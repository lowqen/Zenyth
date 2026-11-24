package dev.gomoku.zenyth.data.library

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A plain directory as a [DocTree] — the app-storage source
 * (`filesDir/library`, design §3.2). Ids are paths relative to [root] with
 * `/`. Replacing is one POSIX rename on the same file system.
 */
class FileDocTree(val root: File) : DocTree {
    override val atomicReplace: Boolean get() = true

    fun file(id: String): File = if (id.isEmpty()) root else File(root, id)

    private fun dirFile(dir: String?): File = if (dir == null) root else File(root, dir)

    private fun idOf(dir: String?, name: String) = if (dir == null) name else "$dir/$name"

    private fun entryOf(dir: String?, f: File) =
        DocEntry(idOf(dir, f.name), f.name, if (f.isFile) f.length() else 0L, f.lastModified(), f.isDirectory)

    override fun children(dir: String?): List<DocEntry> =
        dirFile(dir).listFiles().orEmpty().map { entryOf(dir, it) }

    override fun dir(name: String, create: Boolean): String? {
        val d = File(root, name)
        if (d.isDirectory) return name
        if (!create) return null
        if (!d.mkdirs() && !d.isDirectory) throw IOException("mkdir $d")
        return name
    }

    override fun create(dir: String?, name: String): DocEntry {
        val parent = dirFile(dir)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("mkdir $parent")
        val f = File(parent, name)
        if (!f.createNewFile()) throw IOException("exists: $f")
        return entryOf(dir, f)
    }

    override fun openInput(id: String): InputStream = FileInputStream(file(id))

    override fun openOutput(id: String): OutputStream = object : FileOutputStream(file(id)) {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                fd.sync()
            } finally {
                super.close()
            }
        }
    }

    private fun parentId(id: String): String? = id.substringBeforeLast('/', "").ifEmpty { null }

    override fun rename(id: String, name: String): DocEntry {
        val from = file(id)
        val dir = parentId(id)
        val to = File(dirFile(dir), name)
        val caseOnly = from.name != name && from.name.equals(name, ignoreCase = true)
        if (to.exists() && !caseOnly) throw IOException("exists: $to")
        if (!from.renameTo(to)) throw IOException("rename $from → $name")
        return entryOf(dir, to)
    }

    override fun delete(id: String) {
        val f = file(id)
        if (f.exists() && !f.delete()) throw IOException("delete $f")
    }

    override fun move(id: String, fromDir: String?, toDir: String?): DocEntry? {
        val from = file(id)
        val target = dirFile(toDir)
        if (!target.isDirectory && !target.mkdirs()) throw IOException("mkdir $target")
        val to = File(target, from.name)
        if (to.exists()) throw IOException("exists: $to")
        if (!from.renameTo(to)) throw IOException("move $from → $target")
        return entryOf(toDir, to)
    }

    override fun replace(id: String, name: String): DocEntry {
        val from = file(id)
        val dir = parentId(id)
        val to = File(dirFile(dir), name)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return entryOf(dir, to)
    }
}
