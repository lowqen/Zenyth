package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.Reconciler

/**
 * Settles one source (web `syncFolder` + `purgeLibraryTrash`; design §4):
 *
 * 1. [LibrarySource.recover] — finish or undo replaces cut short.
 * 2. Sidecar + listing → [Reconciler]. With no sidecar yet (first open, or
 *    someone deleted it) the caller's [previous] entries are the base.
 * 3. Trash older than 30 days goes — entries and `.zenyth-trash/<ms>-…` files
 *    by their prefix (the PC's trash too).
 * 4. The sidecar is written when anything changed or it was not there.
 *
 * The sidecar is the record; callers keep the returned list in memory.
 */
object LibrarySync {

    suspend fun sync(
        source: LibrarySource,
        takenIds: Set<String> = emptySet(),
        previous: List<LibraryEntry> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): List<LibraryEntry> {
        source.recover()
        val manifest = source.readManifest()
        val files = source.list()
        val base = manifest.entries.ifEmpty { previous }
        val r = Reconciler.reconcile(base, files, { source.fingerprint(it) }, takenIds, now)

        val before = now - LibraryEntries.TRASH_DAYS * LibraryEntries.DAY_MS
        val (expired, kept) = r.entries.partition { (it.trashedAt ?: Long.MAX_VALUE) < before }
        for (e in expired) e.trashFile?.let { runCatching { source.purge(it) } }
        runCatching { source.sweepTrash(before) }

        if (r.changed || expired.isNotEmpty() || manifest.raw == null) {
            source.writeManifest(manifest.copy(entries = kept))
        }
        return kept
    }
}
