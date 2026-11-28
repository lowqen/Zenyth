package dev.gomoku.zenyth.data.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.data.library.job.ExpectedFile
import dev.gomoku.zenyth.data.library.job.JobCopier
import dev.gomoku.zenyth.data.library.job.JobOutcome
import dev.gomoku.zenyth.data.library.job.JobPriority
import dev.gomoku.zenyth.data.library.job.JobResult
import dev.gomoku.zenyth.data.library.job.LibraryJob
import dev.gomoku.zenyth.data.library.job.LibraryJobClient
import dev.gomoku.zenyth.data.prefs.EndpointStore
import dev.gomoku.zenyth.domain.library.FileStat
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import dev.gomoku.zenyth.domain.library.LibraryPositions
import dev.gomoku.zenyth.domain.library.StartMode
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.repository.DatabaseRepository
import dev.gomoku.zenyth.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why an entry has no full stats this session (web `ScanNote`) — the detail says it in one line. */
enum class ScanNote {
    /** The index did not fit the memory gate (or its process died): counts and rules only. */
    MEMORY,
    /** Not in stone-count order: openings were not settled. */
    UNORDERED,
    /** Counting failed (not a yixindb, a pipe the job could not re-read, …). */
    ERROR,
    /** The user stopped it. */
    STOPPED,
}

/** A source chip: app storage or a folder. */
data class LibrarySourceView(val id: String, val kind: SourceKind, val label: String, val access: SourceAccess)

/** An entry with the source it lives in and the bytes its file takes (main + journal, 0 = no file yet). */
data class LibraryItem(val sourceId: String, val entry: LibraryEntry, val size: Long) {
    val id: String get() = entry.id
}

/** The count running now. [early] = the first pass's numbers, before the index (design §2.3 two-stage report). */
data class LibraryScan(val entryId: String, val phase: String = "stats", val fraction: Double = 0.0, val early: DbStats? = null)

/** What the last delete moved to the trash — the 10 s «되돌리기». */
data class LibraryUndo(val ids: List<String>, val title: String, val at: Long)

data class LibraryState(
    val loaded: Boolean = false,
    val sources: List<LibrarySourceView> = listOf(LibrarySourceView(AppDirSource.ID, SourceKind.APP, "", SourceAccess.GRANTED)),
    /** Live entries of every reachable source. */
    val items: List<LibraryItem> = emptyList(),
    val trashed: List<LibraryItem> = emptyList(),
    /** The entry the engine opens ([AttachedDbStore]). */
    val attachedId: String? = null,
    val scan: LibraryScan? = null,
    val notes: Map<String, ScanNote> = emptyMap(),
    val undo: LibraryUndo? = null,
    /** Entries a copy · move is working on — rename and delete wait. */
    val working: Set<String> = emptySet(),
)

/** Where [LibraryRepository.open] puts the board. */
sealed interface OpenAt {
    /** The record's start ([StartMode]: where it branches, the largest root, or empty); the board stays when there are no roots. */
    data object Start : OpenAt
    /** The board as it is (the editor). */
    data object Keep : OpenAt
    data class Line(val moves: List<Move>) : OpenAt
}

sealed interface OpenOutcome {
    /** Attached. [moves] = the line to put on the board (null: leave it). */
    data class Opened(val title: String, val moves: List<Move>?, val start: Boolean) : OpenOutcome
    data class Refused(val message: String) : OpenOutcome
}

/**
 * The library screen's state and verbs (web `libraryStore`, L5 design §6 ·
 * step L5-6): every source's entries, file sizes, the stats queue, the trash,
 * and «열기».
 *
 * - **Sidecar first.** Each change reads the source's `zenyth-library.json`,
 *   changes the entry and writes it back under [ManifestLocks] — the same lock
 *   the attach controller's write-back takes — then republishes that source.
 * - **Stats** are counted one entry at a time in the `:library` process
 *   ([LibraryJobClient.scan], background priority: it pauses while the engine
 *   thinks or the phone is hot). An identity that moved under the count throws
 *   the result away. A count without the index is still written (rules,
 *   positions) — the detail page says why the openings are missing, and the
 *   phone does not try the index again for that file.
 * - **Saved** ([onSaved]): when the engine's save rewrote the attached
 *   app-storage file, its identity is measured again (design §5
 *   `recordSaved`); a new tail drops the stats, which then count again.
 */
