package dev.gomoku.zenyth

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.gomoku.zenyth.data.engine.EngineIdleWatcher
import dev.gomoku.zenyth.data.engine.EngineService
import dev.gomoku.zenyth.data.engine.MemoryGuard
import dev.gomoku.zenyth.data.library.LibraryAttachController
import dev.gomoku.zenyth.data.library.job.LibraryJobProtocol
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ZenythApp : Application() {

    // Lazy: the `:library` process runs this class too, and must not build
    // these (the engine watcher, the thermal listener) just by being injected.
    @Inject
    lateinit var engineIdleWatcher: Lazy<EngineIdleWatcher>

    @Inject
    lateinit var memoryGuard: Lazy<MemoryGuard>

    @Inject
    lateinit var libraryAttach: Lazy<LibraryAttachController>

    override fun onCreate() {
        super.onCreate()
        // The library job process (L5 design §2.2) only runs the codec: no
        // engine, no theme, nothing from the graph.
        if (LibraryJobProtocol.isLibraryProcess(this)) return
        ensureEngineChannel(this)
        // Before anything connects: a low-memory kill of the last run lowers the table (T3).
        memoryGuard.get().checkLastExit()
        engineIdleWatcher.get().start()
        // SAF copies the engine holds go home on a switch, a stop, ON_STOP and here (L5-4).
        libraryAttach.get().apply {
            start()
            observeProcessLifecycle()
        }
        // Temp files of a library job that was killed (skipped while one runs).
        Thread({ runCatching { LibraryJobProtocol.sweepStale(this) } }, "library-sweep").start()
    }

    companion object {
        /** The ongoing-work channel (engine, pack build, library jobs). Idempotent — each process calls it. */
        fun ensureEngineChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                EngineService.CHANNEL_ID,
                context.getString(R.string.engine_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.engine_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
