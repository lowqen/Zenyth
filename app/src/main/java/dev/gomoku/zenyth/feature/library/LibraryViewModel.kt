package dev.gomoku.zenyth.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gomoku.zenyth.core.common.BoardHandoff
import dev.gomoku.zenyth.core.common.IncomingLinks
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.library.LibraryOps
import dev.gomoku.zenyth.data.library.LibraryRepository
import dev.gomoku.zenyth.data.library.MergePrepared
import dev.gomoku.zenyth.data.library.OpResult
import dev.gomoku.zenyth.data.library.SplitPrepared
import dev.gomoku.zenyth.data.library.SplitResultSpec
import dev.gomoku.zenyth.data.library.LibraryUndo
import dev.gomoku.zenyth.data.library.OpenAt
import dev.gomoku.zenyth.data.library.OpenOutcome
import dev.gomoku.zenyth.data.library.job.LibraryJob
import dev.gomoku.zenyth.data.library.job.LibraryJobClient
import dev.gomoku.zenyth.data.prefs.UiPrefs
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import dev.gomoku.zenyth.domain.library.LibraryPositions
import dev.gomoku.zenyth.domain.library.StartMode
import dev.gomoku.zenyth.domain.library.Covers
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import dev.gomoku.zenyth.domain.library.yxdb.SplitPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import dev.gomoku.zenyth.domain.repository.SettingsRepository
import dev.gomoku.zenyth.domain.repository.GameRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the library sends the shell. */
sealed interface LibraryEvent {
    data object ToBoard : LibraryEvent
    data object ToEditor : LibraryEvent
    /** The detail's entry went (trashed): back to the grid. */
    data object Back : LibraryEvent
}

