package dev.gomoku.zenyth.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own UI choices: which board tab was open, which folds were
 * expanded, whether the engine starts with the app.
 *
 * None of this has a line in the desktop's `settings.txt`, so it does not
 * belong in [dev.gomoku.zenyth.core.model.AppSettings] (pinned to those 67
 * lines) — the same reason [LocalEngineStore] is separate.
 *
 * SharedPreferences rather than DataStore: a fold has to know whether it is open
 * in the frame it is first drawn, and a flow that answers a frame later makes
 * every remembered panel blink shut and open again on launch.
 */
@Singleton
class UiPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Connect to the last engine when the app opens (Zenyth Web's auto boot). */
    val autoStartEngine: BoolPref get() = bool("engine.autostart", true)

    /**
     * «고급 사용자 모드» — off by default (user request, 2026-10-29). Since L5-5 it
     * guards **dangerous writes only**, as Zenyth Web decided: the DB editor's bulk
     * delete (and the server-path split), the console's command line, the engine's
     * tools and parameters (hash, server), the board's Tools card and the PC
     * settings list. Every screen is there without it — the DB editor and the
     * engine log too. It took over the DB screen's own «파괴적 연산 해제» switch.
     * Read by the activity into `LocalProMode`, and by the database repository.
     */
    val proMode: BoolPref get() = bool("pro.enabled", false)

    /**
     * The board sheet shows [개요 | 로그] — true for «로그» (L5-5, Zenyth Web's
     * `inspectorPane`). A pref rather than screen state: the old engine route
     * opens the board on it, and it is remembered across launches like the web's.
     */
    val boardLogPane: BoolPref get() = bool("board.pane.log", false)

    /**
     * Fetch the official joseki curriculum from Zenyth Web by itself. The app's one
     * automatic network request (a static file, nothing sent); off, the refresh
     * button and lesson links still fetch when asked.
     */
    val curriculumAutoUpdate: BoolPref get() = bool("curriculum.autoupdate", true)

    /** Whether [key] has ever been written — a default that depends on that needs it. */
    fun contains(key: String): Boolean = prefs.contains(key)

    /** Whether a [dev.gomoku.zenyth.core.designsystem.component.Disclosure] is open. */
    fun isOpen(id: String, default: Boolean): Boolean = prefs.getBoolean("open.$id", default)

    fun setOpen(id: String, open: Boolean) {
        prefs.edit().putBoolean("open.$id", open).apply()
    }

    private val cache = HashMap<String, Any>()

    /** One instance per key, so every reader of a key sees the same flow. */
    @Synchronized
    fun bool(key: String, default: Boolean): BoolPref =
        cache.getOrPut(key) { BoolPref(key, default) } as BoolPref

    @Synchronized
    fun int(key: String, default: Int): IntPref =
        cache.getOrPut(key) { IntPref(key, default) } as IntPref

    @Synchronized
    fun string(key: String, default: String): StringPref =
        cache.getOrPut(key) { StringPref(key, default) } as StringPref

    inner class BoolPref internal constructor(private val key: String, default: Boolean) {
        private val flow = MutableStateFlow(prefs.getBoolean(key, default))
        val state: StateFlow<Boolean> = flow.asStateFlow()
        val value: Boolean get() = flow.value
        fun set(v: Boolean) {
            prefs.edit().putBoolean(key, v).apply()
            flow.value = v
        }
    }

    inner class IntPref internal constructor(private val key: String, default: Int) {
        private val flow = MutableStateFlow(prefs.getInt(key, default))
        val state: StateFlow<Int> = flow.asStateFlow()
        val value: Int get() = flow.value
        fun set(v: Int) {
            prefs.edit().putInt(key, v).apply()
            flow.value = v
        }
    }

    inner class StringPref internal constructor(private val key: String, default: String) {
        private val flow = MutableStateFlow(prefs.getString(key, default) ?: default)
        val state: StateFlow<String> = flow.asStateFlow()
        val value: String get() = flow.value
        fun set(v: String) {
            prefs.edit().putString(key, v).apply()
            flow.value = v
        }
    }
}
