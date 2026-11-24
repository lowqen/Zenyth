package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copy · move an entry to another source (design §9-8): app storage is
 * invisible, so this is how a DB gets out to a folder (and back in).
 *
 * - The file name in the target: app storage `<id>.db`; a folder takes the
 *   title through `fileNameFor` past the names already there.
 * - The main file goes through the target's atomic write and is checked by
 *   size + tail fingerprint against the source before it counts. A leftover
 *   journal travels with it (it holds saves not yet folded in).
 * - Copy: a new id; title, cover and stats carry over (same bytes, same
 *   stats). Move: the same id, and the source file goes to the source's
 *   trash — [Result.sourceEntry] is the source's entry marked trashed under a
 *   new id, so it can still be restored from there for 30 days.
 *
 * The caller writes both sidecars (target first — a cut in between leaves the
 * id in two sources, which the next reconcile splits; nothing is lost). An
 * attached entry is folded first (L5-4). The bytes are copied in this process
 * for now; L5-3 hands [copyBytes] to the `:library` job.
 */
object LibraryTransfer {

    class Result(
        /** The entry for the target's sidecar. */
        val entry: LibraryEntry,
        /** Move only: replaces the entry in the source's sidecar. */
        val sourceEntry: LibraryEntry?,
    )

    suspend fun transfer(
        entry: LibraryEntry,
        from: LibrarySource,
        to: LibrarySource,
        toEntries: List<LibraryEntry>,
        move: Boolean,
        now: Long = System.currentTimeMillis(),
        copyBytes: suspend (InputStream, OutputStream) -> Unit = { i, o -> withContext(Dispatchers.IO) { i.copyTo(o, 256 * 1024) } },
    ): Result {
        require(from.id != to.id) { "same source" }
        val want = from.fingerprint(entry.file) ?: throw FileNotFoundException(entry.file)
        val id = if (move) entry.id else LibraryEntries.newId()
        val name = when (to.kind) {
            SourceKind.APP -> AppDirSource.fileNameOf(id)
            SourceKind.SAF -> LibraryFileNames.fileNameFor(
                entry.title,
                to.list().map { it.name } + toEntries.filter { it.trashedAt == null }.map { it.file },
            )
        }
        val journal = entry.file + ".journal"
        val hasJournal = from.stat(journal) != null

        val placed = to.writeAtomic(name) { staged ->
            val input = from.openRead(entry.file) ?: throw FileNotFoundException(entry.file)
            input.use { i -> staged.openOutput().use { o -> copyBytes(i, o) } }
            val got = withContext(Dispatchers.IO) { staged.openInput().use { Fingerprint.read(it, want.size, 0) } }
            if (!got.sameFile(want)) throw IOException("copy of ${entry.file} does not match")
        }
        if (hasJournal) {
            try {
                to.writeAtomic(name + ".journal", dropJournal = false) { staged ->
                    val input = from.openRead(journal) ?: throw FileNotFoundException(journal)
                    input.use { i -> staged.openOutput().use { o -> copyBytes(i, o) } }
                }
            } catch (t: Throwable) {
                runCatching { to.delete(name) }
                throw t
            }
        }

        val copied = entry.copy(
            id = id,
            file = name,
            fingerprint = Fingerprint(placed.size, placed.mtime, want.tail),
            createdAt = if (move) entry.createdAt else now,
            updatedAt = now,
            lastOpenedAt = if (move) entry.lastOpenedAt else null,
            trashedAt = null, trashFile = null, missingSince = null, fileRenamePending = null,
        )
        val sourceEntry = if (move) {
            val token = from.trash(entry.file)
            entry.copy(id = LibraryEntries.newId(), trashedAt = now, trashFile = token)
        } else {
            null
        }
        return Result(copied, sourceEntry)
    }
}
