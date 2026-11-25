package dev.gomoku.zenyth.data.library.job

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.os.BundleCompat
import java.io.File

/**
 * The messages between the UI process and `:library` (L5 design §2.2) —
 * `Messenger` + `Bundle`. Bytes never travel: the job is JSON, files are
 * descriptors. `arg1` of every job message is the job id, so a late message of
 * a finished job is dropped.
 *
 * | UI → service | data | answer |
 * |---|---|---|
 * | [HELLO] | `replyTo` | [HELLO_REPLY] `pid` · `heapMax` |
 * | [START] | [KEY_JOB] · [KEY_IN] · [KEY_OUT] · [KEY_VERIFY], `arg2` = [JobYield.code] to start paused | [PROGRESS]* · [STATS]? · then [RESULT] · [CANCELLED] · [FAILED] |
 * | [CANCEL] | — | [CANCELLING], then the job ends [CANCELLED]; still running 1.5 s later → the process is killed |
 * | [PAUSE] / [RESUME] | `arg2` = [JobYield.code] | background jobs only |
 */
object LibraryJobProtocol {
    const val HELLO = 1
    const val START = 2
    const val CANCEL = 3
    const val PAUSE = 4
    const val RESUME = 5

    const val HELLO_REPLY = 101
    const val PROGRESS = 102
    /** The scan's pass-1 stats, before the index (two-stage report, §2.3). */
    const val STATS = 103
    const val RESULT = 104
    const val CANCELLED = 105
    const val FAILED = 106
    /** The service started cancelling by itself (notification, timeout, the UI's death) — a kill may follow. */
    const val CANCELLING = 107

    const val KEY_PID = "pid"
    const val KEY_HEAP_MAX = "heapMax"
    const val KEY_JOB = "job"
    const val KEY_IN = "in"
    const val KEY_OUT = "out"
    const val KEY_VERIFY = "verify"
    const val KEY_PHASE = "phase"
    const val KEY_FRACTION = "fraction"
    const val KEY_RECORDS = "records"
    const val KEY_JSON = "json"
    const val KEY_MESSAGE = "message"
    const val KEY_OOM = "oom"

    /** How long a cancelled job may take to stop before its process is killed (web: 1.5 s, then `terminate()`). */
    const val KILL_AFTER_MS = 1500L

    const val PROCESS_SUFFIX = ":library"

    fun putFds(b: Bundle, key: String, fds: List<ParcelFileDescriptor>) {
        b.putParcelableArray(key, fds.toTypedArray())
    }

    fun fds(b: Bundle, key: String): List<ParcelFileDescriptor> =
        BundleCompat.getParcelableArray(b, key, ParcelFileDescriptor::class.java)
            ?.map { it as ParcelFileDescriptor }.orEmpty()

    /** Temp files of jobs (sorted runs) — `cacheDir/library/spill/`, swept before and after each job and at app start. */
    fun spillRoot(context: Context) = File(context.cacheDir, "library/spill")

    fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }
                .getOrDefault(context.packageName)
        }

    fun isLibraryProcess(context: Context) = processName(context).endsWith(PROCESS_SUFFIX)

    /** Is a `:library` process of ours alive now? (`runningAppProcesses` lists our own processes.) */
    fun libraryProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.runningAppProcesses.orEmpty().any { it.processName.endsWith(PROCESS_SUFFIX) }
    }

    /**
     * App start, UI process: temp files a killed job left. Skipped while a
     * `:library` process lives — it may be in the middle of a job.
     */
    fun sweepStale(context: Context) {
        if (libraryProcessAlive(context)) return
        spillRoot(context).deleteRecursively()
    }
}
