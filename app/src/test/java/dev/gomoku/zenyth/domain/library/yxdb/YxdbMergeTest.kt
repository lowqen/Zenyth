package dev.gomoku.zenyth.domain.library.yxdb

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Merge · split (library L5-1) — the web's `yxdbmerge.test.ts` ported: round
 * trips (split, then merge back = the original), overlap policies, sorted
 * runs, cancel, preview counts, symmetry.
 */
class YxdbMergeTest {

    private class Rec(val key: ByteArray, val value: ByteArray)

    private fun v(label: Char?, depth: Int = 0, text: String = ""): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        val b = ByteArray(5 + t.size)
        b[0] = (label?.code ?: 0).toByte()
        val db = (depth shl 2) or 3
        b[3] = db.toByte()
        b[4] = (db shr 8).toByte()
        System.arraycopy(t, 0, b, 5, t.size)
        return b
    }

    private fun sortRecs(recs: List<Rec>) =
        recs.sortedWith { a, b -> Yxdb.compareKeys(a.key, 0, a.key.size, b.key, 0, b.key.size) }

    private fun key(moves: List<Move>) = YxdbKey.keyOfMoves(Yxdb.RULE_RENJU, 15, moves)

    /** Lines from a few openings' representatives — every prefix, deterministic values · comments. Engine order + metadata. */
    private fun corpus(): List<Rec> {
        var s = 7
        fun rnd(n: Int): Int {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            return s % n
        }
        val map = LinkedHashMap<String, Rec>()
        fun add(line: List<Move>) {
            for (i in 0..line.size) {
                val k = key(line.take(i))
                val h = YxdbKey.toHex(k)
                if (h in map) continue
                val r = rnd(6)
                val value = if (r == 0) ByteArray(0) else v("wldx"[r % 4], rnd(20), if (r == 5) "주석 ${map.size}" else "")
                map[h] = Rec(k, value)
            }
        }
        for (op in listOf(0, 3, 4, 13, 17)) {
            repeat(4) {
                val line = Opening26.representative(op).toMutableList()
                val used = line.map { it.x * 32 + it.y }.toMutableSet()
                while (line.size < 9) {
                    val m = Move(4 + rnd(7), 4 + rnd(7))
                    if (!used.add(m.x * 32 + m.y)) continue
                    line.add(m)
                }
                add(line)
            }
        }
        // A parentless position (unclassified)
        add(listOf(Move(1, 1), Move(2, 2), Move(3, 1)))
        val (mk, mv) = Yxdb.metaRecord()
        return listOf(Rec(mk, mv)) + sortRecs(map.values.toList())
    }

    private fun dbOf(recs: List<Rec>, compress: Boolean = true): YxdbInput {
        val out = ByteArrayOutputStream()
        val w = YxdbWriter(recs.size.toLong(), { b, o, l -> out.write(b, o, l) }, compress = compress, blockKB = 64)
        for (r in recs) w.recordKV(r.key, 0, r.key.size, r.value, 0, r.value.size)
        w.finish()
        return YxdbInput.of(out.toByteArray())
    }

    private fun recordsOf(input: YxdbInput): List<String> {
        val out = ArrayList<String>()
        input.open().use { s ->
            Yxdb.read(s, input.size, { b, ko, kl, vo, vl, _ ->
                out.add(YxdbKey.toHex(b, ko, kl) + ":" + YxdbKey.toHex(b, vo, vl)); true
            })
        }
        return out
    }

    private val deeper = MergeOptions(MergePolicy.DEEPER, joinComments = true)

    /** Preview → write, the two streams the screen runs. */
    private fun merge(sources: List<MergeSource>, opts: MergeOptions = deeper): Pair<YxdbInput, MergeSummary> {
        val pre = YxdbMerge.merge(sources, opts)
        val spill = MemorySpill()
        val sum = YxdbMerge.merge(sources, opts, MergeOut(pre.records, { b, o, l -> spill.write(b, o, l) }, blockKB = 64))
        assertThat(sum).isEqualTo(pre)
        return spill.finish() to pre
    }

    private fun src(inputs: List<YxdbInput>) = inputs.mapIndexed { rank, it -> MergeSource(it, rank) }

    private val recs = corpus()
    private val original = dbOf(recs)
    private val total = recs.size - 1L

    // ── merge ──

    @Test
    fun disjointHalvesMergeBackToTheOriginal() {
        val meta = recs[0]
        val a = listOf(meta) + recs.drop(1).filterIndexed { i, _ -> i % 2 == 0 }
        val b = listOf(meta) + recs.drop(1).filterIndexed { i, _ -> i % 2 == 1 }
        val (out, sum) = merge(src(listOf(dbOf(a), dbOf(b, compress = false))))
        assertThat(recordsOf(out)).isEqualTo(recordsOf(original))
        assertThat(sum.overlaps).isEqualTo(0)
        assertThat(sum.positions).isEqualTo(total)
        assertThat(sum.records).isEqualTo(recs.size.toLong())
        assertThat(sum.inputPositions).containsExactly(a.size - 1L, b.size - 1L).inOrder()
        assertThat(YxdbMerge.count(out)).isEqualTo(recs.size.toLong())
    }

    @Test
    fun theSameFileTwiceOverlapsEverywhereWithoutDifferences() {
        val (out, sum) = merge(src(listOf(original, original)))
        assertThat(sum.overlaps).isEqualTo(total)
        assertThat(sum.differing).isEqualTo(0)
        assertThat(recordsOf(out)).isEqualTo(recordsOf(original))
    }

    private val meta = recs[0]
    private val k = recs[5].key
    private val only = recs[6]
    private val oa = dbOf(listOf(meta, Rec(k, v('l', 4, "가"))))
    private val ob = dbOf(sortRecs(listOf(meta, Rec(k, v('w', 8, "나")), only)))

    private fun valueOf(input: YxdbInput): String =
        recordsOf(input).first { it.startsWith(YxdbKey.toHex(k) + ":") }.substringAfter(':')

    @Test
    fun deeperPolicyTakesTheDeeperRecordAndJoinsComments() {
        val (out, sum) = merge(src(listOf(oa, ob)))
        assertThat(sum.overlaps).isEqualTo(1)
        assertThat(sum.differing).isEqualTo(1)
        assertThat(sum.positions).isEqualTo(2)
        assertThat(valueOf(out)).isEqualTo(YxdbKey.toHex(v('w', 8, "가\n나")))
    }

    @Test
    fun basePolicyTakesTheTopInputWithoutJoining() {
        val (out, _) = merge(src(listOf(oa, ob)), MergeOptions(MergePolicy.BASE, joinComments = false))
        assertThat(valueOf(out)).isEqualTo(YxdbKey.toHex(v('l', 4, "가")))
    }

    @Test
    fun equalDepthPrefersSolvedAndEqualCommentsJoinOnce() {
        val x = dbOf(listOf(meta, Rec(k, v('x', 6, "같음"))))
        val y = dbOf(listOf(meta, Rec(k, v('d', 6, "같음"))))
        val (out, _) = merge(src(listOf(x, y)))
        assertThat(valueOf(out)).isEqualTo(YxdbKey.toHex(v('d', 6, "같음")))
    }

    @Test
    fun anEmptySlotAlwaysLosesButOtherCommentsLive() {
        val x = dbOf(listOf(meta, Rec(k, ByteArray(0))))
        val y = dbOf(listOf(meta, Rec(k, v('l', 1, "글"))))
        val (out, sum) = merge(src(listOf(x, y)), MergeOptions(MergePolicy.BASE, joinComments = true))
        assertThat(sum.differing).isEqualTo(0)
        assertThat(valueOf(out)).isEqualTo(YxdbKey.toHex(v('l', 1, "글")))
    }

    @Test
    fun duplicatesWithinAnInputAreOnePosition() {
        val dup = dbOf(listOf(meta, Rec(k, v('l', 2, "하나")), Rec(k, v('w', 5, "둘"))))
        val (out, sum) = merge(src(listOf(dup, oa)))
        assertThat(sum.records).isEqualTo(2)
        assertThat(sum.positions).isEqualTo(1)
        assertThat(sum.overlaps).isEqualTo(1)
        assertThat(sum.inputPositions).containsExactly(1L, 1L).inOrder()
        // Deepest wins; comments in rank order, within a rank as they came.
        assertThat(valueOf(out)).isEqualTo(YxdbKey.toHex(v('w', 5, "하나\n둘\n가")))
        // Alone, too (no other input to tie with).
        val (solo, s1) = merge(src(listOf(dup)))
        assertThat(s1.positions).isEqualTo(1)
        assertThat(s1.overlaps).isEqualTo(0)
        assertThat(valueOf(solo)).isEqualTo(YxdbKey.toHex(v('w', 5, "하나\n둘")))
    }

    @Test
    fun unorderedInputThrowsAndSortedRunsMerge() {
        val bad = dbOf(listOf(recs[0]) + recs.drop(1).reversed())
        val e = assertThrows(YxdbUnsorted::class.java) { YxdbMerge.merge(src(listOf(original, bad)), deeper) }
        assertThat(e.rank).isEqualTo(1)
        val runs = YxdbMerge.sortedRuns(bad, { MemorySpill() }, runBytes = 300)
        assertThat(runs.size).isGreaterThan(2)
        val (out, sum) = merge(runs.map { MergeSource(it, 0) })
        assertThat(sum.overlaps).isEqualTo(0) // runs of one input are not overlaps
        assertThat(recordsOf(out)).isEqualTo(recordsOf(original))
    }

    @Test
    fun sortedRunsSpillToFiles() {
        val dir = kotlin.io.path.createTempDirectory("spill").toFile()
        try {
            val bad = dbOf(listOf(recs[0]) + recs.drop(1).reversed())
            val runs = YxdbMerge.sortedRuns(bad, FileSpill.factory(dir), runBytes = 300)
            assertThat(dir.listFiles()!!.size).isEqualTo(runs.size)
            val (out, _) = merge(runs.map { MergeSource(it, 0) })
            assertThat(recordsOf(out)).isEqualTo(recordsOf(original))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stableOrderKeepsEqualKeysInFileOrder() {
        val keys = intArrayOf(3, 1, 2, 1, 3, 0, 2, 1)
        val order = YxdbMerge.sortedOrder(keys.size) { a, b -> keys[a] - keys[b] }
        assertThat(order.toList()).containsExactly(5, 1, 3, 7, 2, 6, 0, 4).inOrder()
    }

    @Test
    fun cancelStopsTheMerge() {
        assertThrows(YxdbCancelled::class.java) {
            YxdbMerge.merge(src(listOf(original, original)), MergeOptions(MergePolicy.DEEPER, true, isCancelled = { true }))
        }
    }

    @Test
    fun aCountDifferentFromThePreviewStopsTheWrite() {
        for (c in listOf(recs.size - 1L, recs.size + 1L)) {
            assertThrows(YxdbFormatException::class.java) {
                YxdbMerge.merge(src(listOf(original)), deeper, MergeOut(c, { _, _, _ -> }))
            }
        }
    }

    // ── split ──

    private fun split(plan: SplitPlan, meta: Boolean = true): Pair<List<Long>, List<YxdbInput>> {
        val n = plan.outputs.size + (if (plan.rest != null) 1 else 0)
        val spills = List(n) { MemorySpill() }
        val got = YxdbSplit.run(original, plan, spills.map { s -> { b: ByteArray, o: Int, l: Int -> s.write(b, o, l) } }, meta)
        return got to spills.map { it.finish() }
    }

    @Test
    fun previewCountsEveryCriterion() {
        val c = YxdbSplit.count(original, SplitBase(Yxdb.RULE_RENJU, 15, listOf(Move(7, 7)), emptyList()))
        assertThat(c.total).isEqualTo(total)
        assertThat(c.meta).isTrue()
        assertThat(c.openings).hasSize(Opening26.COUNT + 1)
        assertThat(c.openings!!.sum()).isEqualTo(total)
        assertThat(c.openings[Opening26.NONSTD]).isGreaterThan(0)
        assertThat(c.rules).containsExactly("renju-15", total)
        val solved = recs.drop(1).count { it.value.isNotEmpty() && it.value[0].toInt().toChar() in "wld" }
        assertThat(c.solved).isEqualTo(solved.toLong())
        // Under a lone centre black stone = all but the empty board and the far-off positions
        assertThat(c.under!!).isGreaterThan(total / 2)
        assertThat(c.under).isLessThan(total)
    }

    @Test
    fun splitByOpeningThenMergeBackIsTheOriginal() {
        val c = YxdbSplit.count(original, null)
        val buckets = c.openings!!.withIndex().filter { it.value > 0 }
        assertThat(buckets.size).isAtLeast(5)
        val (got, outs) = split(SplitPlan(SplitCriterion.OPENING, buckets.map { SplitOutput(listOf(it.index), it.value) }))
        assertThat(got).isEqualTo(buckets.map { it.value })
        outs.forEachIndexed { i, o -> assertThat(YxdbMerge.count(o)).isEqualTo(buckets[i].value + 1) }
        val (back, _) = merge(src(outs))
        assertThat(recordsOf(back)).isEqualTo(recordsOf(original))
    }

    @Test
    fun underPlusRestSumsToTheOriginalAndMergesBack() {
        val base = SplitBase(Yxdb.RULE_RENJU, 15, listOf(Move(7, 7)), emptyList())
        val c = YxdbSplit.count(original, base, withOpenings = false)
        assertThat(c.openings).isNull()
        val (got, outs) = split(
            SplitPlan(SplitCriterion.UNDER, listOf(SplitOutput(listOf(SplitBuckets.IN), c.under!!)), rest = total - c.under, base = base),
        )
        assertThat(got[0] + got[1]).isEqualTo(total)
        val (back, _) = merge(src(outs))
        assertThat(recordsOf(back)).isEqualTo(recordsOf(original))
    }

    @Test
    fun solvedOnlyAndByRule() {
        val c = YxdbSplit.count(original, null, withOpenings = false)
        val (_, s) = split(SplitPlan(SplitCriterion.SOLVED, listOf(SplitOutput(listOf(SplitBuckets.SOLVED), c.solved))))
        assertThat(YxdbMerge.count(s[0])).isEqualTo(c.solved + 1)
        val (_, r) = split(SplitPlan(SplitCriterion.RULE, listOf(SplitOutput(listOf("renju-15"), total))))
        assertThat(recordsOf(r[0])).isEqualTo(recordsOf(original))
    }

    @Test
    fun aCountDifferentFromThePreviewThrowsAndCancelStops() {
        assertThrows(YxdbFormatException::class.java) {
            split(SplitPlan(SplitCriterion.SOLVED, listOf(SplitOutput(listOf(SplitBuckets.SOLVED), 0))))
        }
        assertThrows(YxdbCancelled::class.java) {
            YxdbSplit.run(original, SplitPlan(SplitCriterion.SOLVED, emptyList()), emptyList(), true, isCancelled = { true })
        }
    }

    @Test
    fun aRotatedBaseIsTheSameUnder() {
        val line = Opening26.representative(4)
        fun base(t: (Move) -> Move) = SplitBase(Yxdb.RULE_RENJU, 15, listOf(t(line[0]), t(line[2])), listOf(t(line[1])))
        val a = YxdbSplit.count(original, base { it }, withOpenings = false)
        val b = YxdbSplit.count(original, base { Move(14 - it.y, it.x) }, withOpenings = false)
        assertThat(a.under!!).isGreaterThan(0)
        assertThat(b.under).isEqualTo(a.under)
    }

    // ── on the web's golden files ──

    private fun resource(name: String): YxdbInput = YxdbInput.of(
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("library/$name")) { "missing library/$name" }.use { it.readBytes() },
    )

    @Test
    fun webFilesSplitByRuleMergeBack() {
        val mixed = resource("mixed.db")
        val c = YxdbSplit.count(mixed, null, withOpenings = false)
        assertThat(c.rules.size).isAtLeast(3)
        val plan = SplitPlan(SplitCriterion.RULE, c.rules.map { (name, n) -> SplitOutput(listOf(name), n) })
        val spills = List(plan.outputs.size) { MemorySpill() }
        val got = YxdbSplit.run(mixed, plan, spills.map { s -> { b: ByteArray, o: Int, l: Int -> s.write(b, o, l) } }, c.meta)
        assertThat(got).isEqualTo(c.rules.values.toList())
        val (back, sum) = merge(src(spills.map { it.finish() }))
        assertThat(sum.overlaps).isEqualTo(0)
        assertThat(recordsOf(back)).isEqualTo(recordsOf(mixed))
        // Itself twice: the same records
        val (twice, _) = merge(src(listOf(mixed, mixed)))
        assertThat(recordsOf(twice)).isEqualTo(recordsOf(mixed))
    }

    @Test
    fun webUnorderedFileGoesThroughRuns() {
        // unordered.db = mixed.db's keys shuffled (values by position, so they differ).
        val un = resource("unordered.db")
        assertThrows(YxdbUnsorted::class.java) { YxdbMerge.merge(src(listOf(un)), deeper) }
        val runs = YxdbMerge.sortedRuns(un, { MemorySpill() }, runBytes = 2000)
        assertThat(runs.size).isGreaterThan(2)
        val (out, sum) = merge(runs.map { MergeSource(it, 0) })
        assertThat(sum.overlaps).isEqualTo(0)
        val keys = { i: YxdbInput -> recordsOf(i).map { it.substringBefore(':') } }
        assertThat(keys(out)).isEqualTo(keys(resource("mixed.db")))
        assertThat(recordsOf(out).toSet()).isEqualTo(recordsOf(un).toSet())
        assertThat(YxdbIndex.scan(out.open, out.size, index = false).sorted).isTrue()
    }
}
