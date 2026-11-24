package dev.gomoku.zenyth.data.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory document tree that behaves like `ExternalStorageProvider`:
 * names are case-insensitive, a rename or create onto a taken name answers
 * ` (1)` instead of failing, and renaming or moving gives the document a new
 * id. [failOn] throws before an operation, to cut a sequence anywhere.
 */
class FakeDocTree(private val canMove: Boolean = true) : DocTree {
    override val atomicReplace: Boolean get() = false

    class Node(var id: String, var name: String, var parent: String?, val isDir: Boolean, var bytes: ByteArray, var mtime: Long)

    private val nodes = LinkedHashMap<String, Node>()
    private var next = 0
    var clock = 1_000L
    var failOn: ((op: String, name: String) -> Boolean)? = null
    val ops = ArrayList<String>()

    private fun newId() = "doc${next++}"

    private fun fail(op: String, name: String) {
        ops += "$op $name"
        if (failOn?.invoke(op, name) == true) throw IOException("injected: $op $name")
    }

    private fun taken(parent: String?, name: String, self: Node? = null) =
        nodes.values.any { it !== self && it.parent == parent && it.name.equals(name, ignoreCase = true) }

    /** `a.db` → `a (1).db` like `FileUtils.buildUniqueFile`. Case-only renames collide with themselves. */
    private fun unique(parent: String?, name: String): String {
        if (!taken(parent, name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (taken(parent, "$stem ($n)$ext")) n++
        return "$stem ($n)$ext"
    }

    private fun entry(n: Node) = DocEntry(n.id, n.name, n.bytes.size.toLong(), n.mtime, n.isDir)

    private fun node(id: String) = nodes[id] ?: throw IOException("no document $id")

    private fun rekey(n: Node) {
        nodes.remove(n.id)
        val old = n.id
        n.id = newId()
        nodes[n.id] = n
        for (c in nodes.values) if (c.parent == old) c.parent = n.id
    }

    // ── test helpers ──

    fun put(name: String, bytes: ByteArray, dir: String? = null, mtime: Long = ++clock): DocEntry {
        val n = Node(newId(), name, dir, false, bytes, mtime)
        nodes[n.id] = n
        return entry(n)
    }

    fun put(name: String, text: String, dir: String? = null) = put(name, text.toByteArray(), dir)

    fun names(dir: String? = null): List<String> = nodes.values.filter { it.parent == dir && !it.isDir }.map { it.name }.sorted()

    fun text(name: String, dir: String? = null): String? =
        nodes.values.firstOrNull { it.parent == dir && it.name == name }?.bytes?.decodeToString()

    fun trashDir(): String? = nodes.values.firstOrNull { it.isDir && it.parent == null && it.name == ".zenyth-trash" }?.id

    // ── DocTree ──

    override fun children(dir: String?): List<DocEntry> = nodes.values.filter { it.parent == dir }.map(::entry)

    override fun dir(name: String, create: Boolean): String? {
        nodes.values.firstOrNull { it.isDir && it.parent == null && it.name == name }?.let { return it.id }
        if (!create) return null
        fail("mkdir", name)
        val n = Node(newId(), unique(null, name), null, true, ByteArray(0), ++clock)
        nodes[n.id] = n
        return n.id
    }

    override fun create(dir: String?, name: String): DocEntry {
        fail("create", name)
        val n = Node(newId(), unique(dir, name), dir, false, ByteArray(0), ++clock)
        nodes[n.id] = n
        return entry(n)
    }

    override fun openInput(id: String): InputStream = ByteArrayInputStream(node(id).bytes)

    override fun openOutput(id: String): OutputStream {
        val n = node(id)
        return object : ByteArrayOutputStream() {
            override fun close() {
                n.bytes = toByteArray()
                n.mtime = ++clock
            }
        }
    }

    override fun rename(id: String, name: String): DocEntry {
        val n = node(id)
        fail("rename", name)
        n.name = if (n.name == name) name else unique(n.parent, name)
        rekey(n)
        return entry(n)
    }

    override fun delete(id: String) {
        val n = node(id)
        fail("delete", n.name)
        nodes.remove(id)
        nodes.values.removeAll { it.parent == id }
    }

    override fun move(id: String, fromDir: String?, toDir: String?): DocEntry? {
        if (!canMove) return null
        val n = node(id)
        fail("move", n.name)
        n.name = unique(toDir, n.name)
        n.parent = toDir
        rekey(n)
        return entry(n)
    }
}
