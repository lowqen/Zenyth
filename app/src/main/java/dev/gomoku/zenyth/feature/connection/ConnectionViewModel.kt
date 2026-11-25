package dev.gomoku.zenyth.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.EngineCapabilities
import dev.gomoku.zenyth.core.model.EngineEndpoint
import dev.gomoku.zenyth.core.model.EngineTarget
import dev.gomoku.zenyth.core.model.FontSpec
import dev.gomoku.zenyth.core.model.LinkHealth
import dev.gomoku.zenyth.core.model.LocalEngineProfile
import dev.gomoku.zenyth.data.library.AttachedDbStore
import dev.gomoku.zenyth.data.prefs.EndpointStore
import dev.gomoku.zenyth.data.prefs.LocalEngineStore
import dev.gomoku.zenyth.domain.engine.EngineCommand
import dev.gomoku.zenyth.domain.repository.EngineRepository
import dev.gomoku.zenyth.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The board's «로그» pane (L5-5) — what the engine tab did, minus choosing the
 * engine (Settings ▸ 엔진 now). Made by the board screen itself, not by the pane,
 * so the console holds what the engine said since the board opened and not only
 * since the pane was first looked at.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: EngineRepository,
    private val endpointStore: EndpointStore,
    localEngineStore: LocalEngineStore,
    settingsRepository: SettingsRepository,
    private val attachedDb: AttachedDbStore,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val consoleLines = MutableStateFlow(ConsoleBuffer())
    private val dbTitle = MutableStateFlow<String?>(null)

    /** Where the engine is, as one value: combine takes five flows at most. */
    private data class Link(
        val local: Boolean,
        val serverEnabled: Boolean,
        val endpoint: EngineEndpoint,
        val health: LinkHealth,
        val state: ConnectionState,
    )

    /** What «세부 수치» reads. */
    private data class Detail(
        val profile: LocalEngineProfile,
        val thermal: Int?,
        val caps: EngineCapabilities,
        val db: String?,
    )

    private val link = combine(
        endpointStore.localMode,
        endpointStore.serverEnabled,
        endpointStore.endpoint,
        repository.health,
        repository.state,
    ) { local, serverEnabled, endpoint, health, state -> Link(local, serverEnabled, endpoint, health, state) }

    private val detail = combine(
        localEngineStore.profile,
        repository.thermalThreads,
        repository.capabilities,
        dbTitle,
    ) { profile, thermal, caps, db -> Detail(profile, thermal, caps, db) }

    val uiState: StateFlow<ConnectionUiState> =
        combine(link, detail, consoleLines, draft, settingsRepository.settings) { l, d, buffer, text, config ->
            ConnectionUiState(
                localMode = l.local,
                serverEnabled = l.serverEnabled,
                endpoint = l.endpoint,
                state = l.state,
                health = l.health,
                console = buffer.lines,
                commandDraft = text,
                showLog = config.showLog,
                logScalePercent = config.logScale,
                logFont = FontSpec.parse(config.textLogFont),
                profile = d.profile,
                thermalThreads = d.thermal,
                capabilities = d.caps,
                dbTitle = d.db,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionUiState(),
        )

    init {
        viewModelScope.launch {
            repository.console.collect { line ->
                consoleLines.update { it.plus(line) }
            }
        }
        // The attached entry changes with the library (L5-4 switches it with the
        // engine up); every change of the engine's state is a cheap time to look.
        viewModelScope.launch {
            repository.state.map { it.isLive }.distinctUntilChanged().collect {
                dbTitle.value = runCatching { attachedDb.attachedTitle() }.getOrNull()
            }
        }
    }

    fun onDraftChange(value: String) { draft.value = value }

    /** Start whichever engine Settings ▸ 엔진 chose — the shell's start, from here. */
    fun onConnect() {
        viewModelScope.launch {
            val local = endpointStore.localMode.first()
            val endpoint = endpointStore.endpoint.first()
            // No default server address: a blank one would resolve to this phone.
            // The button is off then; this covers a tap that raced the settings.
            if (!local && !endpoint.isConfigured) return@launch
            val target = if (local) EngineTarget.Local else EngineTarget.Remote(endpoint)
            runCatching { repository.connect(target) }
        }
    }

    fun onDisconnect() {
        repository.disconnect()
    }

    /** `YXHASHCLEAR` once, now — a one-off, so beside the engine rather than in a list of settings. */
    fun onHashClear() {
        if (repository.state.value != ConnectionState.Ready) return
        viewModelScope.launch { runCatching { repository.send(EngineCommand.YxHashClear) } }
    }

    fun onSend() {
        val line = draft.value.trim()
        if (line.isEmpty()) return
        draft.value = ""
        viewModelScope.launch {
            runCatching { repository.send(EngineCommand.Raw(line)) }
        }
    }

    /** Stop waiting out the backoff and try the engine again now. */
    fun onRetryNow() {
        viewModelScope.launch { runCatching { repository.retryNow() } }
    }

    fun onClearConsole() {
        consoleLines.value = ConsoleBuffer()
    }
}
