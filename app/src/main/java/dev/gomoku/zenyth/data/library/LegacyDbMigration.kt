package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import java.io.File
import java.io.FileInputStream

/**
 * First start with the library: the engine's old `filesDir/engine/rapfi.db`
 * (+ `.journal`, numbered backups `rapfi_bak<N>.db`) becomes the first entry
 * «내 DB» in app storage (design §3.2) — renamed, never copied (same file
 * system), before the engine starts.
 *
 * - The plan (`library/.migrating` = the new id) is written before the first
 *   rename, so a process killed halfway finishes on the next start instead of
 *   leaving a nameless file. A rename that fails outright undoes the others
 *   and drops the plan — nothing moved, try again next start.
 * - `library/.migrated` marks it done. No main file → a lone journal is
 *   dropped (the engine drops it too) and nothing else happens.
 *
 * Wiring it in front of the engine start goes with the config url (L5-4):
 * until the engine opens `library/<id>.db`, moving its file away would hand it
 * an empty database.
 */
class LegacyDbMigration(
    private val engineDir: File,
    private val app: AppDirSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = LibraryEntries::newId,
) {
    sealed interface Outcome {
        /** Done before (marker present). */
        data object Done : Outcome
        /** No old database to move. */
        data object NoLegacy : Outcome
        data class Migrated(val entry: LibraryEntry) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private val marker get() = File(app.dir, MARKER)
    private val plan get() = File(app.dir, PLAN)

    suspend fun run(title: String = "내 DB"): Outcome {
        if (marker.exists()) return Outcome.Done
        if (!app.dir.isDirectory && !app.dir.mkdirs()) return Outcome.Failed(java.io.IOException("mkdir ${app.dir}"))
        val planned = plan.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val legacy = File(engineDir, LEGACY)
        if (planned == null && !legacy.isFile) {
            File(engineDir, LEGACY + ".journal").delete()
            marker.writeText("")
            return Outcome.NoLegacy
        }
        val id = planned ?: newId().also { plan.writeText(it) }
        val target = AppDirSource.fileNameOf(id)

        // Main last: while rapfi.db is still in the engine folder the plan is unfinished.
        val moves = engineDir.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { f -> LibraryFileNames.companionSuffix(LEGACY, f.name)?.let { f to File(app.dir, LibraryFileNames.withTail(target, it)) } }
            .plus(if (legacy.isFile) listOf(legacy to File(app.dir, target)) else emptyList())
        val done = ArrayList<Pair<File, File>>()
        for ((from, to) in moves) {
            if (to.exists() || !from.renameTo(to)) {
                // A fresh run puts everything back; a resumed one keeps its plan (earlier moves are already out).
                if (planned == null) {
                    for ((a, b) in done.asReversed()) b.renameTo(a)
                    plan.delete()
                }
                return Outcome.Failed(java.io.IOException("cannot move ${from.name} → ${to.name}"))
            }
            done += from to to
        }

        val main = File(app.dir, target)
        return try {
            val manifest = app.readManifest()
            var entry = manifest.entries.firstOrNull { it.id == id }
            if (entry == null && main.isFile) {
                val fp = FileInputStream(main).use { Fingerprint.read(it, main.length(), main.lastModified()) }
                entry = LibraryEntries.newEntry(title, target, LibraryCover.ECLIPSE, main.lastModified())
                    .copy(id = id, fingerprint = fp, lastOpenedAt = clock())
                app.writeManifest(manifest.copy(entries = manifest.entries + entry))
            }
            marker.writeText(id)
            plan.delete()
            if (entry != null) Outcome.Migrated(entry) else Outcome.NoLegacy
        } catch (t: Throwable) {
            // Keep the plan: the files are in place, the next start writes the entry.
            Outcome.Failed(t)
        }
    }

    companion object {
        const val LEGACY = "rapfi.db"
        const val MARKER = ".migrated"
        const val PLAN = ".migrating"
    }
}
