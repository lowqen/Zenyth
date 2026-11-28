package dev.gomoku.zenyth.data.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.data.library.job.JobControl
import dev.gomoku.zenyth.data.library.job.JobIo
import dev.gomoku.zenyth.data.library.job.JobOutcome
import dev.gomoku.zenyth.data.library.job.LibraryJob
import dev.gomoku.zenyth.data.library.job.LibraryJobRunner
import dev.gomoku.zenyth.data.library.job.SplitOutSpec
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitBuckets
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import dev.gomoku.zenyth.domain.library.yxdb.SplitPlan
import dev.gomoku.zenyth.domain.library.yxdb.Yxdb
import dev.gomoku.zenyth.domain.library.yxdb.YxdbCancelled
import dev.gomoku.zenyth.domain.library.yxdb.YxdbInput
import dev.gomoku.zenyth.domain.library.yxdb.YxdbKey
import dev.gomoku.zenyth.domain.library.yxdb.YxdbMerge
import dev.gomoku.zenyth.domain.library.yxdb.YxdbWriter
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Merge · split between entries (L5-7, [LibraryOps]) on real sources — app
 * storage in a temp folder and a fake document tree — with the job run in
 * place: previews, results as new entries, the move's backup, refusals
 * (journal, changed file, attached), cancel and the all-or-nothing placement.
 */
class LibraryOpsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val okVerifier = ReplaceVerifier { _, _ -> true }

    // ── tiny yixindbs ──────────────────────────────────────────────────

    private fun key(moves: List<Move>) = YxdbKey.keyOfMoves(Yxdb.RULE_RENJU, 15, moves)

    private fun value(label: Char?): ByteArray = ByteArray(5).also {
        if (label != null) it[0] = label.code.toByte()
        it[3] = 3
    }

    /** Every prefix (1 stone and up) of [line]; the full line gets [last]. */
    private fun prefixes(line: List<Move>, last: Char?): List<Pair<ByteArray, ByteArray>> =
        (1..line.size).map { n -> key(line.take(n)) to value(if (n == line.size) last else null) }

    private fun db(recs: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val sorted = recs.distinctBy { YxdbKey.toHex(it.first) }
            .sortedWith { a, b -> Yxdb.compareKeys(a.first, 0, a.first.size, b.first, 0, b.first.size) }
        val (mk, mv) = Yxdb.metaRecord()
        val all = listOf(mk to mv) + sorted
        val out = ByteArrayOutputStream()
        val w = YxdbWriter(all.size.toLong(), { b, o, l -> out.write(b, o, l) }, compress = true, blockKB = 64)
        for ((k, v) in all) w.recordKV(k, 0, k.size, v, 0, v.size)
        w.finish()
        return out.toByteArray()
    }

    private val lineA = listOf(Move(7, 7), Move(8, 6), Move(9, 7), Move(6, 8))
    private val lineB = listOf(Move(7, 7), Move(8, 6), Move(9, 7), Move(10, 6))

    /** 4 positions, the last solved (W). */
    private val dbA by lazy { db(prefixes(lineA, 'W')) }

    /** 4 positions, 3 shared with A, the last solved (L). */
    private val dbB by lazy { db(prefixes(lineB, 'L')) }

    /** A ∪ B: 5 positions, 2 solved. */
    private val dbC by lazy { db(prefixes(lineA, 'W') + prefixes(lineB, 'L')) }

    private fun count(bytes: ByteArray) = YxdbMerge.count(YxdbInput.of(bytes))

    // ── the job, in place ──────────────────────────────────────────────

    private inner class LocalRunner : LibraryOpRunner {
        val jobs = ArrayList<LibraryJob>()
        var cancel = false
        var fail: String? = null

        override suspend fun run(job: LibraryJob, inputs: List<OpFile>, outputs: List<StagedFile>): JobOutcome {
            jobs += job
            if (fail != null) return JobOutcome.Failed(fail!!)
            val control = JobControl()
            if (cancel) control.cancel()
            val io = object : JobIo {
                override fun input(i: Int): YxdbInput {
                    val f = inputs[i]
                    return YxdbInput(f.size) { runBlocking { f.source.openRead(f.name)!! } }
                }
                override fun output(i: Int): OutputStream = outputs[i].openOutput()
                override fun verify(i: Int): YxdbInput = YxdbInput.of(outputs[i].openInput().use { it.readBytes() })
                override val spillDir: File = tmp.newFolder()
            }
            return try {
                JobOutcome.Done(LibraryJobRunner(io, control).run(job))
            } catch (e: YxdbCancelled) {
                JobOutcome.Cancelled
            }
        }
    }

    private val runner = LocalRunner()
    private val folded = ArrayList<String>()
    private var foldAnswer: String? = null
    private val ops = LibraryOps(runner, { id -> folded += id; foldAnswer }, clock = { now })

    private fun app(): AppDirSource = AppDirSource(tmp.newFolder("library"))

    private fun appEntry(app: AppDirSource, title: String, bytes: ByteArray, id: String = LibraryEntries.newId()): LibraryEntry {
        File(app.dir, AppDirSource.fileNameOf(id)).writeBytes(bytes)
        return LibraryEntries.newEntry(title, AppDirSource.fileNameOf(id), now = 1)
            .copy(id = id, cover = LibraryCover(LibraryCover.OPENING, opening = 4, seed = 3))
    }

    private fun folder(tree: FakeDocTree) = TreeSource("saf-1", SourceKind.SAF, "연구DB", tree, verifier = okVerifier, clock = { now })

    private fun <T> done(r: OpResult<T>): T {
        assertThat(r).isInstanceOf(OpResult.Done::class.java)
        return (r as OpResult.Done<T>).value
    }

    // ── merge ──────────────────────────────────────────────────────────

    @Test
    fun `merge counts first, then writes a new entry in the base's source`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "화월", dbA)
        val b = appEntry(app, "포월", dbB)
        val inputs = listOf(OpInput(app, a), OpInput(app, b))

        val prep = done(ops.previewMerge(inputs, MergePolicy.DEEPER, joinComments = true))
        assertThat(prep.summary.positions).isEqualTo(5)
        assertThat(prep.summary.overlaps).isEqualTo(3)
        assertThat(prep.summary.inputPositions).containsExactly(4L, 4L).inOrder()
        assertThat(folded).containsExactly(a.id, b.id).inOrder()

        val made = done(ops.commitMerge(prep, "화월 + 포월", a.cover, app, listOf(a, b)))
        assertThat(made.title).isEqualTo("화월 + 포월")
        assertThat(made.cover).isEqualTo(a.cover)
        assertThat(made.file).isEqualTo("${made.id}.db")
        val bytes = File(app.dir, made.file).readBytes()
        assertThat(count(bytes)).isEqualTo(count(dbC))
        assertThat(made.fingerprint!!.size).isEqualTo(bytes.size.toLong())
        // Originals untouched; no temp file left.
        assertThat(File(app.dir, a.file).readBytes()).isEqualTo(dbA)
        assertThat(File(app.dir, b.file).readBytes()).isEqualTo(dbB)
        assertThat(app.dir.list()!!.filter { it.endsWith(".db") }).hasSize(3)
        assertThat(app.dir.list()!!.none { it.contains("zenyth-tmp") }).isTrue()
        val write = runner.jobs.last() as LibraryJob.MergeWrite
        assertThat(write.ranks).containsExactly(0, 1).inOrder()
        assertThat(write.count).isEqualTo(prep.summary.records)
    }

    @Test
    fun `a file that changed after the preview is refused and nothing is written`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "a", dbA)
        val b = appEntry(app, "b", dbB)
        val prep = done(ops.previewMerge(listOf(OpInput(app, a), OpInput(app, b)), MergePolicy.BASE, false))
        File(app.dir, b.file).writeBytes(dbC)

        val r = ops.commitMerge(prep, "x", a.cover, app, listOf(a, b))
        assertThat(r).isInstanceOf(OpResult.Failed::class.java)
        assertThat(app.dir.list()!!.filter { it.endsWith(".db") }).hasSize(2)
    }

    @Test
    fun `a journal with saves not folded in refuses, an empty one does not`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "a", dbA)
        val b = appEntry(app, "b", dbB)
        File(app.dir, b.file + ".journal").writeBytes(ByteArray(LibraryRepository.JOURNAL_HEADER.toInt()))
        done(ops.previewMerge(listOf(OpInput(app, a), OpInput(app, b)), MergePolicy.DEEPER, true))

        File(app.dir, b.file + ".journal").writeBytes(ByteArray(200))
        val r = ops.previewMerge(listOf(OpInput(app, a), OpInput(app, b)), MergePolicy.DEEPER, true)
        assertThat((r as OpResult.Failed).message).contains("«b»")
    }

    @Test
    fun `a fold that fails stops the op before any job`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "a", dbA)
        val b = appEntry(app, "b", dbB)
        foldAnswer = "save timed out"
        val r = ops.previewMerge(listOf(OpInput(app, a), OpInput(app, b)), MergePolicy.DEEPER, true)
        assertThat((r as OpResult.Failed).message).contains("save timed out")
        assertThat(runner.jobs).isEmpty()
    }

    @Test
    fun `a new DB without a file cannot be merged`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "a", dbA)
        val empty = LibraryEntries.newEntry("빈 것", "nothing.db", now = 1)
        val r = ops.previewMerge(listOf(OpInput(app, a), OpInput(app, empty)), MergePolicy.DEEPER, true)
        assertThat((r as OpResult.Failed).message).contains("«빈 것»")
    }

    @Test
    fun `a cancelled write leaves neither a file nor a temp file`() = runBlocking<Unit> {
        val app = app()
        val a = appEntry(app, "a", dbA)
        val b = appEntry(app, "b", dbB)
        val prep = done(ops.previewMerge(listOf(OpInput(app, a), OpInput(app, b)), MergePolicy.DEEPER, true))
        runner.cancel = true

        assertThat(ops.commitMerge(prep, "x", a.cover, app, listOf(a, b))).isEqualTo(OpResult.Cancelled)
        assertThat(app.dir.list()!!.filter { !it.startsWith(".") }).containsExactly(a.file, b.file)
    }

    @Test
    fun `a merge into a folder names the file from the title past the names taken`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", dbA)
        tree.put("b.db", dbB)
        tree.put("a + b.db", "someone else's")
        val src = folder(tree)
        val a = LibraryEntries.newEntry("a", "a.db", now = 1)
        val b = LibraryEntries.newEntry("b", "b.db", now = 1)
        val prep = done(ops.previewMerge(listOf(OpInput(src, a), OpInput(src, b)), MergePolicy.DEEPER, true))

        val made = done(ops.commitMerge(prep, "a + b", a.cover, src, listOf(a, b)))
        assertThat(made.file).isEqualTo("a + b (2).db")
        assertThat(count(tree.bytes("a + b (2).db")!!)).isEqualTo(count(dbC))
    }

    // ── split ──────────────────────────────────────────────────────────

    private fun solvedPlan(prep: SplitPrepared, move: Boolean): Pair<SplitPlan, List<SplitResultSpec>> {
        val out = SplitOutSpec(names = listOf(SplitBuckets.SOLVED), count = prep.counts.solved)
        val plan = SplitPlan(SplitCriterion.SOLVED, listOf(out.toOutput()), rest = if (move) prep.counts.total - out.count else null)
        return plan to listOf(SplitResultSpec(out, "연구 · 풀린 국면", LibraryCover(LibraryCover.AUTO, seed = 0)))
    }

    @Test
    fun `split by copy puts the results beside the original and keeps it`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("연구.db", dbC)
        val src = folder(tree)
        val e = LibraryEntries.newEntry("연구", "연구.db", now = 1)

        val prep = done(ops.previewSplit(OpInput(src, e), null, Long.MAX_VALUE))
        assertThat(prep.counts.total).isEqualTo(5)
        assertThat(prep.counts.solved).isEqualTo(2)
        val (plan, results) = solvedPlan(prep, move = false)
        val d = done(ops.commitSplit(prep, plan, results, move = false, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = null))

        assertThat(d.replaced).isNull()
        assertThat(d.backup).isNull()
        val made = d.made.single()
        assertThat(made.file).isEqualTo("연구 · 풀린 국면.db")
        val meta = count(dbC) - 5
        assertThat(count(tree.bytes(made.file)!!)).isEqualTo(2 + meta)
        assertThat(tree.bytes("연구.db")).isEqualTo(dbC)
    }

    @Test
    fun `split by move makes the original the rest and backs it up in the trash`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("연구.db", dbC)
        tree.put("연구.db.journal", ByteArray(LibraryRepository.JOURNAL_HEADER.toInt()))
        val src = folder(tree)
        val e = LibraryEntries.newEntry("연구", "연구.db", now = 1).copy(lastOpenedAt = 5)

        val prep = done(ops.previewSplit(OpInput(src, e), null, Long.MAX_VALUE))
        val (plan, results) = solvedPlan(prep, move = true)
        val d = done(ops.commitSplit(prep, plan, results, move = true, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = null))

        val meta = count(dbC) - 5
        // Results + what stays = the original (each file keeps its own metadata record).
        assertThat(count(tree.bytes(d.made.single().file)!!)).isEqualTo(2 + meta)
        assertThat(count(tree.bytes("연구.db")!!)).isEqualTo(3 + meta)
        val replaced = d.replaced!!
        assertThat(replaced.id).isEqualTo(e.id)
        assertThat(replaced.file).isEqualTo("연구.db")
        assertThat(replaced.statsJson).isNull()
        assertThat(replaced.fingerprint!!.size).isEqualTo(tree.bytes("연구.db")!!.size.toLong())
        val backup = d.backup!!
        assertThat(backup.id).isNotEqualTo(e.id)
        assertThat(backup.title).contains("연구")
        assertThat(backup.trashedAt).isEqualTo(now)
        assertThat(tree.bytes(backup.trashFile!!, tree.trashDir())).isEqualTo(dbC)
        // No rest file left under its own name, no temp files.
        assertThat(tree.names()).containsExactly("연구.db", "연구 · 풀린 국면.db")
    }

    @Test
    fun `split by move in app storage gives the backup a file name of its own`() = runBlocking<Unit> {
        val app = app()
        val e = appEntry(app, "연구", dbC)
        val prep = done(ops.previewSplit(OpInput(app, e), null, Long.MAX_VALUE))
        val (plan, results) = solvedPlan(prep, move = true)
        val d = done(ops.commitSplit(prep, plan, results, move = true, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = null))

        assertThat(d.backup!!.file).isEqualTo("${d.backup!!.id}.db")
        assertThat(d.made.single().file).isEqualTo("${d.made.single().id}.db")
        assertThat(app.dir.list()!!.filter { it.endsWith(".db") }).containsExactly(e.file, d.made.single().file)
        // Restoring the backup later puts it under that name — free now.
        assertThat(app.restore(d.backup!!.trashFile!!, d.backup!!.file)).isEqualTo(d.backup!!.file)
        assertThat(File(app.dir, d.backup!!.file).readBytes()).isEqualTo(dbC)
    }

    @Test
    fun `the attached DB splits by copy only`() = runBlocking<Unit> {
        val app = app()
        val e = appEntry(app, "연구", dbC)
        val prep = done(ops.previewSplit(OpInput(app, e), null, Long.MAX_VALUE))
        val (plan, results) = solvedPlan(prep, move = true)
        val r = ops.commitSplit(prep, plan, results, move = true, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = e.id)
        assertThat(r).isInstanceOf(OpResult.Failed::class.java)
        assertThat(File(app.dir, e.file).readBytes()).isEqualTo(dbC)
    }

    @Test
    fun `results placed before a later one failed are taken away again`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("연구.db", dbC)
        val src = folder(tree)
        val e = LibraryEntries.newEntry("연구", "연구.db", now = 1)
        val prep = done(ops.previewSplit(OpInput(src, e), null, Long.MAX_VALUE))
        // Two results — renju rows and solved ones would need two criteria, so take the solved one
        // twice under two names: the first name commits last, and its commit fails.
        val out = SplitOutSpec(names = listOf(SplitBuckets.SOLVED), count = prep.counts.solved)
        val open = SplitOutSpec(names = listOf(SplitBuckets.OPEN), count = prep.counts.total - prep.counts.solved)
        val plan = SplitPlan(SplitCriterion.SOLVED, listOf(out.toOutput(), open.toOutput()))
        val results = listOf(
            SplitResultSpec(out, "풀림", LibraryCover(LibraryCover.AUTO, seed = 0)),
            SplitResultSpec(open, "안 풀림", LibraryCover(LibraryCover.AUTO, seed = 0)),
        )
        tree.failOn = { op, name -> op == "rename" && name == "풀림.db" }

        val r = ops.commitSplit(prep, plan, results, move = false, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = null)
        assertThat(r).isInstanceOf(OpResult.Failed::class.java)
        assertThat(tree.ops).contains("rename 안 풀림.db")
        assertThat(tree.names().filter { !it.contains("zenyth") }).containsExactly("연구.db")
    }

    @Test
    fun `a job that fails is reported and leaves the source as it was`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("연구.db", dbC)
        val src = folder(tree)
        val e = LibraryEntries.newEntry("연구", "연구.db", now = 1)
        val prep = done(ops.previewSplit(OpInput(src, e), null, Long.MAX_VALUE))
        val (plan, results) = solvedPlan(prep, move = true)
        runner.fail = "read-back: header 3, read 2"

        val r = ops.commitSplit(prep, plan, results, move = true, taken = listOf(e), maxIndexBytes = Long.MAX_VALUE, attachedId = null)
        assertThat((r as OpResult.Failed).message).contains("read-back")
        assertThat(tree.names()).containsExactly("연구.db")
        assertThat(tree.bytes("연구.db")).isEqualTo(dbC)
    }

    @Test
    fun `the default merge title joins up to three names`() {
        assertThat(LibraryOps.defaultMergeTitle(listOf("a", "b", "c"))).isEqualTo("a + b + c")
        assertThat(LibraryOps.defaultMergeTitle(listOf("a", "b", "c", "d"))).startsWith("a + b")
    }
}
