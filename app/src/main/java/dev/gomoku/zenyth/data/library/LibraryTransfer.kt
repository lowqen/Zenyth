package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

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
 * id in two sources, which the next reconcile splits; nothing is lost) and
 * folds an attached entry first ([LibraryAttachController.foldForJob]). The
 * bytes go through [copier] — on a device the `:library` job ([dev.gomoku.zenyth.data.library.job.JobCopier]),
 * so the library's job line shows the copy and its [취소] stops it; a copy cut
 * short leaves nothing in the target (atomic write).
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
        copier: LibraryCopier = StreamCopier,
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
        val journalStat = from.stat(journal)

        val placed = to.writeAtomic(name) { staged ->
            val tail = copier.copy(want.size, from.copyFrom(entry.file), staged.copyTo())
            val got = withContext(Dispatchers.IO) { staged.openInput().use { Fingerprint.read(it, want.size, 0) } }
            if (tail != want.tail || !got.sameFile(want)) throw IOException("copy of ${entry.file} does not match")
        }
        if (journalStat != null) {
            try {
                to.writeAtomic(name + ".journal", dropJournal = false) { staged ->
                    copier.copy(journalStat.size, from.copyFrom(journal), staged.copyTo())
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

    private fun LibrarySource.copyFrom(name: String) = CopyFrom(
        { openRead(name) ?: throw FileNotFoundException(name) },
        { openFd(name, "r") ?: throw FileNotFoundException(name) },
    )

    private fun StagedFile.copyTo() = CopyTo({ openOutput() }, { openFd("wt") })
}
