package dev.gomoku.zenyth.data.library.job

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.JobProgress
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitBase
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import dev.gomoku.zenyth.domain.library.yxdb.SplitOutput
import dev.gomoku.zenyth.domain.library.yxdb.YxdbCancelled
import dev.gomoku.zenyth.domain.library.yxdb.YxdbIndex
import dev.gomoku.zenyth.domain.library.yxdb.YxdbInput
import dev.gomoku.zenyth.domain.library.yxdb.YxdbUnsorted
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The job process's work, without the process (L5-3): every job kind on the
 * web-written golden files, the two-stage report, cancel · pause · index drop,
 * read-back, and the job/result JSON that crosses the process boundary.
 */
class LibraryJobRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("library/$name")) { "missing library/$name" }
            .use { it.readBytes() }

    private fun file(name: String): File = tmp.newFile(name).also { it.writeBytes(resource(name)) }

    private fun golden(name: String): DbStats {
        val j = Json { ignoreUnknownKeys = true }
        val o = j.parseToJsonElement(resource("$name.json").decodeToString()).jsonObject
        return j.decodeFromJsonElement(DbStats.serializer(), o.getValue("stats"))
    }

    private inner class FileIo(
        private val ins: List<File>,
        private val outs: List<File> = emptyList(),
        private val verifies: List<File> = outs,
    ) : JobIo {
        override fun input(i: Int) = YxdbInput.of(ins[i])
        override fun output(i: Int): OutputStream = FileOutputStream(outs[i])
        override fun verify(i: Int) = YxdbInput.of(verifies[i])
        override val spillDir: File = tmp.newFolder()
    }

    private fun runner(
        io: JobIo,
        control: JobControl = JobControl(),
        progress: MutableList<JobProgress> = ArrayList(),
        stats: MutableList<DbStats> = ArrayList(),
    ) = LibraryJobRunner(io, control, { progress += it }, { stats += it })

    // ── scan ───────────────────────────────────────────────────────────

    @Test
    fun scanGivesTheWebStatsAndReportsPassOneFirst() {
        val db = file("mixed.db")
        val progress = ArrayList<JobProgress>()
        val early = ArrayList<DbStats>()
        val r = runner(FileIo(listOf(db)), progress = progress, stats = early).run(LibraryJob.Scan(db.length()))

        val want = golden("mixed")
        assertThat(r).isInstanceOf(JobResult.Scanned::class.java)
        r as JobResult.Scanned
        assertThat(r.stats).isEqualTo(want)
        assertThat(r.indexSkipped).isNull()
        assertThat(r.trimmed).isFalse()
        // Stage one: the same numbers without openings · roots, before the result.
        assertThat(early).containsExactly(want.copy(openings = null, roots = null))
        assertThat(progress.last().fraction).isEqualTo(1.0)
    }

    @Test
    fun aFileOtherThanExpectedIsNotScanned() {
        val db = file("mixed.db")
        val fp = FileInputStream(db).use { Fingerprint.read(it, db.length(), 0) }
        val same = runner(FileIo(listOf(db))).run(LibraryJob.Scan(db.length(), expect = ExpectedFile(fp.size, fp.tail)))
        assertThat(same).isInstanceOf(JobResult.Scanned::class.java)

        val other = runner(FileIo(listOf(db))).run(LibraryJob.Scan(db.length(), expect = ExpectedFile(fp.size, "0".repeat(16))))
        assertThat(other).isEqualTo(JobResult.Changed(fp.size, fp.tail))
    }

    @Test
    fun theBudgetSkipsTheIndex() {
        val db = file("mixed.db")
        val r = runner(FileIo(listOf(db))).run(LibraryJob.Scan(db.length(), maxIndexBytes = 1)) as JobResult.Scanned
        assertThat(r.stats).isEqualTo(golden("mixed").copy(openings = null, roots = null))
        assertThat(r.indexSkipped).isEqualTo("MEMORY")
        assertThat(r.trimmed).isFalse()
    }

    @Test
    fun memoryPressureDropsTheIndexAndKeepsPassOne() {
        val db = file("mixed.db")
        val control = JobControl()
        val early = ArrayList<DbStats>()
        // The pressure arrives right after pass 1 (as onTrimMemory would, from another thread).
        val io = FileIo(listOf(db))
        val r = LibraryJobRunner(io, control, onStats = { early += it; control.dropIndex = true })
            .run(LibraryJob.Scan(db.length())) as JobResult.Scanned
        assertThat(r.stats).isEqualTo(golden("mixed").copy(openings = null, roots = null))
        assertThat(r.indexSkipped).isEqualTo("MEMORY")
        assertThat(r.trimmed).isTrue()
        assertThat(early).hasSize(1)
    }

    @Test
    fun dropDuringPassTwoUnwindsToPassOneStats() {
        val db = file("orphans.db")
        var checks = 0
        // Checked before the sort, after it, then per chunk of pass 2 — drop at pass 2's first chunk.
        val r = YxdbIndex.scan(
            { FileInputStream(db) }, db.length(),
            dropIndex = { checks++ >= 2 },
        )
        assertThat(checks).isEqualTo(3)
        assertThat(r.indexSkipped?.name).isEqualTo("MEMORY")
        assertThat(r.stats).isEqualTo(golden("orphans").copy(openings = null, roots = null))
    }

    @Test
    fun cancelStopsAJob() {
        val db = file("mixed.db")
        val control = JobControl().apply { cancel() }
        assertThrows(YxdbCancelled::class.java) { runner(FileIo(listOf(db)), control).run(LibraryJob.Scan(db.length())) }
    }

    @Test
    fun pauseHoldsTheJobUntilResumedAndCancelWakesIt() {
        val db = file("mixed.db")
        fun start(control: JobControl): Pair<Thread, Array<Any?>> {
            val out = arrayOfNulls<Any?>(1)
            val t = Thread {
                out[0] = try {
                    runner(FileIo(listOf(db)), control).run(LibraryJob.Scan(db.length()))
                } catch (e: Throwable) {
                    e
                }
            }
            t.start()
            return t to out
        }

        val resumed = JobControl().apply { pause(true) }
        val (t1, out1) = start(resumed)
        t1.join(300)
        assertThat(t1.isAlive).isTrue() // held at the first chunk
        resumed.pause(false)
        t1.join(10_000)
        assertThat(out1[0]).isInstanceOf(JobResult.Scanned::class.java)

        val cancelled = JobControl().apply { pause(true) }
        val (t2, out2) = start(cancelled)
        t2.join(300)
        assertThat(t2.isAlive).isTrue()
        cancelled.cancel()
        t2.join(10_000)
        assertThat(out2[0]).isInstanceOf(YxdbCancelled::class.java)
    }

    @Test
    fun checkDoesNotBlockWhenNotPaused() {
        val c = JobControl()
        val done = CountDownLatch(1)
        Thread { c.check(); done.countDown() }.start()
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue()
    }

    // ── merge ──────────────────────────────────────────────────────────

    @Test
    fun mergeTheSameFileTwiceThenWriteAndReadBack() {
        val a = file("mixed.db")
        val b = tmp.newFile("b.db").also { a.copyTo(it, overwrite = true) }
        val positions = golden("mixed").records

        val preview = runner(FileIo(listOf(a, b))).run(
            LibraryJob.MergeCount(listOf(a.length(), b.length()), listOf(0, 1)),
        ) as JobResult.Merged
        assertThat(preview.positions).isEqualTo(positions)
        assertThat(preview.overlaps).isEqualTo(positions)
        assertThat(preview.differing).isEqualTo(0)
        assertThat(preview.unsorted).isEmpty()

        val out = tmp.newFile("out.db")
        val written = runner(FileIo(listOf(a, b), listOf(out))).run(
            LibraryJob.MergeWrite(listOf(a.length(), b.length()), listOf(0, 1), preview.records),
        ) as JobResult.Merged
        assertThat(written.verified).isEqualTo(preview.records)
        assertThat(written.bytes).isEqualTo(out.length())

        val again = runner(FileIo(listOf(out))).run(LibraryJob.Scan(out.length())) as JobResult.Scanned
        assertThat(again.stats.records).isEqualTo(positions)
    }

    @Test
    fun anUnorderedInputIsSortedIntoRunsAndTheWriteNeedsToKnow() {
        val u = file("unordered.db")
        val io = FileIo(listOf(u))
        val preview = runner(io).run(LibraryJob.MergeCount(listOf(u.length()), listOf(0))) as JobResult.Merged
        assertThat(preview.unsorted).containsExactly(0)
        assertThat(io.spillDir.walk().filter { it.isFile }.count()).isGreaterThan(0)

        val out = tmp.newFile("sorted.db")
        val w = runner(FileIo(listOf(u), listOf(out))).run(
            LibraryJob.MergeWrite(listOf(u.length()), listOf(0), preview.records, unsorted = preview.unsorted),
        ) as JobResult.Merged
        assertThat(w.verified).isEqualTo(preview.records)
        assertThat(w.positions).isEqualTo(preview.positions)

        // Not told: the write cannot restart once bytes are out.
        assertThrows(YxdbUnsorted::class.java) {
            runner(FileIo(listOf(u), listOf(tmp.newFile("x.db")))).run(
                LibraryJob.MergeWrite(listOf(u.length()), listOf(0), preview.records),
            )
        }
    }

    @Test
    fun readBackOfAnotherFileFails() {
        val a = file("mixed.db")
        val preview = runner(FileIo(listOf(a))).run(LibraryJob.MergeCount(listOf(a.length()), listOf(0))) as JobResult.Merged
        val wrong = file("orphans.db")
        assertThrows(IllegalStateException::class.java) {
            runner(FileIo(listOf(a), listOf(tmp.newFile("o.db")), verifies = listOf(wrong))).run(
                LibraryJob.MergeWrite(listOf(a.length()), listOf(0), preview.records),
            )
        }
    }

    // ── split ──────────────────────────────────────────────────────────

    @Test
    fun splitByRuleCountsThenWritesEveryRuleAndReadsBack() {
        val db = file("mixed.db")
        val counted = runner(FileIo(listOf(db))).run(LibraryJob.SplitCount(db.length())) as JobResult.SplitCounted
        val want = golden("mixed")
        assertThat(counted.rules).isEqualTo(want.rules)
        assertThat(counted.total).isEqualTo(want.records)

        val names = counted.rules.keys.toList()
        val outs = names.map { tmp.newFile("$it.db") }
        val r = runner(FileIo(listOf(db), outs)).run(
            LibraryJob.SplitWrite(
                db.length(), SplitCriterion.RULE,
                names.map { SplitOutSpec(names = listOf(it), count = counted.rules.getValue(it)) },
                meta = counted.meta,
            ),
        ) as JobResult.Split
        assertThat(r.counts).isEqualTo(names.map { counted.rules.getValue(it) })
        assertThat(r.bytes).isEqualTo(outs.map { it.length() })
        for ((i, f) in outs.withIndex()) {
            val s = runner(FileIo(listOf(f))).run(LibraryJob.Scan(f.length(), index = false)) as JobResult.Scanned
            assertThat(s.stats.records).isEqualTo(r.counts[i])
        }
    }

    // ── copy ───────────────────────────────────────────────────────────

    @Test
    fun copyKeepsTheBytesAndComputesTheTailOnTheWay() {
        for (n in listOf(0, 100, 4096, 4097, 262_144 + 10, 600_001)) {
            val src = tmp.newFile("src-$n").also { it.writeBytes(Random(n).nextBytes(n)) }
            val dst = tmp.newFile("dst-$n")
            val r = runner(FileIo(listOf(src), listOf(dst))).run(LibraryJob.Copy(src.length())) as JobResult.Copied
            assertThat(dst.readBytes()).isEqualTo(src.readBytes())
            val fp = FileInputStream(src).use { Fingerprint.read(it, src.length(), 0) }
            assertThat(r).isEqualTo(JobResult.Copied(n.toLong(), fp.tail))
        }
    }

    // ── what crosses the process boundary ─────────────────────────────

    @Test
    fun jobsAndResultsRoundTripAsJson() {
        val jobs = listOf(
            LibraryJob.Scan(123, maxIndexBytes = 456, expect = ExpectedFile(123, "00ff00ff00ff00ff")),
            LibraryJob.MergeCount(listOf(1, 2), listOf(1, 0), MergePolicy.BASE, joinComments = false),
            LibraryJob.MergeWrite(listOf(1, 2), listOf(0, 1), count = 9, unsorted = listOf(1)),
            LibraryJob.SplitCount(5, BaseSpec.of(SplitBase(2, 15, listOf(Move(7, 7)), listOf(Move(8, 6)))), withOpenings = false),
            LibraryJob.SplitWrite(
                5, SplitCriterion.OPENING,
                listOf(SplitOutSpec.of(SplitOutput(listOf(0, 3), 7)), SplitOutSpec.of(SplitOutput(listOf("solved"), 2))),
                rest = 4,
            ),
            LibraryJob.Copy(77),
        )
        for (j in jobs) assertThat(JobJson.decodeJob(JobJson.encode(j))).isEqualTo(j)

        val stats = golden("mixed")
        val results = listOf(
            JobResult.Scanned(stats, 486, true, true, 0, true, 0, "MEMORY", trimmed = true),
            JobResult.Changed(1, "x"),
            JobResult.Merged(3, 2, listOf(2, 2), 2, 0, listOf(0), 99, 3),
            JobResult.SplitCounted(10, true, null, "UNORDERED", 4, 3, linkedMapOf("renju-15" to 6L, "freestyle-15" to 4L)),
            JobResult.Split(listOf(1, 2), listOf(10, 20)),
            JobResult.Copied(5, "abc"),
        )
        for (r in results) assertThat(JobJson.decodeResult(JobJson.encode(r))).isEqualTo(r)
        // The sidecar cares about rule order.
        val back = JobJson.decodeResult(JobJson.encode(results[0])) as JobResult.Scanned
        assertThat(back.stats.rules.keys.toList()).isEqualTo(stats.rules.keys.toList())
    }

    @Test
    fun splitSpecsKeepTheirBuckets() {
        val base = SplitBase(2, 15, listOf(Move(7, 7), Move(9, 9)), listOf(Move(8, 6)))
        assertThat(BaseSpec.of(base).toBase()).isEqualTo(base)
        val o = SplitOutput(listOf(1, 26, "in"), 5)
        assertThat(SplitOutSpec.of(o).toOutput()).isEqualTo(o)
    }

    @Test
    fun descriptorCountsFollowTheJob() {
        val w = LibraryJob.SplitWrite(1, SplitCriterion.SOLVED, listOf(SplitOutSpec(names = listOf("solved"), count = 1)), rest = 2)
        assertThat(listOf(w.inputs, w.outputs, w.verifies)).containsExactly(1, 2, 2).inOrder()
        val m = LibraryJob.MergeWrite(listOf(1, 2, 3), listOf(0, 1, 2), 5, verify = false)
        assertThat(listOf(m.inputs, m.outputs, m.verifies)).containsExactly(3, 1, 0).inOrder()
    }
}
