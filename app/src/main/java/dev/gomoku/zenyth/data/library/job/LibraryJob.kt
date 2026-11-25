package dev.gomoku.zenyth.data.library.job

import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitBase
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import dev.gomoku.zenyth.domain.library.yxdb.SplitOutput
import dev.gomoku.zenyth.domain.library.yxdb.SplitPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the `:library` process is asked to do (L5 design §2.2 — the web
 * worker's `job` · `jobProgress` · `result`). A job names no files: its bytes
 * come as descriptors the UI process opened, sent beside the job in three
 * ordered slots — `in`, `out`, `verify` ([JobIo]). The spec travels as JSON
 * in the message, so it is the same text in tests and on the device.
 */
@Serializable
sealed interface LibraryJob {
    val priority: JobPriority

    /** Descriptors this job expects in each slot. */
    val inputs: Int
    val outputs: Int
    val verifies: Int

    /**
     * Stats + index of one file. [expect] = the identity the UI last saw
     * (size + tail): a different file answers [JobResult.Changed] unscanned.
     * [maxIndexBytes] = the memory gate's budget ([MemoryGate]).
     */
    @Serializable
    @SerialName("scan")
    data class Scan(
        val size: Long,
        val index: Boolean = true,
        val maxIndexBytes: Long = Long.MAX_VALUE,
        val rootsTop: Int = 32,
        val expect: ExpectedFile? = null,
        override val priority: JobPriority = JobPriority.BACKGROUND,
    ) : LibraryJob {
        override val inputs get() = 1
        override val outputs get() = 0
        override val verifies get() = 0
    }

    /** Merge preview: totals · overlaps · differing records. Input `i` has rank `i`'s place in [ranks]. */
    @Serializable
    @SerialName("mergeCount")
    data class MergeCount(
        val sizes: List<Long>,
        val ranks: List<Int>,
        val policy: MergePolicy = MergePolicy.DEEPER,
        val joinComments: Boolean = true,
        override val priority: JobPriority = JobPriority.USER,
    ) : LibraryJob {
        override val inputs get() = sizes.size
        override val outputs get() = 0
        override val verifies get() = 0
    }

    /**
     * Merge write: [count] = the preview's `records`, [unsorted] = the ranks the
     * preview found out of key order (sorted into runs before writing — a rank
     * found unsorted only now means the file changed, and the job fails). With
     * [verify], the output is read back (LZ4 checksums too) and counted.
     */
    @Serializable
    @SerialName("mergeWrite")
    data class MergeWrite(
        val sizes: List<Long>,
        val ranks: List<Int>,
        val count: Long,
        val unsorted: List<Int> = emptyList(),
        val policy: MergePolicy = MergePolicy.DEEPER,
        val joinComments: Boolean = true,
        val verify: Boolean = true,
        override val priority: JobPriority = JobPriority.USER,
    ) : LibraryJob {
        override val inputs get() = sizes.size
        override val outputs get() = 1
        override val verifies get() = if (verify) 1 else 0
    }

    /** Split preview — every criterion's bucket counts. */
    @Serializable
    @SerialName("splitCount")
    data class SplitCount(
        val size: Long,
        val base: BaseSpec? = null,
        val withOpenings: Boolean = true,
        val maxIndexBytes: Long = Long.MAX_VALUE,
        override val priority: JobPriority = JobPriority.USER,
    ) : LibraryJob {
        override val inputs get() = 1
        override val outputs get() = 0
        override val verifies get() = 0
    }

    /** Split write: one output per [outputs] entry, then the rest when [rest] is set. */
    @Serializable
    @SerialName("splitWrite")
    data class SplitWrite(
        val size: Long,
        val criterion: SplitCriterion,
        val results: List<SplitOutSpec>,
        val rest: Long? = null,
        val base: BaseSpec? = null,
        val meta: Boolean = true,
        val maxIndexBytes: Long = Long.MAX_VALUE,
        val verify: Boolean = true,
        override val priority: JobPriority = JobPriority.USER,
    ) : LibraryJob {
        override val inputs get() = 1
        override val outputs get() = results.size + (if (rest != null) 1 else 0)
        override val verifies get() = if (verify) outputs else 0

        fun plan() = SplitPlan(criterion, results.map { it.toOutput() }, rest, base?.toBase())
    }

    /** Byte copy (staging · write-back · between sources): size and tail identity computed on the way — not read again. */
    @Serializable
    @SerialName("copy")
    data class Copy(
        val size: Long,
        override val priority: JobPriority = JobPriority.USER,
    ) : LibraryJob {
        override val inputs get() = 1
        override val outputs get() = 1
        override val verifies get() = 0
    }
}

/**
 * [USER] = pressed by the user (merge · split · copy): never pauses.
 * [BACKGROUND] = the automatic stats count: low thread priority, and pauses
 * while the engine searches or the phone is hot ([JobYield]).
 */
@Serializable
enum class JobPriority { USER, BACKGROUND }

@Serializable
data class ExpectedFile(val size: Long, val tail: String)

@Serializable
data class BaseSpec(val rule: Int, val size: Int, val black: List<Int>, val white: List<Int>) {
    fun toBase() = SplitBase(rule, size, black.map { move(it) }, white.map { move(it) })

    private fun move(c: Int) = Move(c shr 8, c and 0xff)

    companion object {
        fun of(b: SplitBase) = BaseSpec(b.rule, b.size, b.black.map { (it.x shl 8) or it.y }, b.white.map { (it.x shl 8) or it.y })
    }
}

/** A split result's buckets: opening numbers and/or names (`solved`, `in`, `renju-15` …). */
@Serializable
data class SplitOutSpec(val openings: List<Int> = emptyList(), val names: List<String> = emptyList(), val count: Long) {
    fun toOutput() = SplitOutput(openings + names, count)

    companion object {
        fun of(o: SplitOutput) = SplitOutSpec(
            o.buckets.filterIsInstance<Int>(),
            o.buckets.filterIsInstance<String>(),
            o.count,
        )
    }
}

/** A job's answer. */
@Serializable
sealed interface JobResult {
    @Serializable
    @SerialName("scanned")
    data class Scanned(
        val stats: DbStats,
        val count: Long,
        val compressed: Boolean,
        val sorted: Boolean,
        val duplicates: Long,
        val countOrdered: Boolean,
        val special: Long,
        /** `MEMORY` · `ALLOC` (web `indexSkipped`), null when indexed or not asked. */
        val indexSkipped: String? = null,
        /** The index was given up for memory pressure in the job process (not the budget). */
        val trimmed: Boolean = false,
        val openingsUnordered: Boolean = false,
        val totalMs: Long = 0,
    ) : JobResult

    /** [LibraryJob.Scan.expect] did not match — the file changed since the UI looked. */
    @Serializable
    @SerialName("changed")
    data class Changed(val size: Long, val tail: String) : JobResult

    @Serializable
    @SerialName("merged")
    data class Merged(
        val records: Long,
        val positions: Long,
        val inputPositions: List<Long>,
        val overlaps: Long,
        val differing: Long,
        /** Ranks that were not in key order (merged through sorted runs) — pass on to [LibraryJob.MergeWrite]. */
        val unsorted: List<Int> = emptyList(),
        /** Write only: bytes written, and the record count read back (null without verify). */
        val bytes: Long? = null,
        val verified: Long? = null,
    ) : JobResult

    @Serializable
    @SerialName("splitCounted")
    data class SplitCounted(
        val total: Long,
        val meta: Boolean,
        val openings: List<Long>? = null,
        /** `MEMORY` · `ALLOC` · `UNORDERED`. */
        val openingsSkipped: String? = null,
        val under: Long? = null,
        val solved: Long,
        val rules: Map<String, Long>,
    ) : JobResult

    @Serializable
    @SerialName("split")
    data class Split(val counts: List<Long>, val bytes: List<Long>) : JobResult

    @Serializable
    @SerialName("copied")
    data class Copied(val bytes: Long, val tail: String) : JobResult
}

object JobJson {
    val json = Json {
        classDiscriminator = "t"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(job: LibraryJob): String = json.encodeToString(LibraryJob.serializer(), job)
    fun decodeJob(s: String): LibraryJob = json.decodeFromString(LibraryJob.serializer(), s)
    fun encode(r: JobResult): String = json.encodeToString(JobResult.serializer(), r)
    fun decodeResult(s: String): JobResult = json.decodeFromString(JobResult.serializer(), s)
    fun encode(s: DbStats): String = json.encodeToString(DbStats.serializer(), s)
    fun decodeStats(s: String): DbStats = json.decodeFromString(DbStats.serializer(), s)
}
