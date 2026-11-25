package dev.gomoku.zenyth.data.library.job

import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.FileSpill
import dev.gomoku.zenyth.domain.library.yxdb.IndexSkipped
import dev.gomoku.zenyth.domain.library.yxdb.JobProgress
import dev.gomoku.zenyth.domain.library.yxdb.MergeOptions
import dev.gomoku.zenyth.domain.library.yxdb.MergeOut
import dev.gomoku.zenyth.domain.library.yxdb.MergeSource
import dev.gomoku.zenyth.domain.library.yxdb.MergeSummary
import dev.gomoku.zenyth.domain.library.yxdb.Yxdb
import dev.gomoku.zenyth.domain.library.yxdb.YxdbCancelled
import dev.gomoku.zenyth.domain.library.yxdb.YxdbIndex
import dev.gomoku.zenyth.domain.library.yxdb.YxdbInput
import dev.gomoku.zenyth.domain.library.yxdb.YxdbMerge
import dev.gomoku.zenyth.domain.library.yxdb.YxdbSplit
import dev.gomoku.zenyth.domain.library.yxdb.YxdbUnsorted
import java.io.File
import java.io.OutputStream

/**
 * A job's bytes, by slot ([LibraryJob]). On the device these wrap the
 * descriptors the UI process sent; in tests, files.
 */
interface JobIo {
    /** Re-openable: every `open` starts from byte 0 (one open at a time per input). */
    fun input(i: Int): YxdbInput

    /** Written once; closing it syncs to storage. */
    fun output(i: Int): OutputStream

    /** The same document as output [i], opened for reading — the read-back check. */
    fun verify(i: Int): YxdbInput

    /** Empty, the job's own — sorted runs of unordered merge inputs go here. */
    val spillDir: File
}

/**
 * Runs one [LibraryJob] on the codec (pure JVM — the service only adds the
 * descriptors and the messages). Throws [YxdbCancelled] when cancelled;
 * `OutOfMemoryError` and failures pass through for the service to report.
 */