@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: LibrarySources,
    private val store: AttachedDbStore,
    private val attach: LibraryAttachController,
    private val jobs: LibraryJobClient,
    private val copier: JobCopier,
    private val database: DatabaseRepository,
    private val settings: SettingsRepository,
    private val endpoints: EndpointStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-line notices: a write-back conflict, a deferred write-back. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val loadLock = Mutex()
    @Volatile private var started = false
    @Volatile private var pumpJob: Job? = null
    private val attempts = HashMap<String, Int>()

    /** «다시 세기» on stats that are current but short (openings · roots skipped): count them anyway, once. */
    private val forced = HashSet<String>()

    // ── start ──────────────────────────────────────────────────────────

    private fun start() {
        if (started) return
        started = true
        scope.launch {
            attach.notices.collect { o ->
                when (o) {
                    is WriteBackOutcome.Conflict -> {
                        _notices.tryEmit(tr("폴더의 파일이 그새 바뀌어 기기 사본을 «${o.name}» 으로 따로 두었습니다", "The folder's file changed meanwhile — this device's copy is beside it as «${o.name}»", "文件夹中的文件已被修改，本机副本另存为«${o.name}»"))
                        runCatching { load() }
                    }
                    is WriteBackOutcome.Deferred -> _notices.tryEmit(tr("폴더에 아직 쓰지 못했습니다 (${o.reason}) — 다음에 다시 합니다", "Not written to the folder yet (${o.reason}) — trying again later", "尚未写回文件夹 (${o.reason})，稍后重试"))
                    else -> Unit
                }
            }
        }
        scope.launch {
            var saving = database.fileState.value.saving
            database.fileState.collect { st ->
                if (saving && !st.saving) runCatching { onSaved() }
                saving = st.saving
            }
        }
        scope.launch {
            jobs.active.collect { a ->
                val p = a?.progress ?: return@collect
                if (a.job !is LibraryJob.Scan) return@collect
                _state.update { s -> s.scan?.let { s.copy(scan = it.copy(phase = p.phase, fraction = p.fraction)) } ?: s }
            }
        }
    }

    // ── load ───────────────────────────────────────────────────────────

    /** Reads every source: recover · reconcile · 30-day trash, then the sizes. Starts counting. */
    suspend fun load(): Unit = loadLock.withLock {
        start()
        val list = runCatching { sources.load() }.getOrElse { listOf<LibrarySource>(sources.app) }
        val taken = HashSet<String>()
        val items = ArrayList<LibraryItem>()
        val trashed = ArrayList<LibraryItem>()
        for (src in list) {
            if (src.access.value != SourceAccess.GRANTED) continue
            val previous = entriesOf(src.id)
            val entries = try {
                ManifestLocks.with(src.id) { LibrarySync.sync(src, taken, previous) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "sync of ${src.id} failed", t)
                previous
            }
            taken += entries.map { it.id }
            val files = runCatching { src.list() }.getOrDefault(emptyList())
            for (e in entries) {
                when {
                    e.trashedAt != null -> trashed += LibraryItem(src.id, e, 0)
                    e.missingSince == null -> items += LibraryItem(src.id, e, sizeOf(e.file, files))
                }
            }
        }
        _state.update {
            it.copy(
                loaded = true,
                sources = list.map(::viewOf),
                items = items,
                trashed = trashed,
                attachedId = store.attached()?.entryId,
            )
        }
        for (src in list) if (src.access.value == SourceAccess.GRANTED) retryRenames(src)
        pump()
    }

    private fun viewOf(s: LibrarySource) = LibrarySourceView(s.id, s.kind, s.label, s.access.value)

    private fun entriesOf(sourceId: String): List<LibraryEntry> =
        _state.value.let { s -> (s.items + s.trashed).filter { it.sourceId == sourceId }.map { it.entry } }

    fun item(id: String): LibraryItem? = _state.value.items.firstOrNull { it.id == id }

    private fun anyItem(id: String): LibraryItem? = item(id) ?: _state.value.trashed.firstOrNull { it.id == id }

    private suspend fun sourceOf(id: String): LibrarySource? = sources.sourceOf(id) ?: sources.load().firstOrNull { it.id == id }

    /**
     * Changes one source's sidecar: [change] gets its entries (the ones held
     * here when the sidecar has gone) and returns the new list, then the
     * source is published again.
     */
    private suspend fun edit(src: LibrarySource, change: (List<LibraryEntry>) -> List<LibraryEntry>): List<LibraryEntry> {
        val next = ManifestLocks.with(src.id) {
            val m = src.readManifest()
            val base = m.entries.ifEmpty { entriesOf(src.id) }
            change(base).also { src.writeManifest(m.copy(entries = it)) }
        }
        publish(src, next)
        return next
    }

    private suspend fun publish(src: LibrarySource, entries: List<LibraryEntry>) {
        val files = runCatching { src.list() }.getOrDefault(emptyList())
        val live = entries.filter { it.live }.map { LibraryItem(src.id, it, sizeOf(it.file, files)) }
        val bin = entries.filter { it.trashedAt != null }.map { LibraryItem(src.id, it, 0) }
        _state.update { s ->
            s.copy(
                items = s.items.filter { it.sourceId != src.id } + live,
                trashed = s.trashed.filter { it.sourceId != src.id } + bin,
                attachedId = store.attached()?.entryId,
            )
        }
    }

    // ── create · import ────────────────────────────────────────────────

    /** A new, empty DB in [sourceId] — an entry with no file; the engine makes it on its first save. */
    suspend fun create(sourceId: String, title: String): Pair<LibraryItem?, String?> {
        val src = sourceOf(sourceId) ?: return null to noFolder()
        if (src.access.value != SourceAccess.GRANTED) return null to lostFolder()
        val clean = title.trim().ifEmpty { tr("새 DB", "New DB", "新数据库") }
        return try {
            val made = LibraryEntries.newEntry(clean, "", LibraryCover.ECLIPSE)
            val file = when (src.kind) {
                SourceKind.APP -> AppDirSource.fileNameOf(made.id)
                SourceKind.SAF -> LibraryFileNames.fileNameFor(clean, takenNames(src, null))
            }
            val entry = made.copy(file = file)
            edit(src) { it + entry }
            item(entry.id) to null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null to messageOf(t)
        }
    }

    /**
     * Copies a picked `.db` into [sourceId] (cover «자동»). The bytes go through
     * the job process when the provider says the size; the copy is checked by
     * size and tail before the entry is written.
     */
    suspend fun importFile(sourceId: String, uri: Uri): String? {
        val src = sourceOf(sourceId) ?: return noFolder()
        if (src.access.value != SourceAccess.GRANTED) return lostFolder()
        val cr = context.contentResolver
        return try {
            val (display, size) = withContext(Dispatchers.IO) { describe(uri) }
            val title = display.replace(DB_SUFFIX, "").ifEmpty { display }
            val made = LibraryEntries.newEntry(title, "", LibraryCover.AUTO)
            val file = when (src.kind) {
                SourceKind.APP -> AppDirSource.fileNameOf(made.id)
                SourceKind.SAF -> LibraryFileNames.fileNameFor(title, takenNames(src, null))
            }
            val placed = src.writeAtomic(file) { staged ->
                if (size > 0) {
                    val tail = copier.copy(
                        size,
                        CopyFrom(
                            { cr.openInputStream(uri) ?: throw FileNotFoundException(display) },
                            { cr.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(display) },
                        ),
                        CopyTo({ staged.openOutput() }, { staged.openFd("wt") }),
                    )
                    val back = withContext(Dispatchers.IO) { staged.openInput().use { Fingerprint.read(it, size, 0) } }
                    if (back.tail != tail || back.size != size) throw IOException(tr("가져온 사본이 원본과 다릅니다", "The imported copy does not match", "导入的副本与原件不一致"))
                } else {
                    withContext(Dispatchers.IO) {
                        val input = cr.openInputStream(uri) ?: throw FileNotFoundException(display)
                        input.use { i -> staged.openOutput().use { o -> i.copyTo(o, 256 * 1024) } }
                    }
                }
            }
            val fp = src.fingerprint(file)?.copy(mtime = placed.mtime)
            val entry = made.copy(file = file, fingerprint = fp, updatedAt = placed.mtime)
            edit(src) { it + entry }
            pump()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "import failed", t)
            messageOf(t)
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.db"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    // ── rename · cover ─────────────────────────────────────────────────

    /**
     * New title; with [alsoFile] a folder entry's file follows (companions too,
     * `fileNameFor` past the names taken). A provider that refuses leaves the
     * title changed and the file name pending (`fileRenamePending`, tried again
     * at the next load). An attached folder entry renames freely: the engine
     * holds the staging copy, and the write-back takes the new name.
     */
    suspend fun rename(id: String, title: String, alsoFile: Boolean): String? {
        val clean = title.trim()
        val it0 = item(id) ?: return null
        if (clean.isEmpty() || id in _state.value.working) return null
        val src = sourceOf(it0.sourceId) ?: return noFolder()
        val now = System.currentTimeMillis()
        edit(src) { list -> list.map { if (it.id == id) it.copy(title = clean, updatedAt = now) else it } }
        if (!alsoFile || src.kind == SourceKind.APP) return null
        return renameFile(src, id, clean, quiet = false)
    }

    /** The file half of [rename] (and of the pending retry). */
    private suspend fun renameFile(src: LibrarySource, id: String, title: String, quiet: Boolean): String? {
        val e = entriesOf(src.id).firstOrNull { it.id == id } ?: return null
        val want = LibraryFileNames.fileNameFor(title, takenNames(src, e))
        if (want == e.file) {
            if (e.fileRenamePending != null) edit(src) { l -> l.map { if (it.id == id) it.copy(fileRenamePending = null) else it } }
            return null
        }
        return try {
            val got = if (src.stat(e.file) != null) src.rename(e.file, want) else want
            edit(src) { l -> l.map { if (it.id == id) it.copy(file = got, fileRenamePending = null) else it } }
            // A staging copy (attached or kept) goes home under the new name.
            store.markForEntry(id)?.let { m -> store.updateMark(m.key) { it.copy(name = got) } }
            null
        } catch (e2: CancellationException) {
            throw e2
        } catch (t: Throwable) {
            Log.w(TAG, "file rename of $id failed", t)
            if (!quiet) edit(src) { l -> l.map { if (it.id == id) it.copy(fileRenamePending = want) else it } }
            if (quiet) null else tr("파일 이름은 그대로 두었습니다 — 다음에 다시 시도합니다", "The file name stayed — it will be tried again later", "文件名保持不变，稍后重试")
        }
    }

    private suspend fun retryRenames(src: LibrarySource) {
        val attached = store.attached()?.entryId
        for (e in entriesOf(src.id)) {
            if (!e.live || e.fileRenamePending == null || e.id == attached) continue
            renameFile(src, e.id, e.title, quiet = true)
        }
    }

    /** Names a new file must not take: the folder's files and the live entries' — [self]'s own file and companions aside. */
    private suspend fun takenNames(src: LibrarySource, self: LibraryEntry?): List<String> {
        val files = src.list().map { it.name }.filter { n ->
            self == null || (LibraryFileNames.key(n) != LibraryFileNames.key(self.file) && LibraryFileNames.companionSuffix(self.file, n) == null)
        }
        return files + entriesOf(src.id).filter { it.live && it.id != self?.id }.map { it.file }
    }

    suspend fun setCover(id: String, cover: LibraryCover) {
        val it0 = item(id) ?: return
        val src = sourceOf(it0.sourceId) ?: return
        runCatching {
            edit(src) { l -> l.map { if (it.id == id) it.copy(cover = it.cover.copy(mode = cover.mode, opening = cover.opening, seed = cover.seed)) else it } }
        }.onFailure { Log.w(TAG, "cover of $id failed", it) }
    }

    // ── trash ──────────────────────────────────────────────────────────

    /** Into the source's trash — no question, a 10 s «되돌리기» (design §6.2). The attached entry stays. */
    suspend fun trash(ids: List<String>): String? {
        val attached = store.attached()?.entryId
        val done = ArrayList<String>()
        var title = ""
        var refused = false
        for (id in ids) {
            val it0 = item(id) ?: continue
            if (id == attached || id in _state.value.working) {
                refused = true
                continue
            }
            val src = sourceOf(it0.sourceId) ?: continue
            if (_state.value.scan?.entryId == id) stopScan()
            try {
                dropStagingOf(id)
                val token = if (src.stat(it0.entry.file) != null) src.trash(it0.entry.file) else null
                val now = System.currentTimeMillis()
                edit(src) { l -> l.map { if (it.id == id) it.copy(trashedAt = now, trashFile = token) else it } }
                done += id
                title = it0.entry.title
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "trash of $id failed", t)
                return messageOf(t)
            }
        }
        if (done.isNotEmpty()) {
            val label = if (done.size == 1) title else tr("${done.size}개", "${done.size} DBs", "${done.size} 个")
            _state.update { it.copy(undo = LibraryUndo(done, label, System.currentTimeMillis())) }
        }
        return when {
            refused && ids.size == 1 -> tr("붙어 있는 DB 는 지울 수 없습니다. 다른 DB 를 먼저 여세요", "The attached DB cannot be deleted — open another one first", "已连接的数据库不能删除，请先打开其他数据库")
            refused -> tr("붙어 있거나 작업 중인 DB 는 남겼습니다", "The attached or busy DBs were kept", "已保留已连接或正在处理的数据库")
            else -> null
        }
    }

    /** A folder entry's staging copy goes with it — after it has gone home if it held changes. */
    private suspend fun dropStagingOf(id: String) {
        val m = store.markForEntry(id) ?: return
        if (m.dirty) attach.writeBackAll()
        val after = store.mark(m.key) ?: return
        if (!after.dirty) store.dropStaging(after.key)
    }

    fun clearUndo() = _state.update { it.copy(undo = null) }

    /** Back from the trash; a folder name taken meanwhile gets ` (2)`. A new DB trashed with no file comes back as an entry. */
    suspend fun restore(ids: List<String>): String? {
        for (id in ids) {
            val it0 = _state.value.trashed.firstOrNull { it.id == id } ?: continue
            val src = sourceOf(it0.sourceId) ?: continue
            try {
                val e = it0.entry
                var file = e.file
                val token = e.trashFile
                if (token != null) {
                    if (src.kind == SourceKind.SAF && src.stat(file) != null) file = LibraryFileNames.fileNameFor(e.title, takenNames(src, null))
                    file = src.restore(token, file)
                }
                edit(src) { l -> l.map { if (it.id == id) it.copy(trashedAt = null, trashFile = null, file = file) else it } }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "restore of $id failed", t)
                return messageOf(t)
            }
        }
        _state.update { s -> s.undo?.let { u -> s.copy(undo = u.copy(ids = u.ids - ids.toSet()).takeIf { it.ids.isNotEmpty() }) } ?: s }
        pump()
        return null
    }

    /** Deletes from the trash for good, file and entry. */
    suspend fun purge(id: String): String? {
        val it0 = _state.value.trashed.firstOrNull { it.id == id } ?: return null
        val src = sourceOf(it0.sourceId) ?: return noFolder()
        return try {
            it0.entry.trashFile?.let { src.purge(it) }
            edit(src) { l -> l.filter { it.id != id } }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            messageOf(t)
        }
    }

    // ── folders ────────────────────────────────────────────────────────

    /** «+ 폴더» — from `OpenDocumentTree`. Returns the new source's id, or the reason it failed. */
    suspend fun addFolder(treeUri: Uri): Pair<String?, String?> = try {
        val src = sources.addFolder(treeUri)
        load()
        src.id to null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "add folder failed", t)
        null to tr("폴더를 쓸 권한을 받지 못했습니다", "Could not get access to the folder", "未能获得文件夹权限")
    }

    /** The picker's answer to «폴더 다시 허용» — the same folder takes its grant again. */
    suspend fun regrant(treeUri: Uri): String? = addFolder(treeUri).second

    fun reopenUri(sourceId: String): Uri? = sources.reopenIntentUri(sourceId)

    /** «라이브러리에서 빼기» — the folder's files and sidecar stay as they are. */
    suspend fun removeFolder(id: String): String? {
        val a = store.attached()
        if (a?.sourceId == id) return tr("붙어 있는 DB 의 폴더는 뺄 수 없습니다. 다른 DB 를 먼저 여세요", "The attached DB's folder cannot be removed — open another DB first", "已连接数据库所在的文件夹不能移除，请先打开其他数据库")
        for (m in store.marks().filter { it.sourceId == id && !it.dirty }) store.dropStaging(m.key)
        sources.removeFolder(id)
        load()
        return null
    }

    // ── open ───────────────────────────────────────────────────────────

    /**
     * Attaches the entry and says what to put on the board (design §5 · §6.3).
     * Only for the engine on this phone (§9-5): a server's database is a file
     * on the server. With the engine off the attachment is recorded and the
     * next start opens it. «DB 사용» comes on when it was off.
     */
    suspend fun open(id: String, at: OpenAt, boardSize: Int, mode: StartMode = StartMode.BRANCH): OpenOutcome {
        val it0 = item(id) ?: return OpenOutcome.Refused(tr("라이브러리에 없는 DB 입니다", "That DB is not in the library", "资料库中没有该数据库"))
        if (!endpoints.localMode.first()) {
            return OpenOutcome.Refused(tr("서버 엔진에서는 열 수 없습니다 — 설정 ▸ 엔진에서 기기 내 엔진으로 바꾼 뒤 여세요", "Only the engine on this phone opens library DBs — switch in Settings ▸ Engine", "服务器引擎无法打开，请在 设置 ▸ 引擎 中切换到本机引擎"))
        }
        val src = sourceOf(it0.sourceId) ?: return OpenOutcome.Refused(noFolder())
        if (src.access.value != SourceAccess.GRANTED) return OpenOutcome.Refused(lostFolder())
        when (val r = attach.switchTo(src, it0.entry)) {
            is AttachOutcome.Failed -> return OpenOutcome.Refused(r.reason)
            else -> Unit
        }
        if (!settings.settings.value.useDatabase) runCatching { settings.set("useDatabase", "1") }
        val now = System.currentTimeMillis()
        runCatching { edit(src) { l -> l.map { if (it.id == id) it.copy(lastOpenedAt = now) else it } } }
        val moves = when (at) {
            OpenAt.Start -> LibraryPositions.startMovesOf(it0.entry.stats, boardSize, mode)
            OpenAt.Keep -> null
            is OpenAt.Line -> at.moves
        }
        return OpenOutcome.Opened(it0.entry.title, moves, at == OpenAt.Start)
    }

    // ── copy · move · export ───────────────────────────────────────────

    /**
     * «다른 곳으로 복사 · 옮기기» (design §9-8) — app storage is invisible, so
     * this is how a DB gets out to a folder. The attached entry copies (its
     * last save, journal and all) but does not move.
     */
    suspend fun transfer(id: String, toSourceId: String, move: Boolean): String? {
        val it0 = item(id) ?: return null
        if (move && id == store.attached()?.entryId) {
            return tr("붙어 있는 DB 는 옮길 수 없습니다. 복사하세요", "The attached DB cannot be moved — copy it instead", "已连接的数据库不能移动，请复制")
        }
        if (it0.size == 0L) return tr("아직 파일이 없는 DB 입니다", "This DB has no file yet", "该数据库还没有文件")
        val from = sourceOf(it0.sourceId) ?: return noFolder()
        val to = sourceOf(toSourceId) ?: return noFolder()
        if (to.access.value != SourceAccess.GRANTED || from.access.value != SourceAccess.GRANTED) return lostFolder()
        _state.update { it.copy(working = it.working + id) }
        return try {
            if (move) dropStagingOf(id)
            val r = LibraryTransfer.transfer(it0.entry, from, to, entriesOf(to.id), move)
            edit(to) { it + r.entry }
            r.sourceEntry?.let { se -> edit(from) { l -> l.map { if (it.id == id) se else it } } }
            pump()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "transfer of $id failed", t)
            messageOf(t)
        } finally {
            _state.update { it.copy(working = it.working - id) }
        }
    }

    /**
     * The entry's file to a document the user created (`CreateDocument`). A
     * journal with saves not yet folded in would be left behind, so that is
     * refused (web `exportFile`) — attaching folds it.
     */
    suspend fun export(id: String, target: Uri): String? {
        val it0 = item(id) ?: return null
        val src = sourceOf(it0.sourceId) ?: return noFolder()
        return try {
            val journal = src.stat(it0.entry.file + ".journal")
            if (journal != null && journal.size > JOURNAL_HEADER) {
                return tr("저널에 접히지 않은 변경이 있습니다. DB 를 붙인 뒤 내보내세요", "The journal holds changes not folded in — attach the DB, then export", "日志中有尚未合并的更改，请连接数据库后再导出")
            }
            withContext(Dispatchers.IO) {
                val input = src.openRead(it0.entry.file) ?: throw FileNotFoundException(it0.entry.file)
                input.use { i ->
                    val out = context.contentResolver.openOutputStream(target, "wt") ?: throw FileNotFoundException(target.toString())
                    out.use { o -> i.copyTo(o, 256 * 1024) }
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            messageOf(t)
        }
    }

    // ── stats ──────────────────────────────────────────────────────────

    /** Count the next entry without current stats, one at a time, until none is left. */
    private fun pump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            try {
                while (true) {
                    val next = nextToScan() ?: break
                    scanOne(next)
                }
            } finally {
                _state.update { it.copy(scan = null) }
            }
        }
    }

    private fun nextToScan(): LibraryItem? {
        val s = _state.value
        val granted = s.sources.filter { it.access == SourceAccess.GRANTED }.map { it.id }.toSet()
        return s.items.firstOrNull {
            it.size > 0 && it.sourceId in granted && s.notes[it.id] == null &&
                (it.entry.stats?.current != true || it.id in forced) && (attempts[it.id] ?: 0) < MAX_ATTEMPTS
        }
    }

    private suspend fun scanOne(item: LibraryItem) {
        attempts[item.id] = (attempts[item.id] ?: 0) + 1
        forced.remove(item.id)
        val src = sources.sourceOf(item.sourceId) ?: return note(item.id, ScanNote.ERROR)
        val file = item.entry.file
        val fp = runCatching { src.fingerprint(file) }.getOrNull()
        if (fp == null || fp.size == 0L) return note(item.id, ScanNote.ERROR)
        _state.update { it.copy(scan = LibraryScan(item.id)) }
        val out = try {
            jobs.scan(
                fp.size,
                open = { runBlocking { src.openFd(file, "r") } ?: throw FileNotFoundException(file) },
                expect = ExpectedFile(fp.size, fp.tail),
                priority = JobPriority.BACKGROUND,
                onStats = { early -> _state.update { s -> s.copy(scan = s.scan?.takeIf { it.entryId == item.id }?.copy(early = early)) } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "scan of ${item.id} failed", t)
            JobOutcome.Failed(t.message ?: t::class.java.simpleName)
        }
        _state.update { it.copy(scan = null) }
        when (out) {
            is JobOutcome.Done -> when (val r = out.result) {
                is JobResult.Scanned -> saveStats(src, item.id, fp, r)
                // The file moved since the list was read: settle the source, count again.
                is JobResult.Changed -> runCatching { load() }
                else -> note(item.id, ScanNote.ERROR)
            }
            JobOutcome.Cancelled -> if (_state.value.notes[item.id] == null) note(item.id, ScanNote.STOPPED)
            is JobOutcome.Failed, JobOutcome.Died -> note(item.id, ScanNote.ERROR)
        }
    }

    private suspend fun saveStats(src: LibrarySource, id: String, fp: Fingerprint, r: JobResult.Scanned) {
        val written = ManifestLocks.with(src.id) {
            val m = src.readManifest()
            val e = m.entries.firstOrNull { it.id == id } ?: return@with null
            val known = e.fingerprint
            if (known != null && !known.sameFile(fp)) return@with null   // saved under the count: next round
            val next = m.entries.map { if (it.id == id) it.withStats(r.stats).copy(fingerprint = known ?: fp) else it }
            src.writeManifest(m.copy(entries = next))
            next
        }
        if (written == null) {
            runCatching { load() }
            return
        }
        publish(src, written)
        when {
            r.indexSkipped != null -> note(id, ScanNote.MEMORY)
            r.openingsUnordered -> note(id, ScanNote.UNORDERED)
        }
    }

    private fun note(id: String, n: ScanNote) = _state.update { it.copy(notes = it.notes + (id to n)) }

    /** «그만 세기» — this entry waits for «다시 세기»; the queue goes on. */
    fun stopScan() {
        val id = _state.value.scan?.entryId ?: return
        note(id, ScanNote.STOPPED)
        if (jobs.active.value?.job is LibraryJob.Scan) jobs.cancel()
    }

    /** «다시 세기». */
    fun rescan(id: String) {
        attempts.remove(id)
        // Openings and roots skipped (memory, or the system stopping the count):
        // the stats are current, so only being asked brings them round again.
        val stats = item(id)?.entry?.stats
        if (stats != null && stats.roots == null) forced += id
        _state.update { it.copy(notes = it.notes - id) }
        pump()
    }

    /**
     * A save ended. When it rewrote the attached app-storage file (a fold, not
     * just the journal), the entry's identity moves and a new tail drops its
     * stats (design §5 `recordSaved`).
     */
    private suspend fun onSaved() {
        val a = store.attached() ?: return
        if (a.sourceId != AppDirSource.ID) return
        val app = sources.app
        val e = entriesOf(app.id).firstOrNull { it.id == a.entryId } ?: return
        val fp = app.fingerprint(e.file) ?: return
        val old = e.fingerprint
        if (old != null && old.sameFile(fp)) {
            publish(app, entriesOf(app.id))   // the journal grew: the size on the card
            return
        }
        edit(app) { l ->
            l.map { x ->
                if (x.id != e.id) x
                else x.copy(fingerprint = fp, updatedAt = fp.mtime).let { if (old?.tail != fp.tail) it.withStats(null) else it }
            }
        }
        attempts.remove(e.id)
        pump()
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun noFolder() = tr("라이브러리에 없는 폴더입니다", "That folder is not in the library", "资料库中没有该文件夹")
    private fun lostFolder() = tr("이 폴더를 다시 쓰려면 허용이 필요합니다", "This folder needs access again", "需要重新授权该文件夹")

    private fun messageOf(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    companion object {
        private const val TAG = "LibraryRepository"
        private const val MAX_ATTEMPTS = 3

        /** The engine's DB6 journal header: a journal no longer than this holds no saves. */
        const val JOURNAL_HEADER = 32L

        private val DB_SUFFIX = Regex("\\.db$", RegexOption.IGNORE_CASE)

        /** Main file + journal; 0 when the main file is not there (a new DB). */
        fun sizeOf(file: String, files: List<FileStat>): Long {
            val key = LibraryFileNames.key(file)
            val main = files.firstOrNull { LibraryFileNames.key(it.name) == key } ?: return 0
            val jKey = LibraryFileNames.key("$file.journal")
            return main.size + (files.firstOrNull { LibraryFileNames.key(it.name) == jKey }?.size ?: 0)
        }
    }
}
