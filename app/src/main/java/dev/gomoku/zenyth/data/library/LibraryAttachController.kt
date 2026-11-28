package dev.gomoku.zenyth.data.library

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.DbOpResult
import dev.gomoku.zenyth.data.engine.EngineKeepAlive
import dev.gomoku.zenyth.data.library.job.JobCopier
import dev.gomoku.zenyth.domain.engine.DbSetFile
import dev.gomoku.zenyth.domain.engine.EngineCommand
import dev.gomoku.zenyth.domain.engine.EngineResponse
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import dev.gomoku.zenyth.domain.repository.DatabaseRepository
import dev.gomoku.zenyth.domain.repository.EngineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** [LibraryAttachController.switchTo]. */
sealed interface AttachOutcome {
    data object Already : AttachOutcome
    /** [live]: the running engine now holds it; otherwise recorded for its next attach. */
    data class Attached(val path: String, val live: Boolean) : AttachOutcome
    /** Nothing changed — the old entry is still attached. */
    data class Failed(val reason: String) : AttachOutcome
}

/** [LibraryAttachController.writeBack] — a SAF copy going home (design §3.3). */
sealed interface WriteBackOutcome {
    /** Not dirty, or the folded copy came out the same as the folder's file: no fold, no copy. */
    data object Clean : WriteBackOutcome
    data class Written(val name: String) : WriteBackOutcome
    /** The folder's file changed meanwhile (the PC): the device's copy went beside it as [name]. */
    data class Conflict(val name: String, val entryId: String) : WriteBackOutcome
    /** Still dirty — tried again at the next trigger or start. */
    data class Deferred(val reason: String) : WriteBackOutcome
}

/**
 * Which library entry the on-device engine holds, and the SAF round trip
 * (L5 design §5 · §3.3 · §9, step L5-4).
 *
 * **Switching** ([switchTo]) — web `dbStore.switchTo`'s order:
 * 1. a loaded, writable database is folded (`YXCOMPACTDATABASE`, wait for the
 *    save's end) — if that fails nothing changes;
 * 2. `INFO usedatabase 0` releases it (the engine would otherwise build the
 *    new one before freeing the old);
 * 3. the target's path: app storage as is, a SAF file through its staging copy;
 * 4. `YXSETDATABASE <path>` and an `ABOUT` behind it — commands run in order and
 *    the load is synchronous, so the answer means it is in;
 * 5. a SAF entry left behind writes back after, from the queue;
 * 6. the record ([AttachedDbStore]) is what the next engine start bakes into
 *    `config.toml`.
 * The engine must be idle for 2 and 4 (it drops everything but a stop while
 * searching), so a running search is stopped first. With no database held —
 * off, or not connected — only the record changes, and the next attach goes
 * out as `YXSETDATABASE` to the new entry (engine repository).
 *
 * **Write-back** ([writeBack]) — only when dirty: the engine finished a save
 * into the copy and its bytes moved. Fold, then the copy replaces the folder's
 * file atomically (three steps on SAF) — unless the folder's file is no longer
 * the one copied, then the device's goes beside it as `<name> (이 기기).db` and
 * takes over the attachment. Triggers: a switch, the engine stopping, the app
 * going to the background (under [EngineKeepAlive]), the next start.
 */
