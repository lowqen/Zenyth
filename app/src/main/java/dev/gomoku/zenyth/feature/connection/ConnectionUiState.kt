package dev.gomoku.zenyth.feature.connection

import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.ConsoleLine
import dev.gomoku.zenyth.core.model.EngineCapabilities
import dev.gomoku.zenyth.core.model.EngineEndpoint
import dev.gomoku.zenyth.core.model.FontSpec
import dev.gomoku.zenyth.core.model.LinkHealth
import dev.gomoku.zenyth.core.model.LocalEngineProfile

/**
 * The board's «로그» pane (L5-5): the engine's state line, start/stop, the
 * folded figures and the console. Where the engine is — on the phone or a
 * server, and the server's address — is chosen in Settings ▸ 엔진 now; the
 * pane only reads it.
 */
data class ConnectionUiState(
    /** True while the on-device engine is chosen (always, unless the server is offered). */
    val localMode: Boolean = true,
    /** Whether the server engine is offered at all (Settings ▸ 엔진, expert). */
    val serverEnabled: Boolean = false,
    /** The saved server address — used only when the server is chosen. */
    val endpoint: EngineEndpoint = EngineEndpoint(),
    val state: ConnectionState = ConnectionState.Disconnected,
    /** Drop / reconnect status; [LinkHealth.idle] while nothing is wrong. */
    val health: LinkHealth = LinkHealth(),
    val console: List<ConsoleLine> = emptyList(),
    val commandDraft: String = "",
    /** settings.txt line 13 — the desktop's "show log". */
    val showLog: Boolean = true,
    /** settings.txt line 37 — console text scale in percent. */
    val logScalePercent: Int = 140,
    /** settings.txt line 45 — "Text Log Font"; only its size travels. */
    val logFont: FontSpec = FontSpec.DEFAULT,
    /** The on-device engine's threads · table · database switch, for «세부 수치». */
    val profile: LocalEngineProfile = LocalEngineProfile(),
    /** Threads heat or battery saver holds the on-device engine to (T1), or null. */
    val thermalThreads: Int? = null,
    val capabilities: EngineCapabilities = EngineCapabilities(),
    /** The attached library entry's name, or null before one is known. */
    val dbTitle: String? = null,
) {
    /**
     * Console font multiplier; 100 % = the app's default size. Two settings feed
     * it because the desktop has two: the log area scale (line 37) and the log
     * font itself (line 45). Multiplying keeps both live instead of letting one
     * silently win.
     */
    val logScale: Float get() =
        (logScalePercent.coerceIn(50, 300)) / 100f * logFont.scale

    /** While a reconnect is pending the endpoint is spoken for; don't offer it. */
    val canConnect: Boolean
        get() = !health.reconnecting &&
            (state is ConnectionState.Disconnected || state is ConnectionState.Error)

    /** The server engine is the one chosen (the same rule [ConnectionViewModel.onConnect] applies). */
    val serverChosen: Boolean get() = serverEnabled && !localMode

    /** The server is chosen but has no address — there is no default one; Settings asks for it. */
    val needsAddress: Boolean get() = serverChosen && !endpoint.isConfigured

    /** [canConnect], and a server address when the server is chosen. */
    val canStart: Boolean get() = canConnect && !needsAddress

    /** «해시 비우기» empties the table under a search's feet, so only while it waits. */
    val canClearHash: Boolean get() = state == ConnectionState.Ready

    val canSend: Boolean
        get() = state.isLive && commandDraft.isNotBlank()
}
