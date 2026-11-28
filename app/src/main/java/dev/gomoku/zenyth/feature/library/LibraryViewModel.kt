package dev.gomoku.zenyth.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gomoku.zenyth.core.common.BoardHandoff
import dev.gomoku.zenyth.core.common.IncomingLinks
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.library.LibraryRepository
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

    private val jobLine: Flow<JobLine?> = jobs.active.map { a ->
        val job = a?.job ?: return@map null
        if (job is LibraryJob.Scan) return@map null
        JobLine(
            label = when (job) {
                is LibraryJob.Copy -> tr("복사하는 중", "Copying", "复制中")
                else -> tr("작업 중", "Working", "处理中")
            },
            fraction = (a.progress?.fraction ?: 0.0).toFloat(),
            cancelling = a.cancelling,
        )
    }.distinctUntilChanged()

    val uiState: StateFlow<LibraryUiState> = combine(
        repo.state, sourcePref.state, sortPref.state, listPref.state, combine(selected, jobLine) { s, j -> s to j },
    ) { s, source, sort, list, (sel, job) ->
        LibraryUiMapper.home(s, source, runCatching { LibrarySort.valueOf(sort) }.getOrDefault(LibrarySort.RECENT), list, sel, job)
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

    fun selectSource(id: String) = sourcePref.set(id)
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