@Singleton
class LibraryAttachController(
    private val engine: EngineRepository,
    private val database: DatabaseRepository,
    private val store: AttachedDbStore,
    private val sourceOf: suspend (String) -> LibrarySource?,
    private val copier: LibraryCopier,
    private val keepAlive: EngineKeepAlive,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** After the engine went away: its close-time save finishes before the copy (the reaper allows 3 s). */
    private val stoppedGraceMs: Long = 3_500,
) {
    @Inject constructor(
        engine: EngineRepository,
        database: DatabaseRepository,
        store: AttachedDbStore,
        sources: LibrarySources,
        copier: JobCopier,
        keepAlive: EngineKeepAlive,
    ) : this(
        engine, database, store,
        { id -> sources.sourceOf(id) ?: sources.load().firstOrNull { it.id == id } },
        copier, keepAlive,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** One switch or write-back at a time. */
    private val lock = Mutex()

    /** Ends of saves and database errors the engine reported; [EngineResponse.About] answers. */
    private val saveEnds = MutableStateFlow(0L)
    private val dbErrors = MutableStateFlow(0L)
    private val abouts = MutableStateFlow(0L)
    @Volatile private var lastDbError = ""

    private val _notices = MutableSharedFlow<WriteBackOutcome>(extraBufferCapacity = 8)

    /** Conflicts and deferred write-backs, for a one-line notice (the library screen, L5-6). */
    val notices: SharedFlow<WriteBackOutcome> = _notices.asSharedFlow()

    @Volatile private var started = false

    /** Collectors and the pending write-backs of the last run. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            engine.responses.collect { r ->
                when {
                    isSaveEnd(r) -> {
                        saveEnds.update { it + 1 }
                        noteSaved()
                    }
                    r is EngineResponse.Error && looksLikeDb(r.text) -> {
                        lastDbError = r.text
                        dbErrors.update { it + 1 }
                    }
                    r is EngineResponse.About -> abouts.update { it + 1 }
                }
            }
        }
        // The engine went away: its last save is on disk, the copy can go home from there.
        scope.launch {
            var wasLive = engine.state.value.isLive
            engine.state.collect { st ->
                val live = st.isLive
                if (wasLive && !live && (st is ConnectionState.Disconnected || st is ConnectionState.Error)) {
                    scope.launch { onEngineStopped() }
                }
                wasLive = live
            }
        }
        // A database loaded with its copy still dirty (killed before it went home): finish now.
        scope.launch {
            database.fileState.map { it.loaded }.distinctUntilChanged().collect { loaded ->
                if (!loaded) return@collect
                val a = store.attached() ?: return@collect
                val m = store.markForEntry(a.entryId) ?: return@collect
                if (m.dirty) lock.withLock { report(writeBack(m.key)) }
            }
        }
        scope.launch { resumePending() }
    }

    /** ON_STOP → [onBackground]. Main thread (Application.onCreate). */
    fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) scope.launch { onBackground() }
            },
        )
    }

    // ── switch ─────────────────────────────────────────────────────────

    suspend fun switchTo(source: LibrarySource, entry: LibraryEntry): AttachOutcome = lock.withLock {
        val current = store.attached()
        if (current?.entryId == entry.id && current.sourceId == source.id) return@withLock AttachOutcome.Already
        val target = try {
            withContext(Dispatchers.IO) { targetOf(source, entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return@withLock AttachOutcome.Failed(tr("준비 실패: ", "Could not prepare: ", "准备失败: ") + (t.message ?: t::class.java.simpleName))
        }

        val live = engine.isLocal && engine.state.value.isLive && engine.holdsDatabase
        if (live) {
            engine.awaitIdle()
            val size = current?.path?.let { File(it).length() } ?: 0L
            val folded = fold(size)
            if (folded is Fold.Failed) {
                return@withLock AttachOutcome.Failed(tr("지금 DB 저장 실패 — 바꾸지 않았습니다: ", "Saving the current DB failed — nothing switched: ", "当前数据库保存失败，未切换: ") + folded.reason)
            }
            current?.let { noteSavedNow(it) }
            engine.send(EngineCommand.Info("usedatabase", "0"))
            if (!loadLive(target.path, File(target.path).length())) {
                // Put the old one back rather than leave the engine with nothing.
                current?.let { loadLive(it.path, File(it.path).length()) }
                return@withLock AttachOutcome.Failed(tr("엔진이 새 DB 를 열지 못했습니다", "The engine could not open the new DB", "引擎无法打开新数据库"))
            }
            runCatching { database.refresh() }
        }
        store.setAttached(target)
        // The engine keeps numbered backups beside the file it saves; the entry left
        // behind rests now and needs none (L5-7: 79MB each on a phone).
        runCatching { withContext(Dispatchers.IO) { store.sweepBackups() } }
        // The entry left behind goes home after this returns — the switch does not wait for it.
        if (current != null && current.sourceId != AppDirSource.ID) {
            store.markForEntry(current.entryId)?.takeIf { it.dirty }?.let { m ->
                scope.launch { lock.withLock { report(writeBack(m.key)) } }
            }
        }
        AttachOutcome.Attached(target.path, live)
    }

    /**
     * Before a job reads [entryId]'s file (merge · split · copy, L5-7): the
     * attached entry is folded, so its main file stands alone, and a folder
     * entry's staging copy holding changes goes home first. Null when the file
     * at the source is now complete; otherwise why not. An entry neither
     * attached nor staged returns at once.
     */
    suspend fun foldForJob(entryId: String): String? = lock.withLock {
        val a = store.attached()?.takeIf { it.entryId == entryId }
        if (a != null && engine.isLocal && engine.state.value.isLive && engine.holdsDatabase) {
            engine.awaitIdle()
            val folded = fold(File(a.path).length())
            if (folded is Fold.Failed) return@withLock folded.reason
            noteSavedNow(a)
        }
        val m = store.markForEntry(entryId)?.takeIf { it.dirty } ?: return@withLock null
        when (val o = writeBack(m.key).also { report(it) }) {
            is WriteBackOutcome.Deferred -> o.reason
            is WriteBackOutcome.Conflict -> tr("폴더의 파일이 그새 바뀌어 기기 사본을 «${o.name}» 으로 따로 두었습니다. 다시 고르세요", "The folder's file changed meanwhile — this device's copy is now «${o.name}». Choose again", "文件夹中的文件已被修改，本机副本另存为«${o.name}»，请重新选择")
            else -> null
        }
    }

    private suspend fun targetOf(source: LibrarySource, entry: LibraryEntry): AttachedDb = when (source) {
        is AppDirSource -> AttachedDb(entry.id, source.id, source.fileOf(entry.file).absolutePath)
        else -> {
            val m = stage(source, entry)
            AttachedDb(entry.id, source.id, store.stagingFile(m.key).absolutePath)
        }
    }

    /**
     * The staging copy of a SAF entry: kept when it is the folder's file as
     * last copied (or holds changes not yet home — never copied over), copied
     * again otherwise. The folder's journal travels with it.
     */
    suspend fun stage(source: LibrarySource, entry: LibraryEntry): StagingMark = withContext(Dispatchers.IO) {
        val existing = store.markForEntry(entry.id)
        val now = source.fingerprint(entry.file)
        val nowJ = source.fingerprint(entry.file + JOURNAL)
        if (existing != null) {
            val kept = existing.dirty ||
                (same(now, existing.base) && same(nowJ, existing.baseJournal) && (now == null || store.stagingFile(existing.key).isFile))
            if (kept) {
                return@withContext if (existing.name == entry.file) existing else existing.copy(name = entry.file).also { store.putMark(it) }
            }
        }
        val key = existing?.key ?: entry.id
        store.stagingDir.mkdirs()
        val main = store.stagingFile(key)
        val journal = store.stagingJournal(key)
        if (now == null) {
            // A new DB with no file yet — the engine makes the copy on its first save.
            main.delete()
            journal.delete()
        } else {
            val tmp = store.stagingTemp(key)
            val tmpJ = store.stagingTemp(key, journal = true)
            try {
                copyIn(source, entry.file, now, tmp)
                if (nowJ != null) copyIn(source, entry.file + JOURNAL, nowJ, tmpJ)
                if (nowJ != null) move(tmpJ, journal) else journal.delete()
                move(tmp, main)
            } finally {
                tmp.delete()
                tmpJ.delete()
            }
        }
        StagingMark(
            key = key, entryId = entry.id, sourceId = source.id, name = entry.file,
            base = now?.let(StoredFingerprint::of), baseJournal = nowJ?.let(StoredFingerprint::of), dirty = false,
        ).also { store.putMark(it) }
    }

    private suspend fun copyIn(source: LibrarySource, name: String, want: Fingerprint, to: File) {
        to.delete()
        val tail = copier.copy(
            want.size,
            CopyFrom(
                { source.openRead(name) ?: throw FileNotFoundException(name) },
                { source.openFd(name, "r") ?: throw FileNotFoundException(name) },
            ),
            CopyTo(
                { FileOutputStream(to) },
                { ParcelFileDescriptor.open(to, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) },
            ),
        )
        if (tail != want.tail || to.length() != want.size) throw IOException("copy of $name does not match")
    }

    // ── write-back ─────────────────────────────────────────────────────

    /** Writes every dirty copy home now (a switch, a detach, leaving the app). */
    suspend fun writeBackAll(): List<WriteBackOutcome> = lock.withLock {
        store.marks().filter { it.dirty }.map { m -> writeBack(m.key).also { report(it) } }
    }

    /**
     * One staging copy home (caller holds [lock]). The attached one is folded
     * first when the engine holds it; a copy the engine has let go of is sent
     * as it lies, journal and all.
     */
    private suspend fun writeBack(key: String): WriteBackOutcome {
        val start = store.mark(key) ?: return WriteBackOutcome.Clean
        if (!start.dirty) return WriteBackOutcome.Clean
        val source = sourceOf(start.sourceId) ?: return WriteBackOutcome.Deferred("no source ${start.sourceId}")
        if (source.access.value == SourceAccess.LOST) return WriteBackOutcome.Deferred(tr("폴더 권한 없음", "Folder access lost", "文件夹权限丢失"))
        val attached = store.attached()?.entryId == start.entryId
        if (attached && engine.isLocal && engine.state.value.isLive && engine.holdsDatabase) {
            val folded = fold(store.stagingFile(key).length())
            if (folded is Fold.Failed) return WriteBackOutcome.Deferred(folded.reason)
        }
        val gen = saveEnds.value
        return try {
            withContext(Dispatchers.IO) { sendHome(key, source, attached, gen) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "write-back of $key failed", t)
            WriteBackOutcome.Deferred(t.message ?: t::class.java.simpleName)
        }
    }

    private suspend fun sendHome(key: String, source: LibrarySource, attached: Boolean, gen: Long): WriteBackOutcome {
        val m = store.mark(key) ?: return WriteBackOutcome.Clean
        val mainFile = store.stagingFile(key)
        val journalFile = store.stagingJournal(key)
        val main = store.fingerprintOf(mainFile)
        val journal = store.fingerprintOf(journalFile)
        if (same(main, m.base) && same(journal, m.baseJournal)) {
            store.updateMark(key) { it.copy(dirty = saveEnds.value != gen) }
            return WriteBackOutcome.Clean
        }
        if (main == null) return WriteBackOutcome.Deferred("staging copy missing")

        val now = source.fingerprint(m.name)
        val nowJ = source.fingerprint(m.name + JOURNAL)
        val conflict = !same(now, m.base) || !same(nowJ, m.baseJournal)
        val target = if (!conflict) m.name else LibraryFileNames.fileNameFor(conflictTitle(stemOf(m.name)), source.list().map { it.name })

        val placed = source.writeAtomic(target, dropJournal = true) { staged -> copyOut(mainFile, main, staged) }
        val newBase = StoredFingerprint(placed.size, placed.mtime, main.tail)
        var newBaseJ: StoredFingerprint? = null
        if (journal != null) {
            try {
                val pj = source.writeAtomic(target + JOURNAL, dropJournal = false) { staged -> copyOut(journalFile, journal, staged) }
                newBaseJ = StoredFingerprint(pj.size, pj.mtime, journal.tail)
            } catch (t: Throwable) {
                // The main file is home without its journal: record that, so the
                // next try sees our own file there — not a change from the PC.
                store.updateMark(key) { it.copy(name = target, base = newBase, baseJournal = null, dirty = true) }
                throw t
            }
        }

        val stamp = clock()
        val newFp = newBase.toFingerprint()
        // The library screen writes this sidecar too (rename, cover, trash): one writer at a time.
        val entryId = ManifestLocks.with(source.id) {
            val manifest = source.readManifest()
            val orig = manifest.entries.firstOrNull { it.id == m.entryId }
            if (!conflict) {
                if (orig != null) {
                    val changed = orig.fingerprint?.tail != newFp.tail
                    val e = orig.copy(fingerprint = newFp, updatedAt = stamp).let { if (changed) it.withStats(null) else it }
                    source.writeManifest(manifest.copy(entries = manifest.entries.map { if (it.id == e.id) e else it }))
                }
                m.entryId
            } else {
                val title = conflictTitle(orig?.title ?: stemOf(m.name))
                val made = LibraryEntries.newEntry(title, target, now = stamp)
                val e = made.copy(cover = orig?.cover ?: made.cover, fingerprint = newFp)
                source.writeManifest(manifest.copy(entries = manifest.entries + e))
                e.id
            }
        }
        store.updateMark(key) {
            it.copy(entryId = entryId, name = target, base = newBase, baseJournal = newBaseJ, dirty = saveEnds.value != gen)
        }
        if (conflict && attached) {
            store.attached()?.let { a -> store.setAttached(a.copy(entryId = entryId)) }
        }
        return if (conflict) WriteBackOutcome.Conflict(target, entryId) else WriteBackOutcome.Written(target)
    }

    private suspend fun copyOut(from: File, want: Fingerprint, staged: StagedFile) {
        val tail = copier.copy(
            want.size,
            CopyFrom({ FileInputStream(from) }, { ParcelFileDescriptor.open(from, ParcelFileDescriptor.MODE_READ_ONLY) }),
            CopyTo({ staged.openOutput() }, { staged.openFd("wt") }),
        )
        val back = withContext(Dispatchers.IO) { staged.openInput().use { Fingerprint.read(it, want.size, 0) } }
        if (tail != want.tail || !back.sameFile(want)) throw IOException("copy of ${from.name} does not match")
    }

    // ── triggers ───────────────────────────────────────────────────────

    /**
     * ON_STOP (§9 safe background write-back): only while the engine runs —
     * its foreground service is the priority the copy borrows, held up by
     * [EngineKeepAlive]. With the engine already down nothing new is started
     * from the background; the next start does it.
     */
    suspend fun onBackground() {
        if (!engine.state.value.isLive) return
        // Measured now, not waited for: the engine appends a search's records to
        // the journal with no save end to say so, and the save the idle watcher
        // sends on this same ON_STOP ends only after this check (seen on the
        // phone, L5-6) — the flag alone said «clean» and nothing went home.
        store.attached()?.let { a -> withContext(Dispatchers.IO) { noteSavedNow(a) } }
        if (store.marks().none { it.dirty }) return
        keepAlive.acquire(EngineKeepAlive.DB_WRITEBACK)
        try {
            writeBackAll()
        } finally {
            keepAlive.release(EngineKeepAlive.DB_WRITEBACK)
        }
    }

    private suspend fun onEngineStopped() {
        if (!engine.isLocal) return
        if (store.marks().none { it.dirty }) return
        delay(stoppedGraceMs)
        if (engine.state.value.isLive) return // back already (a respawn) — it holds the copy again
        writeBackAll()
    }

    /** Copies left dirty by the last run — except the one a running engine holds (its load edge handles it). */
    private suspend fun resumePending() {
        lock.withLock {
            val attached = store.attached()?.entryId
            for (m in store.marks().filter { it.dirty }) {
                if (m.entryId == attached && engine.state.value.isLive) continue
                report(writeBack(m.key))
            }
        }
    }

    private fun report(o: WriteBackOutcome) {
        if (o is WriteBackOutcome.Conflict || o is WriteBackOutcome.Deferred) _notices.tryEmit(o)
    }

    // ── dirty ──────────────────────────────────────────────────────────

    /** A save ended: is the attached copy now different from its folder file? */
    private fun noteSaved() {
        val a = store.attached() ?: return
        if (a.sourceId == AppDirSource.ID) return
        scope.launch(Dispatchers.IO) { noteSavedNow(a) }
    }

    private fun noteSavedNow(a: AttachedDb) {
        if (a.sourceId == AppDirSource.ID) return
        val m = store.markForEntry(a.entryId) ?: return
        store.updateMark(m.key) { cur ->
            if (cur.dirty) cur else cur.copy(dirty = moved(cur))
        }
    }

    private fun moved(m: StagingMark): Boolean =
        !same(store.fingerprintOf(store.stagingFile(m.key)), m.base) ||
            !same(store.fingerprintOf(store.stagingJournal(m.key)), m.baseJournal)

    // ── engine steps ───────────────────────────────────────────────────

    private sealed interface Fold {
        data object Done : Fold
        /** Nothing to write — not loaded, read-only. */
        data object Skipped : Fold
        data class Failed(val reason: String) : Fold
    }

    /** `YXCOMPACTDATABASE` and its save's end — the main file whole on its own. */
    private suspend fun fold(sizeHint: Long): Fold {
        if (!engine.state.value.isLive || !engine.holdsDatabase) return Fold.Skipped
        if (!database.fileState.value.loaded) return Fold.Skipped
        if (database.state.value.readOnly) return Fold.Skipped
        val limit = timeoutFor(sizeHint)
        if (database.fileState.value.saving) {
            withTimeoutOrNull(limit) { database.fileState.first { !it.saving } }
                ?: return Fold.Failed(tr("이전 저장이 끝나지 않음", "The previous save never ended", "上一次保存未结束"))
        }
        val ends = saveEnds.value
        val errors = dbErrors.value
        val sent = database.compact()
        if (sent is DbOpResult.Refused) return Fold.Failed(sent.reason)
        val ok = withTimeoutOrNull(limit) {
            while (saveEnds.value == ends && dbErrors.value == errors) delay(POLL_MS)
            saveEnds.value != ends
        }
        return when (ok) {
            true -> Fold.Done
            false -> Fold.Failed(lastDbError)
            null -> Fold.Failed(tr("저장 응답 없음", "No answer to the save", "保存无响应"))
        }
    }

    /** `YXSETDATABASE`, then `ABOUT` — answered only once the load (synchronous) is done. */
    private suspend fun loadLive(path: String, sizeHint: Long): Boolean {
        val asked = abouts.value
        val errors = dbErrors.value
        engine.send(DbSetFile(path))
        engine.send(EngineCommand.About)
        val ok = withTimeoutOrNull(timeoutFor(sizeHint)) {
            while (abouts.value == asked && dbErrors.value == errors) delay(POLL_MS)
            abouts.value != asked && dbErrors.value == errors
        }
        return ok == true
    }

    companion object {
        private const val TAG = "LibraryAttach"
        private const val JOURNAL = ".journal"
        private const val POLL_MS = 20L

        /** 30 s plus 1 s per 2 MB — a full save of the 79 MB sample is ~8 s on a PC. */
        fun timeoutFor(size: Long): Long = 30_000L + size / 2_000

        fun isSaveEnd(r: EngineResponse): Boolean = when (r) {
            is EngineResponse.DbFileEvent -> r.saving && !r.started
            is EngineResponse.Message -> r.text.contains("Saved database file", ignoreCase = true)
            else -> false
        }

        private fun looksLikeDb(text: String): Boolean = text.contains("database", ignoreCase = true)

        fun conflictTitle(stem: String) = "$stem (${tr("이 기기", "this device", "本机")})"

        fun stemOf(name: String) = if (name.endsWith(".db", ignoreCase = true)) name.dropLast(3) else name

        private fun same(a: Fingerprint?, b: StoredFingerprint?): Boolean =
            if (a == null) b == null else b != null && a.sameFile(b.toFingerprint())

        private fun move(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
