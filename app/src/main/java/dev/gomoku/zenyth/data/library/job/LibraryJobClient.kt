package dev.gomoku.zenyth.data.library.job

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.EngineBusy
import dev.gomoku.zenyth.data.engine.ThermalMonitor
import dev.gomoku.zenyth.data.library.job.LibraryJobProtocol as P
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.JobProgress
import dev.gomoku.zenyth.domain.repository.EngineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Descriptors for one job, opened by the UI process (which holds the SAF
 * grants — `:library` needs none). Sent with the job, then closed here: the
 * service received its own copies.
 */
class JobFds(
    val inputs: List<ParcelFileDescriptor>,
    val outputs: List<ParcelFileDescriptor> = emptyList(),
    val verifies: List<ParcelFileDescriptor> = emptyList(),
) : Closeable {
    override fun close() {
        for (pfd in inputs + outputs + verifies) runCatching { pfd.close() }
    }
}

sealed interface JobOutcome {
    data class Done(val result: JobResult) : JobOutcome
    data object Cancelled : JobOutcome
    data class Failed(val message: String, val outOfMemory: Boolean = false) : JobOutcome

    /** The job process died unasked (an OOM the heap could not catch, a low-memory kill). */
    data object Died : JobOutcome
}

/** The job running now, for the screen. */
data class ActiveJob(
    val id: Int,
    val job: LibraryJob,
    val progress: JobProgress? = null,
    val yielding: JobYield? = null,
    val cancelling: Boolean = false,
)

/**
 * The UI side of `:library` (L5 design §2.2 · §2.3): binds
 * [LibraryJobService], runs jobs **one at a time** in call order, and owns
 * the parts the job process cannot do for itself —
 *
 * - **Kill.** [cancel] sends the flag; a job still running
 *   [LibraryJobProtocol.KILL_AFTER_MS] later is killed by pid
 *   (`Process.killProcess` — same uid). The answer is then [JobOutcome.Cancelled].
 * - **Death.** A process that dies unasked answers [JobOutcome.Died]; [scan]
 *   then counts again without the index, so the card still gets its numbers.
 * - **Memory gate.** [indexBudget] = [MemoryGate] over this device's memory
 *   and the job process's heap limit (sent in its first answer).
 * - **Giving way.** A [JobPriority.BACKGROUND] job is paused while the engine
 *   searches or the phone is SEVERE-hot ([JobYield]), and resumed after.
 *
 * The binding is dropped after a short idle so the process can go.
 */
