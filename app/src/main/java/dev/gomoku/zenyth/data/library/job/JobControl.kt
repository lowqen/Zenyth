package dev.gomoku.zenyth.data.library.job

import dev.gomoku.zenyth.core.model.ThermalState
import dev.gomoku.zenyth.domain.library.yxdb.YxdbIndex

/**
 * One job's switches, flipped from the service's message thread and read by
 * the job thread between stream chunks (256 KB — a few ms each).
 *
 * - [cancel]: the codec stops at the next chunk ([check] answers true). What a
 *   chunk cannot cover (a sort, an in-memory pass) is the 1.5 s kill's job.
 * - [pause]: [check] blocks until resumed or cancelled — a pause, not a cancel:
 *   the index stays in memory and the scan goes on where it was.
 * - [dropIndex]: memory pressure — a scan gives its index up and returns the
 *   pass-1 stats.
 */
class JobControl {
    private val lock = Object()

    @Volatile var cancelled = false
        private set

    @Volatile var paused = false
        private set

    @Volatile var dropIndex = false

    fun cancel() = synchronized(lock) {
        cancelled = true
        lock.notifyAll()
    }

    fun pause(on: Boolean) = synchronized(lock) {
        paused = on
        lock.notifyAll()
    }

    /** The codec's `isCancelled`: blocks while paused, then answers whether to stop. */
    fun check(): Boolean {
        if (paused && !cancelled) {
            synchronized(lock) {
                while (paused && !cancelled) lock.wait()
            }
        }
        return cancelled
    }
}

/**
 * The memory gate (L5 design §2.3) — how many bytes an index may take now:
 *
 * ```
 * min( totalMem / 16                 web deviceMemory/16 (4 GB → 256 MB)
 *      heapMax × 0.6                 the :library process's heap limit
 *      (availMem − threshold) / 2 )  what the engine's table and DB leave
 * ```
 */
object MemoryGate {
    fun budget(totalMem: Long, heapMax: Long, availMem: Long, threshold: Long): Long =
        minOf(totalMem / 16, (heapMax * 0.6).toLong(), ((availMem - threshold) / 2).coerceAtLeast(0))

    /** Would an index of [records] fit in [budget]? (web `indexBytesEstimate`) */
    fun fits(records: Long, budget: Long): Boolean = YxdbIndex.bytesEstimate(records) <= budget
}

/**
 * When the background stats count gives way (L5 design §2.2, the mobile part):
 * the engine is searching (an analysis, or a review · proof holding
 * `EngineBusy`), or the phone is at SEVERE heat or worse. User jobs never
 * yield.
 */
enum class JobYield(val code: Int) {
    ENGINE(1),
    THERMAL(2);

    companion object {
        fun of(engineSearching: Boolean, thermalStatus: Int): JobYield? = when {
            engineSearching -> ENGINE
            thermalStatus >= ThermalState.STATUS_SEVERE -> THERMAL
            else -> null
        }

        fun ofCode(code: Int): JobYield? = entries.firstOrNull { it.code == code }
    }
}
