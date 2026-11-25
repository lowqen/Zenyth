package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.engine.LocalEngineInstaller
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** A [Fingerprint] as the staging marks store it. */
@Serializable
data class StoredFingerprint(val size: Long, val mtime: Long, val tail: String) {
    fun toFingerprint() = Fingerprint(size, mtime, tail)

    companion object {
        fun of(f: Fingerprint) = StoredFingerprint(f.size, f.mtime, f.tail)
    }
}

/** The entry the engine opens, and the path it opens it by (design §5). */
@Serializable
data class AttachedDb(val entryId: String, val sourceId: String, val path: String)

/**
 * `staging/<key>.json` (design §3.3) — the copy of a SAF file the engine holds.
 * [base] is the SAF file (and [baseJournal] its journal) as last copied either
 * way; [dirty] = the engine saved into the copy since then and its bytes moved.
 * [entryId] starts as [key] and changes only when a conflict copy takes over.
 */
@Serializable
data class StagingMark(
    val key: String,
    val entryId: String,
    val sourceId: String,
    /** The file's name in the SAF folder. */
    val name: String,
    val base: StoredFingerprint? = null,
    val baseJournal: StoredFingerprint? = null,
    val dirty: Boolean = false,
)

/**
 * The library's side of the engine's files (L5-4): which entry is attached
 * (`library/.attached.json` — a dot file, never listed), the staging copies of
 * SAF files (`library/staging/`, not listed: a directory), and what the
 * engine opens at boot ([bootPath]).
 *
 * No engine dependency, so the engine repository can ask it for the boot url
 * and the attach controller can sit on top of both.
 */
@Singleton
class AttachedDbStore(
    val app: AppDirSource,
    private val engineDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(sources: LibrarySources, installer: LocalEngineInstaller) :
        this(sources.app, installer.engineDir)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val bootLock = Mutex()

    val stagingDir: File get() = File(app.dir, STAGING_DIR)
    private val attachedFile: File get() = File(app.dir, ATTACHED_FILE)

    // ── attached ───────────────────────────────────────────────────────

    fun attached(): AttachedDb? = synchronized(lock) {
        attachedFile.takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString(AttachedDb.serializer(), f.readText()) }.getOrNull()
        }
    }

    fun setAttached(a: AttachedDb?) = synchronized(lock) {
        if (a == null) attachedFile.delete() else writeAtomic(attachedFile, json.encodeToString(AttachedDb.serializer(), a))
    }

    /** What the engine should open now — [AttachedDb.path], or null for the template's. */
    fun enginePath(): String? = attached()?.path

    // ── staging ────────────────────────────────────────────────────────

    fun stagingFile(key: String) = File(stagingDir, "$key.db")
    fun stagingJournal(key: String) = File(stagingDir, "$key.db.journal")
    fun stagingTemp(key: String, journal: Boolean = false) =
        File(stagingDir, "$key.db" + (if (journal) ".journal" else "") + TMP_SUFFIX)

    fun marks(): List<StagingMark> = synchronized(lock) {
        stagingDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { f -> runCatching { json.decodeFromString(StagingMark.serializer(), f.readText()) }.getOrNull() }
            .sortedBy { it.key }
    }

    fun mark(key: String): StagingMark? = synchronized(lock) {
        File(stagingDir, "$key.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(StagingMark.serializer(), it.readText()) }.getOrNull() }
    }

    fun markForEntry(entryId: String): StagingMark? = marks().firstOrNull { it.entryId == entryId }

    fun putMark(m: StagingMark) = synchronized(lock) {
        stagingDir.mkdirs()
        writeAtomic(File(stagingDir, "${m.key}.json"), json.encodeToString(StagingMark.serializer(), m))
    }

    /** Changes a mark in place; null when it is gone. */
    fun updateMark(key: String, change: (StagingMark) -> StagingMark): StagingMark? = synchronized(lock) {
        val m = mark(key) ?: return@synchronized null
        change(m).also { putMark(it) }
    }

    /** The copy, its journal, the engine's `_bak<N>` backups and the mark. */
    fun dropStaging(key: String) = synchronized(lock) {
        stagingDir.listFiles().orEmpty()
            .filter { it.name == "$key.json" || it.name.startsWith("$key.db") || it.name.startsWith("${key}_bak") }
            .forEach { it.delete() }
    }

    /** Copies cut short (`*.zenyth-tmp`) — before anything stages again. */
    fun sweepTemps() = synchronized(lock) {
        stagingDir.listFiles().orEmpty().filter { it.name.endsWith(TMP_SUFFIX) || it.name.endsWith(".json.tmp") }
            .forEach { it.delete() }
    }

    // ── boot ───────────────────────────────────────────────────────────

    /**
     * The path to bake into `config.toml` before the engine starts:
     *
     * 1. The old `engine/rapfi.db` moves in first ([LegacyDbMigration], once) —
     *    only now, with the url pointing at its new place, is that safe.
     * 2. The attached entry: the migrated «내 DB», or the one chosen before. A
     *    SAF entry needs its staging copy (or, for a new DB, its mark) — an
     *    engine that saved into a missing copy would make an empty file, and a
     *    write-back would then put that over the folder's file. So without one
     *    the attachment is dropped.
     * 3. Nothing attached (a fresh install, or the case above): a new «내 DB»
     *    in app storage, with no file — the engine makes it on its first save.
     *
     * Null only when even that failed — the template's `rapfi.db` then, as before.
     */
    suspend fun bootPath(title: String = tr("내 DB", "My DB", "我的数据库")): String? = bootLock.withLock {
        withContext(Dispatchers.IO) {
            sweepTemps()
            val migrated = LegacyDbMigration(engineDir, app, clock).run(title)
            if (migrated is LegacyDbMigration.Outcome.Failed) {
                // rapfi.db is still in the engine folder (or the plan resumes next start).
                return@withContext attached()?.path
            }
            var a = attached()
            if (a == null && migrated is LegacyDbMigration.Outcome.Migrated) {
                a = AttachedDb(migrated.entry.id, AppDirSource.ID, app.fileOf(migrated.entry.file).absolutePath)
                setAttached(a)
            }
            if (a != null && a.sourceId != AppDirSource.ID) {
                val m = markForEntry(a.entryId)
                val usable = m != null && (stagingFile(m.key).isFile || m.base == null)
                if (!usable) a = null.also { setAttached(null) }
            }
            if (a == null) {
                a = runCatching { newAppEntry(title) }.getOrNull() ?: return@withContext null
                setAttached(a)
            }
            a.path
        }
    }

    /** A new «내 DB» in app storage — an entry with no file yet. */
    suspend fun newAppEntry(title: String): AttachedDb {
        val manifest = app.readManifest()
        val made = LibraryEntries.newEntry(title, "", LibraryCover.ECLIPSE, clock())
        val entry = made.copy(file = AppDirSource.fileNameOf(made.id))
        app.writeManifest(manifest.copy(entries = manifest.entries + entry))
        return AttachedDb(entry.id, AppDirSource.ID, app.fileOf(entry.file).absolutePath)
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Size + tail of a local file, or null when absent. */
    fun fingerprintOf(f: File): Fingerprint? =
        if (!f.isFile) null else FileInputStream(f).use { Fingerprint.read(it, f.length(), f.lastModified()) }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val STAGING_DIR = "staging"
        const val ATTACHED_FILE = ".attached.json"
        private const val TMP_SUFFIX = LibraryFileNames.TMP
    }
}