@Singleton
class LibraryJobClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineRepository,
    private val busy: EngineBusy,
    private val thermal: ThermalMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()

    private val _active = MutableStateFlow<ActiveJob?>(null)
    val active: StateFlow<ActiveJob?> = _active.asStateFlow()

    // Main thread only from here.
    private var nextId = 1
    private var conn: Connection? = null
    private var pending: Pending? = null
    private var idleUnbind: Job? = null

    data class Hello(val pid: Int, val heapMax: Long)

    private class Pending(val id: Int, val onStats: (DbStats) -> Unit) {
        val done = CompletableDeferred<JobOutcome>()
        var cancelRequested = false
    }

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { onMessage(it); true })

    private inner class Connection : ServiceConnection {
        val hello = CompletableDeferred<Hello>()
        var info: Hello? = null
        var service: Messenger? = null
        var binder: IBinder? = null
        var dead = false
        val death = IBinder.DeathRecipient { scope.launch { died(this@Connection) } }

        override fun onServiceConnected(name: ComponentName?, b: IBinder) {
            binder = b
            service = Messenger(b)
            runCatching { b.linkToDeath(death, 0) }
            send(this, P.HELLO, 0, null)
        }

        override fun onServiceDisconnected(name: ComponentName?) = died(this)
        override fun onBindingDied(name: ComponentName?) = died(this)
        override fun onNullBinding(name: ComponentName?) = died(this)
    }

    // ── public ─────────────────────────────────────────────────────────

    /**
     * Runs [job] after the jobs before it. [fds] opens the descriptors (on an IO
     * thread, when this job's turn comes). Cancelling the caller cancels the job.
     */
    suspend fun run(job: LibraryJob, fds: () -> JobFds, onStats: (DbStats) -> Unit = {}): JobOutcome =
        queue.withLock { withContext(Dispatchers.Main.immediate) { runNow(job, fds, onStats) } }

    /** Cancels the running job: flag first, the process killed if it has not stopped in 1.5 s. */
    fun cancel() {
        scope.launch {
            val p = pending ?: return@launch
            if (p.cancelRequested) return@launch
            p.cancelRequested = true
            _active.update { it?.copy(cancelling = true) }
            val c = conn ?: return@launch
            send(c, P.CANCEL, p.id, null)
            delay(P.KILL_AFTER_MS)
            if (pending !== p) return@launch
            val pid = c.info?.pid
            if (pid != null && pid != Process.myPid()) {
                Log.w(TAG, "job ${p.id} did not stop — killing :library ($pid)")
                Process.killProcess(pid)
            }
            delay(DEATH_GRACE_MS) // binderDied normally answers first
            if (pending === p) died(c)
        }
    }

    /** Bytes an index may take now ([MemoryGate]). */
    suspend fun indexBudget(): Long {
        val hello = withContext(Dispatchers.Main.immediate) { connect().second }
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return MemoryGate.budget(info.totalMem, hello.heapMax, info.availMem, info.threshold)
    }

    /**
     * Stats + index of one file, the index only when the gate allows it. A run
     * that killed the job process or ran out of memory runs again without the
     * index (§2.2 ③: the card always gets its count and rules). [open] is
     * called once per run.
     */
    suspend fun scan(
        size: Long,
        open: () -> ParcelFileDescriptor,
        expect: ExpectedFile? = null,
        priority: JobPriority = JobPriority.BACKGROUND,
        onStats: (DbStats) -> Unit = {},
    ): JobOutcome {
        val job = LibraryJob.Scan(size, index = true, maxIndexBytes = indexBudget(), expect = expect, priority = priority)
        val first = run(job, { JobFds(listOf(open())) }, onStats)
        val lost = first is JobOutcome.Died || (first is JobOutcome.Failed && first.outOfMemory)
        if (!lost) return first
        Log.w(TAG, "scan lost its process ($first) — counting again without the index")
        val second = run(job.copy(index = false), { JobFds(listOf(open())) }, onStats)
        val r = (second as? JobOutcome.Done)?.result as? JobResult.Scanned ?: return second
        return JobOutcome.Done(r.copy(indexSkipped = "MEMORY", trimmed = true))
    }

    // ── one job (main thread) ──────────────────────────────────────────

    private suspend fun runNow(job: LibraryJob, fds: () -> JobFds, onStats: (DbStats) -> Unit): JobOutcome {
        idleUnbind?.cancel()
        val id = nextId++
        val p = Pending(id, onStats)
        pending = p
        _active.value = ActiveJob(id, job)
        var yielding: Job? = null
        try {
            val c = try {
                connect().first
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return JobOutcome.Failed("library process: ${t.message}")
            }
            val opened = try {
                withContext(Dispatchers.IO) { fds() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return JobOutcome.Failed("open: ${t.message ?: t::class.java.simpleName}")
            }
            opened.use {
                if (p.cancelRequested) return JobOutcome.Cancelled
                if (opened.inputs.size != job.inputs || opened.outputs.size != job.outputs || opened.verifies.size != job.verifies) {
                    return JobOutcome.Failed("descriptors do not match the job")
                }
                startForeground()
                val y0 = if (job.priority == JobPriority.BACKGROUND) yieldNow() else null
                val data = Bundle().apply {
                    putString(P.KEY_JOB, JobJson.encode(job))
                    P.putFds(this, P.KEY_IN, opened.inputs)
                    P.putFds(this, P.KEY_OUT, opened.outputs)
                    P.putFds(this, P.KEY_VERIFY, opened.verifies)
                }
                if (!send(c, P.START, id, data, arg2 = y0?.code ?: 0)) died(c)
                if (job.priority == JobPriority.BACKGROUND) yielding = watchYield(c, id)
            }
            return try {
                p.done.await()
            } catch (e: CancellationException) {
                cancel()
                withContext(NonCancellable) { p.done.await() }
                throw e
            }
        } finally {
            yielding?.cancel()
            if (pending === p) pending = null
            _active.value = null
            scheduleIdleUnbind()
        }
    }

    private fun yieldNow(): JobYield? =
        JobYield.of(engine.state.value == ConnectionState.Thinking || busy.isBusy, thermal.state.value.status)

    /** Pauses and resumes the running background job as the engine and the heat change. */
    private fun watchYield(c: Connection, id: Int): Job = scope.launch {
        combine(engine.state, busy.reasons, thermal.state) { s, reasons, t ->
            JobYield.of(s == ConnectionState.Thinking || reasons.isNotEmpty(), t.status)
        }.distinctUntilChanged().collect { y ->
            _active.update { it?.copy(yielding = y) }
            send(c, if (y != null) P.PAUSE else P.RESUME, id, null, arg2 = y?.code ?: 0)
        }
    }

    /** Foreground while the job runs — refused from the background on Android 12+, then the binding carries it. */
    private fun startForeground() {
        try {
            ContextCompat.startForegroundService(context, LibraryJobService.intent(context, LibraryJobService.ACTION_RUN))
        } catch (e: IllegalStateException) {
            Log.i(TAG, "library job without a foreground service: ${e.message}")
        } catch (e: SecurityException) {
            Log.i(TAG, "library job without a foreground service: ${e.message}")
        }
    }

    // ── connection (main thread) ───────────────────────────────────────

    private suspend fun connect(): Pair<Connection, Hello> {
        idleUnbind?.cancel()
        val c = conn?.takeIf { !it.dead } ?: Connection().also {
            conn = it
            val bound = runCatching {
                context.bindService(android.content.Intent(context, LibraryJobService::class.java), it, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) {
                conn = null
                runCatching { context.unbindService(it) }
                throw IllegalStateException("cannot bind the library job service")
            }
        }
        return c to withTimeout(CONNECT_TIMEOUT_MS) { c.hello.await() }
    }

    private fun died(c: Connection) {
        if (c.dead) return
        c.dead = true
        if (conn === c) conn = null
        c.binder?.let { b -> runCatching { b.unlinkToDeath(c.death, 0) } }
        runCatching { context.unbindService(c) }
        c.hello.completeExceptionally(IllegalStateException("library process died"))
        val p = pending ?: return
        pending = null
        p.done.complete(if (p.cancelRequested) JobOutcome.Cancelled else JobOutcome.Died)
    }

    private fun scheduleIdleUnbind() {
        idleUnbind?.cancel()
        idleUnbind = scope.launch {
            delay(IDLE_UNBIND_MS)
            val c = conn ?: return@launch
            if (pending != null) return@launch
            conn = null
            c.dead = true
            c.binder?.let { b -> runCatching { b.unlinkToDeath(c.death, 0) } }
            runCatching { context.unbindService(c) }
        }
    }

    private fun send(c: Connection, what: Int, id: Int, data: Bundle?, arg2: Int = 0): Boolean {
        val s = c.service ?: return false
        return try {
            s.send(Message.obtain(null, what, id, arg2).also { m ->
                m.replyTo = incoming
                if (data != null) m.data = data
            })
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun onMessage(msg: Message) {
        if (msg.what == P.HELLO_REPLY) {
            val d = msg.data
            val c = conn ?: return
            val h = Hello(d.getInt(P.KEY_PID), d.getLong(P.KEY_HEAP_MAX))
            c.info = h
            c.hello.complete(h)
            return
        }
        val p = pending ?: return
        if (msg.arg1 != p.id) return // a finished job's late message
        val d = msg.data
        when (msg.what) {
            P.PROGRESS -> _active.update {
                it?.copy(progress = JobProgress(d.getString(P.KEY_PHASE).orEmpty(), d.getDouble(P.KEY_FRACTION), d.getLong(P.KEY_RECORDS, -1)))
            }
            // The service began cancelling on its own (its notification's [Cancel], a timeout):
            // a kill that follows is then a cancel, not a death.
            P.CANCELLING -> {
                p.cancelRequested = true
                _active.update { it?.copy(cancelling = true) }
            }
            P.STATS -> runCatching { JobJson.decodeStats(d.getString(P.KEY_JSON).orEmpty()) }.getOrNull()?.let(p.onStats)
            P.RESULT -> p.done.complete(
                runCatching { JobOutcome.Done(JobJson.decodeResult(d.getString(P.KEY_JSON).orEmpty())) }
                    .getOrElse { JobOutcome.Failed("bad result: ${it.message}") },
            )
            P.CANCELLED -> p.done.complete(JobOutcome.Cancelled)
            P.FAILED -> p.done.complete(JobOutcome.Failed(d.getString(P.KEY_MESSAGE).orEmpty(), d.getBoolean(P.KEY_OOM)))
        }
    }

    private companion object {
        const val TAG = "LibraryJobClient"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DEATH_GRACE_MS = 1_000L
        const val IDLE_UNBIND_MS = 30_000L
    }
}