/**
 * The library tab's state (design §6, step L5-6) — one view model for the tab's
 * graph, so the grid and a detail page read the same selection and notices.
 * The work is the repository's; this keeps the view choices (source, sort,
 * grid or list) and turns outcomes into the shell's moves and one-line notices.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val jobs: LibraryJobClient,
    private val game: GameRepository,
    private val links: IncomingLinks,
    private val settings: SettingsRepository,
    prefs: UiPrefs,
) : ViewModel() {

    /** «DB 열 때 시작 국면» (L5-6 P3). */
    private val openAtPref = prefs.libraryOpenAt

    private val sourcePref = prefs.string("library.source", "app")
    private val sortPref = prefs.string("library.sort", LibrarySort.RECENT.name)
    private val listPref = prefs.bool("library.list", false)
    private val selected = MutableStateFlow<String?>(null)

    /** Cards picked in the pick mode (long press, design §6.2), in the order picked. */
    private val picks = MutableStateFlow<List<String>>(emptyList())

    /** The job line (design §6.2) — also the merge · split sheets' progress. */
    val jobLine: Flow<JobLine?> = jobs.active.map { a ->
        val job = a?.job ?: return@map null
        if (job is LibraryJob.Scan) return@map null
        JobLine(
            label = when (job) {
                is LibraryJob.Copy -> tr("복사하는 중", "Copying", "复制中")
                is LibraryJob.MergeCount, is LibraryJob.SplitCount -> tr("세는 중(미리 보기)", "Counting (preview)", "统计中（预览）")
                is LibraryJob.MergeWrite -> tr("합치는 중", "Merging", "合并中")
                is LibraryJob.SplitWrite -> tr("나누는 중", "Splitting", "拆分中")
                else -> tr("작업 중", "Working", "处理中")
            },
            fraction = (a.progress?.fraction ?: 0.0).toFloat(),
            cancelling = a.cancelling,
        )
    }.distinctUntilChanged()

    val uiState: StateFlow<LibraryUiState> = combine(
        repo.state, sourcePref.state, sortPref.state, listPref.state, combine(selected, jobLine, picks) { s, j, p -> Triple(s, j, p) },
    ) { s, source, sort, list, (sel, job, picked) ->
        LibraryUiMapper.home(s, source, runCatching { LibrarySort.valueOf(sort) }.getOrDefault(LibrarySort.RECENT), list, sel, job, picked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** The page of entry [id] (null when it went away). */
    fun detail(id: String): Flow<LibraryDetailUi?> = combine(repo.state, openAtPref.state) { s, openAt ->
        val item = s.items.firstOrNull { it.id == id } ?: return@combine null
        val others = s.items.filter { it.sourceId == item.sourceId && it.id != id }.map { it.entry.file }
        LibraryUiMapper.detail(item, s, game.position.value.size, others, StartMode.of(openAt))
    }.distinctUntilChanged()

    private val _ruleClash = MutableStateFlow<RuleClash?>(null)

    /** «열기» stopped by a DB of another rule — the dialog's question (L5-6 P3). */
    val ruleClash: StateFlow<RuleClash?> = _ruleClash.asStateFlow()

    /**
     * «열기» with the rule check first: a DB whose positions are mostly under
     * another rule or board size than the board's opens after a question —
     * the engine would otherwise look its positions up under the wrong rule
     * and find nothing. The editor ([OpenAt.Keep]) does not ask.
     */
    fun requestOpen(id: String, at: OpenAt) {
        if (at != OpenAt.Keep) {
            val db = LibraryPositions.mainRuleOf(repo.item(id)?.entry?.stats)
            val rule = settings.settings.value.engineRule
            val size = game.position.value.size
            if (db != null && (db.rule != rule || db.size != size)) {
                _ruleClash.value = RuleClash(id, at, db, rule, size)
                return
            }
        }
        open(id, at)
    }

    fun dismissRuleClash() {
        _ruleClash.value = null
    }

    /** [그대로 열기]. */
    fun openDespiteRule() {
        val c = _ruleClash.value ?: return
        _ruleClash.value = null
        open(c.id, c.at)
    }

    /** [규칙 바꿔 열기] — the board takes the DB's rule (same size only), then opens. */
    fun openWithDbRule() {
        val c = _ruleClash.value ?: return
        _ruleClash.value = null
        if (!c.canSwitch) return open(c.id, c.at)
        viewModelScope.launch {
            runCatching { settings.set("rule", c.db.rule.toString()) }
            open(c.id, c.at)
        }
    }

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)

    /** One-line notices for the snackbar — this screen's and the repository's (write-back conflicts). */
    val notices: Flow<String> = merge(_notices.receiveAsFlow(), repo.notices)

    /** The last delete, for the 10 s «되돌리기». */
    val undo: Flow<LibraryUndo?> = repo.state.map { it.undo }.distinctUntilChanged()

    private val _busy = MutableStateFlow(false)

    /** An open is on its way — the buttons that open wait. */
    val busy: StateFlow<Boolean> = _busy

    init {
        refresh()
    }

    /** Sources, sidecars and sizes again (the tab shown, back from a picker). */
    fun refresh() {
        viewModelScope.launch { repo.load() }
    }

    private fun notify(msg: String?) {
        if (msg != null) _notices.trySend(msg)
    }

    // ── view ───────────────────────────────────────────────────────────

    fun selectSource(id: String) {
        picks.value = emptyList()
        sourcePref.set(id)
    }
    fun setSort(s: LibrarySort) = sortPref.set(s.name)
    fun setList(list: Boolean) = listPref.set(list)
    fun select(id: String?) {
        selected.value = id
    }

    // ── sources ────────────────────────────────────────────────────────

    fun onFolderPicked(uri: Uri) = viewModelScope.launch {
        val (id, err) = repo.addFolder(uri)
        if (id != null) sourcePref.set(id)
        notify(err)
    }

    fun onFolderRegranted(uri: Uri) = viewModelScope.launch { notify(repo.regrant(uri)) }

    fun reopenUri(sourceId: String): Uri? = repo.reopenUri(sourceId)

    fun removeFolder(id: String) = viewModelScope.launch {
        val err = repo.removeFolder(id)
        if (err == null && sourcePref.value == id) sourcePref.set("app")
        notify(err)
    }

    // ── entries ────────────────────────────────────────────────────────

    fun create(onMade: (String) -> Unit = {}) = viewModelScope.launch {
        val (item, err) = repo.create(uiState.value.sourceId, tr("새 DB", "New DB", "新数据库"))
        notify(err)
        item?.let { onMade(it.id) }
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        notify(repo.importFile(uiState.value.sourceId, uri) ?: tr("가져왔습니다", "Imported", "已导入"))
    }

    fun rename(id: String, title: String, alsoFile: Boolean) = viewModelScope.launch { notify(repo.rename(id, title, alsoFile)) }

    fun setCover(id: String, cover: LibraryCover) = viewModelScope.launch { repo.setCover(id, cover) }

    fun trash(id: String) = viewModelScope.launch {
        val err = repo.trash(listOf(id))
        notify(err)
        if (err == null) {
            if (selected.value == id) selected.value = null
            _events.trySend(LibraryEvent.Back)
        }
    }

    fun restore(ids: List<String>) = viewModelScope.launch { notify(repo.restore(ids)) }

    fun dismissUndo() = repo.clearUndo()

    fun purge(id: String) = viewModelScope.launch { notify(repo.purge(id)) }

    fun rescan(id: String) = repo.rescan(id)

    fun stopScan() = repo.stopScan()

    fun cancelJob() = jobs.cancel()

    fun transfer(id: String, to: String, move: Boolean) = viewModelScope.launch {
        val err = repo.transfer(id, to, move)
        notify(
            err ?: if (move) tr("옮겼습니다", "Moved", "已移动") else tr("복사했습니다", "Copied", "已复制"),
        )
        if (err == null && move) {
            if (selected.value == id) selected.value = null
            _events.trySend(LibraryEvent.Back)
        }
    }

    fun export(id: String, uri: Uri) = viewModelScope.launch {
        notify(repo.export(id, uri) ?: tr("내보냈습니다", "Exported", "已导出"))
    }

    /** The name `CreateDocument` suggests for an export. */
    fun exportName(title: String): String = LibraryFileNames.fileNameFor(title)

    // ── pick mode (L5-7) ───────────────────────────────────────────────

    /** Long press: the pick mode starts with this card. */
    fun startPick(id: String) {
        picks.value = listOf(id)
    }

    /** A tap while picking. The last one unpicked ends the mode. */
    fun togglePick(id: String) = picks.update { if (id in it) it - id else it + id }

    fun clearPicks() {
        picks.value = emptyList()
    }

    /** [지우기] — every picked one to the trash, one «되돌리기» for all. */
    fun trashPicked() {
        val ids = picks.value
        if (ids.isEmpty()) return
        picks.value = emptyList()
        viewModelScope.launch { notify(repo.trash(ids)) }
    }

    // ── merge sheet (L5-7) ─────────────────────────────────────────────

    private val _merge = MutableStateFlow<MergeUi?>(null)
    val mergeSheet: StateFlow<MergeUi?> = _merge.asStateFlow()
    private var mergePrep: MergePrepared? = null
    private var mergeJob: Job? = null

    /** [합치기] in the pick bar: the picked DBs in the order picked (the first = the base). */
    fun openMerge() {
        val s = repo.state.value
        val rows = picks.value.mapNotNull { id -> s.items.firstOrNull { it.id == id } }.map { item ->
            val stats = item.entry.stats
            MergeRow(
                id = item.id,
                title = item.entry.title,
                cover = Covers.keyOf(item.entry.cover, stats),
                seed = item.entry.cover.seed,
                subtitle = LibraryFormat.subtitle(stats, item.size),
                rule = stats?.rules?.maxByOrNull { it.value }?.key,
            )
        }
        if (rows.size < 2) return
        mergePrep = null
        _merge.value = MergeUi(rows, hasAttached = rows.any { it.id == s.attachedId })
    }

    /** ▲ (−1) · ▼ (+1) — the preview counts again. */
    fun moveMergeRow(index: Int, by: Int) = _merge.update { m ->
        val j = index + by
        if (m == null || m.busy || j !in m.rows.indices) return@update m
        val rows = m.rows.toMutableList()
        rows[index] = rows[j].also { rows[j] = rows[index] }
        mergePrep = null
        m.copy(rows = rows, counts = null)
    }

    fun setMergePolicy(p: MergePolicy) = changeMergeCount { it.copy(policy = p) }
    fun setMergeJoin(on: Boolean) = changeMergeCount { it.copy(joinComments = on) }

    /** A choice the count depends on: the preview is dropped. */
    private fun changeMergeCount(change: (MergeUi) -> MergeUi) {
        val m = _merge.value ?: return
        if (m.busy) return
        mergePrep = null
        _merge.value = change(m).copy(counts = null)
    }
    fun setMergeTitle(t: String) = _merge.update { it?.copy(title = t) }
    fun setMergeTrash(on: Boolean) = _merge.update { it?.copy(trashOriginals = on) }

    /** [다음] · [미리 보기]. Step ③ counts first. */
    fun mergeNext() {
        val m = _merge.value ?: return
        if (m.busy) return
        when (m.step) {
            1 -> _merge.value = m.copy(step = 2, error = null)
            2 -> previewMerge(m)
        }
    }

    /** [이전] — a count on its way stops. */
    fun mergeBack() {
        val m = _merge.value ?: return
        if (m.running) return
        if (m.counting) mergeJob?.cancel()
        _merge.value = m.copy(step = (m.step - 1).coerceAtLeast(1), counting = false, error = null)
    }

    private fun previewMerge(m: MergeUi) {
        _merge.value = m.copy(
            step = 3, counting = true, counts = null, error = null,
            title = m.title.ifBlank { LibraryOps.defaultMergeTitle(m.rows.map { it.title }) },
        )
        mergeJob = viewModelScope.launch {
            val r = repo.mergePreview(m.rows.map { it.id }, m.policy, m.joinComments)
            _merge.update { cur ->
                if (cur == null || cur.step != 3) return@update cur
                when (r) {
                    is OpResult.Done -> {
                        mergePrep = r.value
                        val x = r.value.summary
                        cur.copy(counting = false, counts = MergeCounts(x.records, x.positions, x.overlaps, x.differing, x.inputPositions))
                    }
                    OpResult.Cancelled -> cur.copy(counting = false, step = 2)
                    is OpResult.Failed -> cur.copy(counting = false, error = r.message)
                }
            }
        }
    }

    /** [합치기] — written, read back, then a new entry. The sheet closes on success. */
    fun commitMerge(onDone: (String) -> Unit = {}) {
        val m = _merge.value ?: return
        val prep = mergePrep ?: return
        if (m.busy) return
        _merge.value = m.copy(running = true, error = null)
        mergeJob = viewModelScope.launch {
            when (val r = repo.merge(prep, m.title, m.trashOriginals)) {
                is OpResult.Done -> {
                    _merge.value = null
                    picks.value = emptyList()
                    notify(tr("합쳤습니다 — «${m.title.trim()}»", "Merged into «${m.title.trim()}»", "已合并为«${m.title.trim()}»"))
                    onDone(r.value)
                }
                OpResult.Cancelled -> {
                    _merge.update { it?.copy(running = false) }
                    notify(tr("합치기를 취소했습니다", "Merge cancelled", "已取消合并"))
                }
                is OpResult.Failed -> _merge.update { it?.copy(running = false, error = r.message) }
            }
        }
    }

    /** Closing the sheet stops its job (web: closing while it works cancels). */
    fun closeMerge() {
        if (_merge.value?.busy == true) mergeJob?.cancel()
        _merge.value = null
        mergePrep = null
    }

    // ── split sheet (L5-7) ─────────────────────────────────────────────

    private val _split = MutableStateFlow<SplitUi?>(null)
    val splitSheet: StateFlow<SplitUi?> = _split.asStateFlow()
    private var splitPrep: SplitPrepared? = null
    private var splitJob: Job? = null

    /** [나누기] (the detail's «관리», or one card picked): counts every criterion once. */
    fun openSplit(id: String) {
        val s = repo.state.value
        val item = s.items.firstOrNull { it.id == id } ?: return
        if (item.size == 0L) return notify(tr("아직 파일이 없는 DB 입니다", "This DB has no file yet", "该数据库还没有文件"))
        picks.value = emptyList()
        splitPrep = null
        val board = game.position.value
        _split.value = SplitUi(item.id, item.entry.title, item.entry.cover, attached = item.id == s.attachedId, baseStones = board.moves.size)
        splitJob = viewModelScope.launch {
            val r = repo.splitPreview(id, board.moves, board.size)
            _split.update { cur ->
                if (cur == null || cur.id != id) return@update cur
                when (r) {
                    is OpResult.Done -> {
                        splitPrep = r.value
                        SplitPlanner.defaults(cur.copy(counting = false, counts = r.value.counts, baseStones = r.value.base?.let { it.black.size + it.white.size } ?: 0), r.value.counts)
                    }
                    OpResult.Cancelled -> null
                    is OpResult.Failed -> cur.copy(counting = false, error = r.message)
                }
            }
        }
    }

    fun setSplitCriterion(c: SplitCriterion) = _split.update { it?.copy(criterion = c, names = emptyMap()) }
    fun toggleSplitOpening(i: Int) = _split.update { u -> u?.copy(picked = if (i in u.picked) u.picked - i else u.picked + i, names = emptyMap()) }
    fun setSplitGrouped(on: Boolean) = _split.update { it?.copy(grouped = on, names = emptyMap()) }
    fun toggleSplitRule(k: String) = _split.update { u -> u?.copy(rules = if (k in u.rules) u.rules - k else u.rules + k, names = emptyMap()) }
    fun setSplitMove(on: Boolean) = _split.update { u -> u?.copy(move = on && !u.attached) }
    fun setSplitName(index: Int, name: String) = _split.update { u -> u?.copy(names = u.names + (index to name)) }

    fun splitNext() = _split.update { u -> if (u == null || u.busy || u.step >= 3) u else u.copy(step = u.step + 1, error = null) }
    fun splitBack() = _split.update { u -> if (u == null || u.running) u else u.copy(step = (u.step - 1).coerceAtLeast(1), error = null, confirmMove = false) }
    fun dismissSplitConfirm() = _split.update { it?.copy(confirmMove = false) }

    /** [나누기] — a move asks first (the original becomes the rest). */
    fun commitSplit(confirmed: Boolean = false, onDone: (List<String>) -> Unit = {}) {
        val u = _split.value ?: return
        val prep = splitPrep ?: return
        if (u.busy) return
        if (u.move && !confirmed) {
            _split.value = u.copy(confirmMove = true)
            return
        }
        val planned = SplitPlanner.planned(u)
        if (planned.isEmpty()) return
        val plan = SplitPlan(
            criterion = u.criterion,
            outputs = planned.map { it.out.toOutput() },
            rest = if (u.move) SplitPlanner.rest(u, planned) else null,
            base = if (u.criterion == SplitCriterion.UNDER) prep.base else null,
        )
        _split.value = u.copy(running = true, confirmMove = false, error = null)
        splitJob = viewModelScope.launch {
            when (val r = repo.split(prep, plan, planned.map { SplitResultSpec(it.out, it.title, it.cover) }, u.move)) {
                is OpResult.Done -> {
                    _split.value = null
                    notify(tr("${r.value.size}개로 나눴습니다", "Split into ${r.value.size}", "已拆分为 ${r.value.size} 个"))
                    onDone(r.value)
                }
                OpResult.Cancelled -> {
                    _split.update { it?.copy(running = false) }
                    notify(tr("나누기를 취소했습니다", "Split cancelled", "已取消拆分"))
                }
                is OpResult.Failed -> _split.update { it?.copy(running = false, error = r.message) }
            }
        }
    }

    fun closeSplit() {
        if (_split.value?.busy == true) splitJob?.cancel()
        _split.value = null
        splitPrep = null
    }

    // ── open ───────────────────────────────────────────────────────────

    /**
     * Attaches and goes: the record's start ([OpenAt.Start]), an empty board,
     * a line (an opening's three stones, a root), or the editor ([OpenAt.Keep]).
     */
    fun open(id: String, at: OpenAt) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val r = repo.open(id, at, game.position.value.size, StartMode.of(openAtPref.value))) {
                    is OpenOutcome.Refused -> notify(r.message)
                    is OpenOutcome.Opened -> {
                        r.moves?.let { moves ->
                            val what = if (r.start && moves.isEmpty()) {
                                tr("라이브러리에서 «${r.title}» — 빈 판에서 시작", "From the library: «${r.title}», from an empty board", "从资料库: «${r.title}»，从空棋盘开始")
                            } else if (r.start) {
                                tr("라이브러리에서 «${r.title}»의 시작 국면(${moves.size}수)으로", "From the library: «${r.title}», its start (${moves.size} moves)", "从资料库: «${r.title}» 的起始局面 (${moves.size} 手)")
                            } else {
                                tr("라이브러리에서 ${moves.size}수 국면으로", "From the library: a ${moves.size}-move position", "从资料库: ${moves.size} 手局面")
                            }
                            links.offerPosition(BoardHandoff(moves, what))
                        }
                        _events.trySend(if (at == OpenAt.Keep) LibraryEvent.ToEditor else LibraryEvent.ToBoard)
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
