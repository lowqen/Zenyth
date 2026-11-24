package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.domain.library.FileStat
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import dev.gomoku.zenyth.domain.library.LibraryFileNames.OLD
import dev.gomoku.zenyth.domain.library.LibraryFileNames.RENAME
import dev.gomoku.zenyth.domain.library.LibraryFileNames.TMP
import dev.gomoku.zenyth.domain.library.LibraryManifest
import dev.gomoku.zenyth.domain.library.ManifestCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Collections

/**
 * A library source over a [DocTree] — one implementation for app storage and
 * SAF folders (design §3.2 · §3.3), so the sidecar, companions, trash and
 * recovery are written once.
 *
 * **Atomic replace.** A tree with [DocTree.atomicReplace] swaps the temp file
 * in with one rename. SAF has no overwriting rename (it answers ` (1)`), so:
 *
 * ```
 * 1. create <name>.zenyth-tmp → write → sync → caller reads back and checks
 * 2. rename <name>            → <name>.zenyth-old     (when there is one)
 * 3. rename <name>.zenyth-tmp → <name>                (the returned name must be <name>)
 * 4. delete <name>.zenyth-old
 * ```
 *
 * [recover] settles whatever a cut left, before every reconcile:
 *
 * | seen | meaning | done |
 * |---|---|---|
 * | no `<name>`, old, tmp | cut between 2 and 3 | tmp checks out → finish 3; else old back |
 * | no `<name>`, old only | 3 failed | old → `<name>` |
 * | `<name>` + old | cut before 4 | delete old (and any tmp) |
 * | tmp only | cut during 1 | delete tmp |
 *
 * These names do not end in `.db`, so the web (`isDbFileName`) and desktop
 * (`lib_list`) already ignore them. A name being written now is skipped.
 *
 * **Trash** is `.zenyth-trash/<ms>-<name>` (companions: the same token plus
 * their tail) — the same place web FSA uses, so the PC sees the same trash.
 */
