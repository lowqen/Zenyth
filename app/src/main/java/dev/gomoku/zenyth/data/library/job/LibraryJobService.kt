package dev.gomoku.zenyth.data.library.job

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.gomoku.zenyth.R
import dev.gomoku.zenyth.ZenythApp
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.engine.EngineService
import dev.gomoku.zenyth.data.library.job.LibraryJobProtocol as P
import dev.gomoku.zenyth.domain.library.yxdb.JobProgress
import dev.gomoku.zenyth.domain.library.yxdb.YxdbCancelled
import dev.gomoku.zenyth.domain.library.yxdb.YxdbInput
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The library's heavy byte work, in its own process `:library` (L5 design
 * §2.2 — the web's dedicated worker):
 *
 * 1. **Its own heap** — a 137 MB index does not share the UI's limit.
 * 2. **Instant cancel** — a flag the codec checks per 256 KB chunk; still
 *    running [P.KILL_AFTER_MS] later (a sort), the process is killed. The UI
 *    side kills too ([LibraryJobClient]), and temp files are swept by the next
 *    job and the next app start.
 * 3. **Isolation** — an OOM or a low-memory kill here leaves the board and the
 *    engine alone; the UI sees `binderDied` and lowers the job.
 *
 * Bound by [LibraryJobClient] for the messages ([LibraryJobProtocol]); started
 * as a dataSync foreground service while a job runs, when the app may start
 * one (Android 12+ refuses from the background — the job then runs with the
 * binding app's priority). No Hilt here: `ZenythApp` skips its graph in this
 * process, and the service only calls the codec. One job at a time, on one
 * thread (the engine keeps the other cores).
 */
class LibraryJobService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "library-job") }

    private var client: Messenger? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    private var running: Running? = null
    private var foreground = false
    private var lastNotify = 0L

    private class Running(val id: Int, val job: LibraryJob, val control: JobControl) {
        @Volatile var progress: JobProgress? = null
        @Volatile var yielding: JobYield? = null
    }

    override fun onCreate() {
        super.onCreate()
        ZenythApp.ensureEngineChannel(this)
        // Whatever a killed job left (nothing runs yet in this new process).
        P.spillRoot(this).deleteRecursively()
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            running?.let { cancel(it) }
            return START_NOT_STICKY
        }
        // ACTION_RUN (startForegroundService): must reach startForeground now, job or not.
        goForeground()
        main.postDelayed({ if (running == null) leaveForeground() }, IDLE_FOREGROUND_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running?.let { cancel(it) }
        unlinkClient()
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * Android 15 gives dataSync six hours a day, then calls this and crashes
     * a service that has not stopped within seconds. Cancel — nothing is lost:
     * outputs are temp files the UI never adopts, and an unfinished scan is
     * simply counted again later (§9 «안전한 백그라운드 되쓰기» 4).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        running?.let { cancel(it) }
        leaveForeground()
    }

    /**
     * Memory pressure: a scan drops its index and returns the basic stats.
     * (`RUNNING_CRITICAL` reaches a foreground process only before Android 14;
     * the low-memory kill behind it is what isolation is for.)
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            running?.control?.dropIndex = true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        running?.control?.dropIndex = true
    }

    // ── messages (main thread) ─────────────────────────────────────────

    private fun handle(msg: Message) {
        when (msg.what) {
            P.HELLO -> {
                msg.replyTo?.let { linkClient(it) }
                send(P.HELLO_REPLY, 0, Bundle().apply {
                    putInt(P.KEY_PID, Process.myPid())
                    putLong(P.KEY_HEAP_MAX, Runtime.getRuntime().maxMemory())
                })
            }
            P.START -> start(msg.arg1, msg.arg2, msg.data)
            P.CANCEL -> running?.takeIf { it.id == msg.arg1 }?.let { cancel(it) }
            P.PAUSE, P.RESUME -> running?.takeIf { it.id == msg.arg1 && it.job.priority == JobPriority.BACKGROUND }?.let {
                val on = msg.what == P.PAUSE
                it.yielding = if (on) JobYield.ofCode(msg.arg2) ?: JobYield.ENGINE else null
                it.control.pause(on)
                notifyProgress(force = true)
            }
        }
    }

    private fun linkClient(m: Messenger) {
        unlinkClient()
        client = m
        val death = IBinder.DeathRecipient {
            // The UI process is gone: nobody will adopt the outputs — stop.
            main.post {
                running?.let { cancel(it) }
                client = null
                leaveForeground()
            }
        }
        runCatching { m.binder.linkToDeath(death, 0) }.onSuccess { clientDeath = death }
    }

    private fun unlinkClient() {
        val c = client ?: return
        clientDeath?.let { runCatching { c.binder.unlinkToDeath(it, 0) } }
        clientDeath = null
        client = null
    }

    /** [yieldCode] ≠ 0: a background job starts paused (the engine was already searching). */
    private fun start(id: Int, yieldCode: Int, data: Bundle?) {
        val ins = data?.let { P.fds(it, P.KEY_IN) }.orEmpty()
        val outs = data?.let { P.fds(it, P.KEY_OUT) }.orEmpty()
        val verifies = data?.let { P.fds(it, P.KEY_VERIFY) }.orEmpty()
        val all = ins + outs + verifies
        fun refuse(why: String) {
            all.forEach { runCatching { it.close() } }
            send(P.FAILED, id, Bundle().apply { putString(P.KEY_MESSAGE, why) })
        }
        if (running != null) return refuse("busy")
        val job = runCatching { JobJson.decodeJob(data?.getString(P.KEY_JOB).orEmpty()) }.getOrElse {
            return refuse("bad job: ${it.message}")
        }
        if (ins.size != job.inputs || outs.size != job.outputs || verifies.size != job.verifies) {
            return refuse("descriptors ${ins.size}/${outs.size}/${verifies.size} for ${job.inputs}/${job.outputs}/${job.verifies}")
        }
        val r = Running(id, job, JobControl())
        if (job.priority == JobPriority.BACKGROUND) {
            JobYield.ofCode(yieldCode)?.let {
                r.yielding = it
                r.control.pause(true)
            }
        }
        running = r
        notifyProgress(force = true)
        worker.execute { work(r, FdJobIo(job, ins, outs, verifies, File(P.spillRoot(this), "job-$id"))) }
    }

    private fun cancel(r: Running) {
        if (r.control.cancelled) return
        r.control.cancel()
        send(P.CANCELLING, r.id, Bundle())
        main.postDelayed({
            if (running === r) {
                Log.w(TAG, "job ${r.id} did not stop within ${P.KILL_AFTER_MS} ms — killing the process")
                Process.killProcess(Process.myPid())
            }
        }, P.KILL_AFTER_MS)
    }

    // ── the job (worker thread) ────────────────────────────────────────

    private fun work(r: Running, io: FdJobIo) {
        Process.setThreadPriority(
            if (r.job.priority == JobPriority.BACKGROUND) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT,
        )
        var failed = true
        val (what, bundle) = try {
            P.spillRoot(this).deleteRecursively()
            io.spillDir.mkdirs()
            val runner = LibraryJobRunner(
                io, r.control,
                onProgress = { p ->
                    r.progress = p
                    send(P.PROGRESS, r.id, Bundle().apply {
                        putString(P.KEY_PHASE, p.phase)
                        putDouble(P.KEY_FRACTION, p.fraction)
                        putLong(P.KEY_RECORDS, p.records)
                    })
                    main.post { notifyProgress(force = false) }
                },
                onStats = { s -> send(P.STATS, r.id, Bundle().apply { putString(P.KEY_JSON, JobJson.encode(s)) }) },
            )
            val result = runner.run(r.job)
            failed = false
            P.RESULT to Bundle().apply { putString(P.KEY_JSON, JobJson.encode(result)) }
        } catch (_: YxdbCancelled) {
            P.CANCELLED to Bundle()
        } catch (e: OutOfMemoryError) {
            P.FAILED to Bundle().apply {
                putString(P.KEY_MESSAGE, "out of memory")
                putBoolean(P.KEY_OOM, true)
            }
        } catch (t: Throwable) {
            if (r.control.cancelled) {
                P.CANCELLED to Bundle()
            } else {
                Log.w(TAG, "job ${r.id} failed", t)
                P.FAILED to Bundle().apply { putString(P.KEY_MESSAGE, t.message ?: t::class.java.simpleName) }
            }
        } finally {
            io.close(failed)
            io.spillDir.deleteRecursively()
        }
        send(what, r.id, bundle)
        main.post {
            if (running === r) running = null
            leaveForeground()
        }
    }

    private fun send(what: Int, id: Int, data: Bundle) {
        val c = client ?: return
        try {
            c.send(Message.obtain(null, what, id, 0).also { it.data = data })
        } catch (_: RemoteException) {
            // The UI is gone; its death recipient cancels.
        }
    }

    // ── foreground · notification ──────────────────────────────────────

    private fun goForeground() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        foreground = true
    }

    private fun leaveForeground() {
        if (!foreground || running != null) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf() // stays alive while bound
    }

    /** At most once a second (Android drops faster updates), and on pause changes. */
    private fun notifyProgress(force: Boolean) {
        if (!foreground) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(android.app.NotificationManager::class.java)?.notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val r = running
        val title = when (r?.job) {
            is LibraryJob.Scan -> tr("라이브러리 통계 세는 중", "Counting library stats", "正在统计棋库")
            is LibraryJob.MergeCount, is LibraryJob.MergeWrite -> tr("DB 합치는 중", "Merging databases", "正在合并数据库")
            is LibraryJob.SplitCount, is LibraryJob.SplitWrite -> tr("DB 나누는 중", "Splitting a database", "正在拆分数据库")
            is LibraryJob.Copy -> tr("DB 복사하는 중", "Copying a database", "正在复制数据库")
            null -> tr("라이브러리 작업 준비 중", "Preparing a library job", "正在准备棋库任务")
        }
        val fraction = r?.progress?.fraction
        val text = when (r?.yielding) {
            JobYield.ENGINE -> tr("엔진이 탐색하는 동안 쉬는 중", "Paused while the engine searches", "引擎搜索时暂停")
            JobYield.THERMAL -> tr("기기가 뜨거워 쉬는 중", "Paused — the device is hot", "设备过热，暂停中")
            null -> fraction?.let { "${(it * 100).toInt()}%" } ?: tr("준비 중…", "Starting…", "准备中…")
        }
        val cancel = PendingIntent.getService(
            this, 0,
            Intent(this, LibraryJobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, EngineService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (r != null) b.addAction(R.drawable.ic_notification, tr("취소", "Cancel", "取消"), cancel)
        if (fraction != null) b.setProgress(1000, (fraction * 1000).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val TAG = "LibraryJobService"
        private const val NOTIF_ID = 1003
        /** A foreground start with no job behind it (the UI died before sending it) ends after this. */
        private const val IDLE_FOREGROUND_MS = 10_000L
        const val ACTION_RUN = "dev.gomoku.zenyth.library.RUN"
        const val ACTION_CANCEL = "dev.gomoku.zenyth.library.CANCEL"

        fun intent(context: Context, action: String) = Intent(context, LibraryJobService::class.java).setAction(action)
    }
}

/**
 * A job's descriptors as [JobIo]. An input re-opens by seeking back to 0
 * (a scan reads two or three times); a provider that hands out a pipe can be
 * read once only — staging (L5-4) copies such files first. Outputs sync on
 * close; the descriptors themselves close after the job — with an error when
 * it failed, so a provider does not commit half a file.
 */
private class FdJobIo(
    private val job: LibraryJob,
    private val ins: List<ParcelFileDescriptor>,
    private val outs: List<ParcelFileDescriptor>,
    private val verifies: List<ParcelFileDescriptor>,
    override val spillDir: File,
) : JobIo, Closeable {
    private val inputs = ins.mapIndexed { i, pfd -> fdInput(pfd, sizeHint(i)) }

    private fun sizeHint(i: Int): Long = when (job) {
        is LibraryJob.Scan -> job.size
        is LibraryJob.MergeCount -> job.sizes[i]
        is LibraryJob.MergeWrite -> job.sizes[i]
        is LibraryJob.SplitCount -> job.size
        is LibraryJob.SplitWrite -> job.size
        is LibraryJob.Copy -> job.size
    }

    override fun input(i: Int): YxdbInput = inputs[i]

    override fun output(i: Int): OutputStream = FdOutput(outs[i])

    /** Built when asked — after the write, so its size is the written one. */
    override fun verify(i: Int): YxdbInput = fdInput(verifies[i], 0)

    fun close(failed: Boolean) {
        for (pfd in ins + verifies) runCatching { pfd.close() }
        for (pfd in outs) runCatching { if (failed) pfd.closeWithError("library job failed") else pfd.close() }
    }

    override fun close() = close(failed = false)

    companion object {
        fun fdInput(pfd: ParcelFileDescriptor, hint: Long): YxdbInput {
            val stream = FileInputStream(pfd.fileDescriptor)
            var opened = false
            val size = pfd.statSize.takeIf { it >= 0 } ?: hint
            return YxdbInput(size) {
                if (opened) stream.channel.position(0) // a pipe throws here: one pass only
                opened = true
                object : FilterInputStream(stream) {
                    override fun close() {} // the descriptor is closed by the job
                }
            }
        }
    }
}

private class FdOutput(private val pfd: ParcelFileDescriptor) : OutputStream() {
    private val out = FileOutputStream(pfd.fileDescriptor)
    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() {
        out.flush()
        runCatching { pfd.fileDescriptor.sync() } // some providers' pipes cannot sync
    }
}
