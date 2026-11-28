package dev.gomoku.zenyth.core.model

/**
 * When an engine session the user has walked away from should be hung up.
 *
 * A connected engine that is not searching still costs: the on-device engine holds
 * its table and weights resident (0.3 ~ 0.5 GB on the Galaxy A16 — 256 MB table plus
 * the renju net), a server link keeps a remote process alive, and either keeps the
 * ongoing notification up. Starting again is cheap — the local engine is ready in
 * ~0.65 s (process 377 ms + weights 280 ms, docs/engineering-notes.md §1 R2) — and
 * the session is brought back by itself when the app returns, so the wait can be
 * short: [PARK_AFTER_MS] covers a quick look at another app without paying for
 * the ones that never come back.
 *
 * Parking never cuts a search short: an analysis left running in the background is
 * what the foreground service is for. Only the user's own limit stops one
 * ([backgroundStopAfterMs], default 30 minutes — L5-6 decision).
 */
object EngineIdlePolicy {

    const val PARK_AFTER_MS = 3 * 60_000L

    /** Minutes an analysis may run with the app out of sight (0 = no limit); the setting's default. */
    const val BACKGROUND_STOP_DEFAULT_MIN = 30

    /** The choices the setting offers, 0 being «끔». */
    val BACKGROUND_STOP_CHOICES = listOf(0, 10, 30, 60)

    /**
     * How long to let an analysis run before stopping it, or null when it is left
     * alone: the app is in sight, nothing is analysing, or the limit is off. Game
     * moves, reviews and proofs are not analyses and are never cut short here.
     */
    fun backgroundStopAfterMs(foreground: Boolean, analysing: Boolean, limitMin: Int): Long? =
        if (foreground || !analysing || limitMin <= 0) null else limitMin * 60_000L

    /**
     * Whether the engine's foreground service may go: nobody wants a session, no
     * search is still settling, no reconnect is waiting, nothing holds it up (a
     * library write-back). Not the link's state alone — every connect passes
     * through `Disconnected` first, and a service that stopped there left the
     * engine running in a cached app (L5-6: killed for 353 s of CPU in 5 min).
     */
    fun serviceMayStop(wanted: Boolean, state: ConnectionState, health: LinkHealth, held: Boolean): Boolean =
        !wanted && state != ConnectionState.Thinking && !health.reconnecting && !held

    /**
     * Whether the session is idle and out of sight: the app is in the background and
     * the engine is up (or trying to come back) but not searching.
     */
    fun isIdle(foreground: Boolean, state: ConnectionState, health: LinkHealth): Boolean {
        if (foreground) return false
        return when (state) {
            ConnectionState.Ready, is ConnectionState.Error -> true
            // A reconnect loop waiting in the background is a session too — it is
            // what keeps the notification up after the link died.
            ConnectionState.Disconnected -> health.reconnecting
            ConnectionState.Thinking, ConnectionState.Connecting, ConnectionState.Handshaking -> false
        }
    }
}