open class TreeSource(
    override val id: String,
    override val kind: SourceKind,
    override val label: String,
    val tree: DocTree,
    override val access: StateFlow<SourceAccess> = MutableStateFlow(SourceAccess.GRANTED),
    private val verifier: ReplaceVerifier = ReplaceVerifier.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibrarySource {

    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val manifestLock = Mutex()

    /** Text of a sidecar that did not parse — saved beside it before the first overwrite. */
    @Volatile private var brokenText: String? = null

    private fun key(name: String) = LibraryFileNames.key(name)

    private fun files(dir: String?): List<DocEntry> = tree.children(dir).filter { !it.isDir }

    /** Exact name first, then the same name by key (case-insensitive providers). */
    private fun List<DocEntry>.find(name: String): DocEntry? =
        firstOrNull { it.name == name } ?: firstOrNull { key(it.name) == key(name) }

    private fun List<DocEntry>.taken(name: String): Boolean = any { key(it.name) == key(name) }

    private fun companions(entries: List<DocEntry>, base: String): List<Pair<String, DocEntry>> =
        entries.mapNotNull { e -> LibraryFileNames.companionSuffix(base, e.name)?.let { it to e } }

    private fun stat(e: DocEntry) = FileStat(e.name, e.size, e.mtime)

    private fun checkIdle(name: String) {
        if (key(name) in inFlight) throw IllegalStateException("busy: $name")
    }

    // ── reading ────────────────────────────────────────────────────────

    override suspend fun list(): List<FileStat> = withContext(io) {
        files(null).filter {
            !it.name.startsWith(".") && !LibraryFileNames.isInFlight(it.name) &&
                it.name != LibraryEntries.MANIFEST_FILE && !it.name.startsWith(LibraryEntries.MANIFEST_FILE + ".")
        }.map { stat(it) }
    }

    override suspend fun stat(name: String): FileStat? = withContext(io) { files(null).find(name)?.let { stat(it) } }

    override suspend fun openRead(name: String): InputStream? = withContext(io) {
        files(null).find(name)?.let { tree.openInput(it.id) }
    }

    override suspend fun fingerprint(name: String): Fingerprint? = withContext(io) {
        val e = files(null).find(name) ?: return@withContext null
        tree.openInput(e.id).use { Fingerprint.read(it, e.size, e.mtime) }
    }

    // ── atomic write ───────────────────────────────────────────────────

    override suspend fun writeAtomic(name: String, dropJournal: Boolean, write: suspend (StagedFile) -> Unit): FileStat {
        if (!inFlight.add(key(name))) throw IllegalStateException("busy: $name")
        try {
            val tmpName = name + TMP
            val tmp = withContext(io) {
                files(null).find(tmpName)?.let { tree.delete(it.id) }
                val t = tree.create(null, tmpName)
                if (t.name != tmpName) {
                    runCatching { tree.delete(t.id) }
                    throw IOException("provider named the temp file ${t.name}")
                }
                t
            }
            try {
                write(StagedFile(tree, tmp.id))
            } catch (t: Throwable) {
                withContext(NonCancellable + io) { runCatching { tree.delete(tmp.id) } }
                throw t
            }
            return withContext(NonCancellable + io) {
                val placed = try {
                    swapIn(tmp, name)
                } catch (t: Throwable) {
                    // The real file is back in place → the temp file is litter. If it is not, keep the temp
                    // file: recovery checks it and commits it or restores `.zenyth-old`.
                    runCatching {
                        val now = files(null)
                        if (now.any { it.name == name }) now.find(tmpName)?.let { tree.delete(it.id) }
                    }
                    throw t
                }
                if (dropJournal) files(null).find(name + ".journal")?.let { runCatching { tree.delete(it.id) } }
                stat(files(null).find(placed.name) ?: placed)
            }
        } finally {
            inFlight.remove(key(name))
        }
    }

    private fun swapIn(tmp: DocEntry, name: String): DocEntry {
        if (tree.atomicReplace) return tree.replace(tmp.id, name)
        val entries = files(null)
        val cur = entries.firstOrNull { it.name == name } ?: entries.find(name)
        val old: DocEntry? = if (cur != null) {
            entries.find(name + OLD)?.let { tree.delete(it.id) }
            val o = tree.rename(cur.id, name + OLD)
            if (o.name != name + OLD) {
                runCatching { tree.rename(o.id, cur.name) }
                throw IOException("provider named ${o.name} for ${name + OLD}")
            }
            o
        } else {
            null
        }
        val placed = try {
            tree.rename(tmp.id, name)
        } catch (t: Throwable) {
            // Step 3 refused: the old file back now (recovery would do the same on the next reconcile).
            if (old != null) runCatching { tree.rename(old.id, name) }
            throw t
        }
        if (placed.name != name) {
            // Someone took <name> between the steps: put things back as they were.
            runCatching { tree.rename(placed.id, name + TMP) }
            if (old != null) runCatching { tree.rename(old.id, name) }
            throw IOException("name taken: $name")
        }
        if (old != null) runCatching { tree.delete(old.id) }   // a cut here is "name + old" → recover deletes
        return placed
    }

    override suspend fun recover(): Unit = withContext(io) {
        val entries = files(null)
        val byName = entries.associateBy { it.name }
        val bases = LinkedHashSet<String>()
        for (e in entries) {
            when {
                e.name.endsWith(TMP) -> bases += e.name.dropLast(TMP.length)
                e.name.endsWith(OLD) -> bases += e.name.dropLast(OLD.length)
            }
        }
        for (base in bases) {
            if (key(base) in inFlight) continue
            val cur = byName[base]
            val old = byName[base + OLD]
            val tmp = byName[base + TMP]
            runCatching {
                when {
                    cur == null && old != null && tmp != null ->
                        if (verifier.ok(base) { tree.openInput(tmp.id) }) {
                            val placed = tree.rename(tmp.id, base)
                            if (placed.name == base) tree.delete(old.id)
                        } else {
                            val back = tree.rename(old.id, base)
                            if (back.name == base) tree.delete(tmp.id)
                        }
                    cur == null && old != null -> tree.rename(old.id, base)
                    cur != null -> {
                        old?.let { tree.delete(it.id) }
                        tmp?.let { tree.delete(it.id) }
                    }
                    tmp != null -> tree.delete(tmp.id)
                    else -> Unit
                }
            }
        }
        // A case-only rename cut halfway: back to its old name if that is free.
        for (e in entries) {
            if (!e.name.endsWith(RENAME)) continue
            val base = e.name.dropLast(RENAME.length)
            if (key(base) in inFlight) continue
            if (!entries.taken(base)) runCatching { tree.rename(e.id, base) }
        }
    }

    // ── rename · delete ────────────────────────────────────────────────

    /** Renames to exactly [name] or undoes and throws (SAF may answer another name). */
    private fun renameExact(e: DocEntry, name: String): DocEntry {
        val r = tree.rename(e.id, name)
        if (r.name != name) {
            runCatching { tree.rename(r.id, e.name) }
            throw IOException("provider named ${r.name} for $name")
        }
        return r
    }

    override suspend fun rename(from: String, to: String): String = withContext(io) {
        checkIdle(from)
        val entries = files(null)
        val src = entries.find(from) ?: throw FileNotFoundException(from)
        val caseOnly = from != to && from.lowercase() == to.lowercase()
        if (!caseOnly && entries.taken(to)) throw IOException("exists: $to")
        val tails = companions(entries, src.name)
        for ((sfx, _) in tails) {
            val n = LibraryFileNames.withTail(to, sfx)
            if (!caseOnly && entries.taken(n)) throw IOException("exists: $n")
        }
        val done = ArrayList<Pair<DocEntry, String>>()
        try {
            for ((sfx, e) in listOf("" to src) + tails) {
                val target = if (sfx.isEmpty()) to else LibraryFileNames.withTail(to, sfx)
                var cur = e
                try {
                    if (caseOnly) cur = renameExact(cur, cur.name + RENAME)
                    cur = renameExact(cur, target)
                } catch (t: Throwable) {
                    if (cur !== e) runCatching { tree.rename(cur.id, e.name) }
                    throw t
                }
                done += cur to e.name
            }
        } catch (t: Throwable) {
            for ((now, orig) in done.asReversed()) runCatching { tree.rename(now.id, orig) }
            throw t
        }
        to
    }

    override suspend fun delete(name: String): Unit = withContext(io) {
        checkIdle(name)
        val entries = files(null)
        val main = entries.find(name) ?: return@withContext
        for ((_, e) in companions(entries, main.name)) tree.delete(e.id)
        tree.delete(main.id)
    }

    // ── trash ──────────────────────────────────────────────────────────

    /** Moves [e] from [fromDir] to [toDir] named [name] — provider move, else copy then delete. */
    private fun moveInto(e: DocEntry, fromDir: String?, toDir: String?, name: String): DocEntry {
        val moved = runCatching { tree.move(e.id, fromDir, toDir) }.getOrNull()
        if (moved != null) return if (moved.name == name) moved else renameExact(moved, name)
        val c = tree.create(toDir, name)
        try {
            tree.openInput(e.id).use { i -> tree.openOutput(c.id).use { o -> i.copyTo(o, 256 * 1024) } }
        } catch (t: Throwable) {
            runCatching { tree.delete(c.id) }
            throw t
        }
        tree.delete(e.id)
        return c
    }

    override suspend fun trash(name: String): String = withContext(io) {
        checkIdle(name)
        val entries = files(null)
        val src = entries.find(name) ?: throw FileNotFoundException(name)
        val bin = tree.dir(LibraryEntries.TRASH_DIR, create = true)
        val token = "${clock()}-${src.name}"
        val tails = companions(entries, src.name)
        moveInto(src, null, bin, token)
        for ((sfx, e) in tails) moveInto(e, null, bin, LibraryFileNames.withTail(token, sfx))
        token
    }

    override suspend fun restore(trashName: String, name: String): String = withContext(io) {
        val bin = tree.dir(LibraryEntries.TRASH_DIR, create = false) ?: throw FileNotFoundException(trashName)
        val binFiles = files(bin)
        val src = binFiles.find(trashName) ?: throw FileNotFoundException(trashName)
        if (files(null).taken(name)) throw IOException("exists: $name")
        val tails = companions(binFiles, src.name)
        moveInto(src, bin, null, name)
        for ((sfx, e) in tails) moveInto(e, bin, null, LibraryFileNames.withTail(name, sfx))
        name
    }

    override suspend fun purge(trashName: String): Unit = withContext(io) {
        val bin = tree.dir(LibraryEntries.TRASH_DIR, create = false) ?: return@withContext
        val binFiles = files(bin)
        val main = binFiles.find(trashName) ?: return@withContext
        for ((_, e) in companions(binFiles, main.name)) tree.delete(e.id)
        tree.delete(main.id)
    }

    override suspend fun sweepTrash(before: Long): Int = withContext(io) {
        val bin = tree.dir(LibraryEntries.TRASH_DIR, create = false) ?: return@withContext 0
        var n = 0
        for (e in files(bin)) {
            val ms = e.name.substringBefore('-', "").toLongOrNull() ?: continue
            if (ms < before && runCatching { tree.delete(e.id) }.isSuccess) n++
        }
        n
    }

    // ── sidecar ────────────────────────────────────────────────────────

    override suspend fun readManifest(): LibraryManifest = withContext(io) {
        val e = files(null).find(LibraryEntries.MANIFEST_FILE)
        val text = e?.let { tree.openInput(it.id).use { s -> s.readBytes().decodeToString() } }
        val parsed = ManifestCodec.parse(text)
        brokenText = if (parsed.broken) text else null
        parsed.manifest
    }

    override suspend fun writeManifest(m: LibraryManifest) {
        manifestLock.withLock {
            brokenText?.let { text ->
                withContext(io) {
                    val c = tree.create(null, "${LibraryEntries.MANIFEST_FILE}.broken-${clock()}")
                    tree.openOutput(c.id).use { it.write(text.toByteArray()) }
                }
                brokenText = null
            }
            val bytes = ManifestCodec.render(m).toByteArray()
            writeAtomic(LibraryEntries.MANIFEST_FILE, dropJournal = false) { staged ->
                withContext(io) { staged.openOutput().use { it.write(bytes) } }
            }
        }
    }
}