class LibraryJobRunner(
    private val io: JobIo,
    private val control: JobControl,
    private val onProgress: (JobProgress) -> Unit = {},
    private val onStats: (DbStats) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {
    private var lastPost = Long.MIN_VALUE / 2

    /** 10 Hz, and always the end of a phase. */
    private fun progress(phase: String, fraction: Double, records: Long = -1) {
        val now = clock()
        if (fraction < 1 && now - lastPost < 100_000_000L) return
        lastPost = now
        onProgress(JobProgress(phase, fraction.coerceIn(0.0, 1.0), records))
    }

    private val isCancelled: () -> Boolean = { control.check() }

    fun run(job: LibraryJob): JobResult = when (job) {
        is LibraryJob.Scan -> scan(job)
        is LibraryJob.MergeCount -> {
            val (summary, unsorted) = merge(sources(job.sizes, job.ranks), job.policy, job.joinComments, emptyList(), null)
            merged(summary, unsorted)
        }
        is LibraryJob.MergeWrite -> mergeWrite(job)
        is LibraryJob.SplitCount -> splitCount(job)
        is LibraryJob.SplitWrite -> splitWrite(job)
        is LibraryJob.Copy -> copy(job)
    }

    // ── scan ───────────────────────────────────────────────────────────

    private fun scan(job: LibraryJob.Scan): JobResult {
        val input = io.input(0)
        job.expect?.let { e ->
            val fp = input.open().use { Fingerprint.read(it, input.size, 0) }
            if (fp.size != e.size || fp.tail != e.tail) return JobResult.Changed(fp.size, fp.tail)
        }
        val r = YxdbIndex.scan(
            input.open, input.size,
            index = job.index,
            maxIndexBytes = job.maxIndexBytes,
            rootsTop = job.rootsTop,
            onProgress = { p -> progress(p.phase.name.lowercase(), p.fraction, p.records) },
            isCancelled = isCancelled,
            onStats = onStats,
            dropIndex = { control.dropIndex },
        )
        return JobResult.Scanned(
            stats = r.stats,
            count = r.count,
            compressed = r.compressed,
            sorted = r.sorted,
            duplicates = r.duplicates,
            countOrdered = r.countOrdered,
            special = r.special,
            indexSkipped = r.indexSkipped?.name,
            trimmed = job.index && r.indexSkipped == IndexSkipped.MEMORY && control.dropIndex,
            openingsUnordered = r.openingsUnordered,
            totalMs = r.totalMs,
        )
    }

    // ── merge ──────────────────────────────────────────────────────────

    private fun sources(sizes: List<Long>, ranks: List<Int>): List<MergeSource> {
        require(ranks.size == sizes.size) { "merge: ${ranks.size} ranks for ${sizes.size} inputs" }
        return sizes.indices.map { MergeSource(io.input(it), ranks[it]) }
    }

    /** An unordered input becomes sorted runs under its rank (web: the caller replaces it and calls again). */
    private fun sortRank(src: List<MergeSource>, rank: Int): List<MergeSource> {
        val victim = src.first { it.rank == rank }
        val runs = YxdbMerge.sortedRuns(
            victim.input,
            FileSpill.factory(File(io.spillDir, "rank-$rank")),
            isCancelled = isCancelled,
            onProgress = { f -> progress("sort", f) },
        )
        return src.filter { it !== victim } + runs.map { MergeSource(it, rank) }
    }

    /**
     * Merges, sorting [known] unordered ranks first. Without [out] (a preview),
     * a rank found unordered on the way is sorted and the merge starts over;
     * with it, bytes may already be out, so that fails.
     */
    private fun merge(
        sources: List<MergeSource>,
        policy: dev.gomoku.zenyth.domain.library.yxdb.MergePolicy,
        joinComments: Boolean,
        known: List<Int>,
        out: MergeOut?,
    ): Pair<MergeSummary, List<Int>> {
        var src = sources
        val sorted = ArrayList<Int>()
        for (rank in known) {
            src = sortRank(src, rank)
            sorted += rank
        }
        val opts = MergeOptions(policy, joinComments, isCancelled) { p -> progress(p.phase, p.fraction, p.records) }
        while (true) {
            try {
                return YxdbMerge.merge(src, opts, out) to sorted
            } catch (e: YxdbUnsorted) {
                if (out != null || e.rank in sorted) throw e
                src = sortRank(src, e.rank)
                sorted += e.rank
            }
        }
    }

    private fun merged(s: MergeSummary, unsorted: List<Int>, bytes: Long? = null, verified: Long? = null) =
        JobResult.Merged(s.records, s.positions, s.inputPositions, s.overlaps, s.differing, unsorted, bytes, verified)

    private fun mergeWrite(job: LibraryJob.MergeWrite): JobResult {
        var bytes = 0L
        val (summary, unsorted) = io.output(0).use { out ->
            merge(
                sources(job.sizes, job.ranks), job.policy, job.joinComments, job.unsorted,
                MergeOut(job.count, { b, off, len -> out.write(b, off, len); bytes += len }),
            )
        }
        val verified = if (job.verify) readBack(io.verify(0), summary.records) else null
        return merged(summary, unsorted, bytes, verified)
    }

    // ── split ──────────────────────────────────────────────────────────

    private fun splitCount(job: LibraryJob.SplitCount): JobResult {
        val c = YxdbSplit.count(
            io.input(0), job.base?.toBase(),
            withOpenings = job.withOpenings,
            maxIndexBytes = job.maxIndexBytes,
            isCancelled = isCancelled,
            onProgress = { p -> progress(p.phase, p.fraction, p.records) },
        )
        return JobResult.SplitCounted(c.total, c.meta, c.openings, c.openingsSkipped?.name, c.under, c.solved, c.rules)
    }

    private fun splitWrite(job: LibraryJob.SplitWrite): JobResult {
        val n = job.outputs
        val outs = ArrayList<OutputStream>(n)
        val bytes = LongArray(n)
        val counts = try {
            for (i in 0 until n) outs += io.output(i)
            YxdbSplit.run(
                io.input(0), job.plan(),
                List(n) { i -> { b: ByteArray, off: Int, len: Int -> outs[i].write(b, off, len); bytes[i] += len.toLong() } },
                meta = job.meta,
                maxIndexBytes = job.maxIndexBytes,
                isCancelled = isCancelled,
                onProgress = { p -> progress(p.phase, p.fraction, p.records) },
            )
        } finally {
            var first: Throwable? = null
            for (o in outs) {
                try {
                    o.close()
                } catch (t: Throwable) {
                    if (first == null) first = t
                }
            }
            first?.let { throw it }
        }
        if (job.verify) {
            val extra = if (job.meta) 1 else 0
            for (i in 0 until n) readBack(io.verify(i), counts[i] + extra)
        }
        return JobResult.Split(counts, bytes.toList())
    }

    // ── copy ───────────────────────────────────────────────────────────

    /** Bytes through, keeping the last 4 KB for the identity. */
    private fun copy(job: LibraryJob.Copy): JobResult {
        val input = io.input(0)
        val buf = ByteArray(Yxdb.CHUNK)
        val tail = ByteArray(Fingerprint.TAIL_BYTES)
        var tailPos = 0 // next write position in the ring
        var total = 0L
        input.open().use { s ->
            io.output(0).use { out ->
                while (true) {
                    if (isCancelled()) throw YxdbCancelled()
                    val r = s.read(buf)
                    if (r < 0) break
                    if (r == 0) continue
                    out.write(buf, 0, r)
                    total += r
                    // Ring: only the last TAIL_BYTES of this chunk matter.
                    val from = maxOf(0, r - tail.size)
                    var i = from
                    while (i < r) {
                        val k = minOf(r - i, tail.size - tailPos)
                        System.arraycopy(buf, i, tail, tailPos, k)
                        i += k
                        tailPos = (tailPos + k) % tail.size
                    }
                    progress("copy", if (input.size > 0) total.toDouble() / input.size else 0.0)
                }
            }
        }
        val len = minOf(total, tail.size.toLong()).toInt()
        val last = ByteArray(len)
        // The last `len` bytes end just before tailPos.
        val start = ((tailPos - len) % tail.size + tail.size) % tail.size
        for (j in 0 until len) last[j] = tail[(start + j) % tail.size]
        progress("copy", 1.0)
        return JobResult.Copied(total, Fingerprint.of(total, 0, last).tail)
    }

    // ── read-back ──────────────────────────────────────────────────────

    /** The written file, read whole (LZ4 checksums too): its record count must be [expected]. */
    private fun readBack(input: YxdbInput, expected: Long): Long {
        val r = input.open().use { s ->
            Yxdb.read(
                s, input.size, { _, _, _, _, _, _ -> true },
                verify = true,
                onChunk = { loaded, total -> if (total > 0) progress("verify", loaded.toDouble() / total) },
                isCancelled = isCancelled,
            )
        }
        if (r.count != expected || r.read != r.count) {
            throw IllegalStateException("read-back: header ${r.count}, read ${r.read}, expected $expected")
        }
        return r.count
    }
}
