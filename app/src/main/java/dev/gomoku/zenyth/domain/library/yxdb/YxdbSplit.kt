package dev.gomoku.zenyth.domain.library.yxdb

import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26

/**
 * yixindb split (Zenyth-web `yxdbsplit.ts`; library plan §7 · L3, L5 design
 * §2.1) — each record gets a bucket, and the chosen buckets stream into new files.
 *
 * | criterion | buckets | needs |
 * |---|---|---|
 * | [SplitCriterion.OPENING] | `0..25` (Int), `26` unclassified | the index (pass 2's `onClassified`) |
 * | [SplitCriterion.UNDER] | [SplitBuckets.IN] · [SplitBuckets.OUT] | a board position — same rule · board, black ⊇ · white ⊇ (one of 8 symmetries) |
 * | [SplitCriterion.SOLVED] | [SplitBuckets.SOLVED] · [SplitBuckets.OPEN] | label W/L/D |
 * | [SplitCriterion.RULE] | `renju-15` … | the key head |
 *
 * The header count comes first, so counting ([count], the preview) and
 * writing ([run]) are separate. The metadata record goes first into every
 * result (not in the counts, in the header). Records keep the source order,
 * so results are in key order too.
 */
enum class SplitCriterion { OPENING, UNDER, SOLVED, RULE }

object SplitBuckets {
    const val SOLVED = "solved"
    const val OPEN = "open"
    const val IN = "in"
    const val OUT = "out"
}

/** The «under this position» base — board size · rule · black · white (key frame, square board). */
data class SplitBase(val rule: Int, val size: Int, val black: List<Move>, val white: List<Move>)

enum class OpeningsSkipped { MEMORY, ALLOC, UNORDERED }

data class SplitCounts(
    /** Records without metadata — the check for the results' total. */
    val total: Long,
    /** A metadata record was there (each result gets a copy). */
    val meta: Boolean,
    /** Per opening (27, 26 = unclassified). Null without an index or when not in stone-count order. */
    val openings: List<Long>?,
    /** Why openings were not counted. */
    val openingsSkipped: OpeningsSkipped?,
    /** Under the base position (itself included). Null without a base. */
    val under: Long?,
    /** Positions solved W/L/D. */
    val solved: Long,
    /** Per rule · board, first-seen order. */
    val rules: Map<String, Long>,
)

/** One result: its buckets (Int openings or String names — results never share one) and the previewed count without metadata. */
data class SplitOutput(val buckets: List<Any>, val count: Long)

data class SplitPlan(
    val criterion: SplitCriterion,
    val outputs: List<SplitOutput>,
    /** Records in no result go to one more file (a move — the source is replaced by it). Its count. */
    val rest: Long? = null,
    val base: SplitBase? = null,
)

/**
 * Under the base position — one of the 8 symmetries puts the base's black and
 * white stones all on this key's stones of the same colour. Reuses one board (1024 cells).
 */
class UnderTest(private val base: SplitBase) {
    private val board = ByteArray(YxdbKey.CELLS)
    /** Per symmetry, the base's cells `(x shl 5) or y` — black, then white. */
    private val sets: Array<IntArray>
    private val nb = base.black.size

    init {
        val s = base.size - 1
        val stones = base.black + base.white
        sets = Array(8) { t -> IntArray(stones.size) { i -> YxdbKey.transformCell(t, stones[i].x, stones[i].y, s) } }
    }

    fun test(b: ByteArray, ko: Int, kl: Int): Boolean {
        if (kl < 3 || (kl - 3) and 1 != 0) return false
        val w = b[ko + 1].toInt() and 0xff
        if ((b[ko].toInt() and 0xff) != base.rule || w != base.size || (b[ko + 2].toInt() and 0xff) != w) return false
        val n = (kl - 3) shr 1
        val keyNb = (n + 1) shr 1
        val total = sets[0].size
        if (n < total || keyNb < nb || n - keyNb < total - nb) return false
        val bd = board
        var p = ko + 3
        for (i in 0 until n) {
            val x = b[p].toInt() and 0xff
            val y = b[p + 1].toInt() and 0xff
            if (x >= w || y >= w) {
                clear(b, ko, i)
                return false // a pass slot
            }
            bd[(x shl 5) or y] = if (i < keyNb) 1 else 2
            p += 2
        }
        var ok = false
        var t = 0
        while (t < 8 && !ok) {
            val cells = sets[t]
            ok = true
            for (i in cells.indices) {
                if (bd[cells[i]].toInt() != (if (i < nb) 1 else 2)) {
                    ok = false
                    break
                }
            }
            t++
        }
        clear(b, ko, n)
        return ok
    }

    private fun clear(b: ByteArray, ko: Int, upto: Int) {
        var p = ko + 3
        for (i in 0 until upto) {
            board[((b[p].toInt() and 0xff) shl 5) or (b[p + 1].toInt() and 0xff)] = 0
            p += 2
        }
    }
}

object YxdbSplit {
    private fun isSolved(l: Int) = l == 0x77 || l == 0x6c || l == 0x64 || l == 0x57 || l == 0x4c || l == 0x44

    private fun ruleKey(b: ByteArray, ko: Int): Int =
        (b[ko].toInt() and 0xff) or ((b[ko + 1].toInt() and 0xff) shl 8) or ((b[ko + 2].toInt() and 0xff) shl 16)

    private fun ruleName(rk: Int): String = Yxdb.ruleBoardName(rk and 0xff, (rk ushr 8) and 0xff, (rk ushr 16) and 0xff)

    /** Counts every criterion's buckets in one go (the preview). */
    private class Tally(base: SplitBase?) {
        val under = base?.let { UnderTest(it) }
        var total = 0L
        var meta = false
        var solved = 0L
        var underN = 0L
        private val ruleCount = LinkedHashMap<Int, Long>() // first-seen order, like the web's object
        private var curRk = -1
        private var curCount = 0L

        /** False for the metadata record. */
        fun add(b: ByteArray, ko: Int, kl: Int, vo: Int, vl: Int): Boolean {
            if (Yxdb.isMetaKey(b, ko, kl)) {
                meta = true
                return false
            }
            total++
            if (isSolved(Yxdb.valueLabel(b, vo, vl))) solved++
            if (under?.test(b, ko, kl) == true) underN++
            val rk = ruleKey(b, ko)
            if (rk != curRk) {
                flushRule()
                curRk = rk
            }
            curCount++
            return true
        }

        private fun flushRule() {
            if (curRk >= 0) ruleCount[curRk] = (ruleCount[curRk] ?: 0L) + curCount
            curCount = 0
        }

        fun rules(): Map<String, Long> {
            flushRule()
            curRk = -1
            val out = LinkedHashMap<String, Long>()
            for ((k, v) in ruleCount) out[ruleName(k)] = (out[ruleName(k)] ?: 0L) + v
            return out
        }
    }

    /**
     * The preview — every criterion's bucket counts at once. Openings need the
     * index, so this runs [YxdbIndex.scan] (when the index is skipped, the
     * rest only). [withOpenings] false reads once without an index.
     */
    fun count(
        input: YxdbInput,
        base: SplitBase?,
        withOpenings: Boolean = true,
        maxIndexBytes: Long = Long.MAX_VALUE,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((JobProgress) -> Unit)? = null,
    ): SplitCounts {
        if (withOpenings) {
            val t = Tally(base)
            val openings = LongArray(Opening26.COUNT + 1)
            var classified = false
            val r = YxdbIndex.scan(
                input.open, input.size,
                index = true,
                maxIndexBytes = maxIndexBytes,
                onClassified = { b, ko, kl, vo, vl, o ->
                    classified = true
                    if (t.add(b, ko, kl, vo, vl)) openings[o]++
                },
                onProgress = { p -> onProgress?.invoke(JobProgress("count", p.fraction)) },
                isCancelled = isCancelled,
            )
            val skipped = when (r.indexSkipped) {
                IndexSkipped.MEMORY -> OpeningsSkipped.MEMORY
                IndexSkipped.ALLOC -> OpeningsSkipped.ALLOC
                null -> if (r.openingsUnordered) OpeningsSkipped.UNORDERED else null
            }
            if (classified) {
                // Indexed but not in stone-count order — only the openings are dropped.
                return SplitCounts(
                    t.total, t.meta, if (skipped == null) openings.toList() else null, skipped,
                    if (base != null) t.underN else null, t.solved, t.rules(),
                )
            }
            if (skipped != null) return countOnce(input, base, skipped, isCancelled, onProgress)
        }
        return countOnce(input, base, null, isCancelled, onProgress)
    }

    private fun countOnce(
        input: YxdbInput,
        base: SplitBase?,
        skipped: OpeningsSkipped?,
        isCancelled: (() -> Boolean)?,
        onProgress: ((JobProgress) -> Unit)?,
    ): SplitCounts {
        val t = Tally(base)
        input.open().use { s ->
            Yxdb.read(
                s, input.size,
                onRecord = { b, ko, kl, vo, vl, _ -> t.add(b, ko, kl, vo, vl); true },
                onChunk = { loaded, total -> onProgress?.invoke(JobProgress("count", if (total > 0) loaded.toDouble() / total else 1.0)) },
                isCancelled = isCancelled,
            )
        }
        return SplitCounts(t.total, t.meta, null, skipped, if (base != null) t.underN else null, t.solved, t.rules())
    }

    /**
     * Splits — each result streams into `sinks[i]` (the rest last). Many results
     * (27 openings) stay small: 256 KB buffer · 256 KB LZ4 block each. A count
     * different from the preview (the file changed) throws.
     */
    fun run(
        input: YxdbInput,
        plan: SplitPlan,
        sinks: List<(ByteArray, Int, Int) -> Unit>,
        meta: Boolean,
        maxIndexBytes: Long = Long.MAX_VALUE,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((JobProgress) -> Unit)? = null,
    ): List<Long> {
        val n = plan.outputs.size + (if (plan.rest != null) 1 else 0)
        require(sinks.size == n) { "split: ${sinks.size} sinks for $n results" }
        val restIndex = if (plan.rest != null) plan.outputs.size else -1
        val openingRoute = IntArray(Opening26.COUNT + 1) { restIndex }
        val nameRoute = HashMap<String, Int>()
        plan.outputs.forEachIndexed { i, o ->
            for (b in o.buckets) when (b) {
                is Int -> openingRoute[b] = i
                is String -> nameRoute[b] = i
                else -> throw IllegalArgumentException("split: bucket $b")
            }
        }
        fun routeOf(name: String) = nameRoute[name] ?: restIndex
        val expected = plan.outputs.map { it.count } + listOfNotNull(plan.rest)
        val writers = expected.mapIndexed { i, c ->
            YxdbWriter(c + (if (meta) 1 else 0), sinks[i], blockKB = 256, bufferKB = 256)
        }
        val got = LongArray(n)
        var sawMeta = false
        fun put(i: Int, b: ByteArray, ko: Int, kl: Int, vo: Int, vl: Int) {
            if (i < 0) return
            got[i]++
            writers[i].record(b, ko, kl, vo, vl)
        }
        fun writeMeta(b: ByteArray, ko: Int, kl: Int, vo: Int, vl: Int): Boolean {
            if (!Yxdb.isMetaKey(b, ko, kl)) return false
            if (!sawMeta && meta) for (w in writers) w.record(b, ko, kl, vo, vl)
            sawMeta = true
            return true
        }
        val progress = { f: Double -> onProgress?.invoke(JobProgress("write", f)) }

        if (plan.criterion == SplitCriterion.OPENING) {
            val r = YxdbIndex.scan(
                input.open, input.size,
                index = true,
                maxIndexBytes = maxIndexBytes,
                onClassified = { b, ko, kl, vo, vl, o ->
                    if (!writeMeta(b, ko, kl, vo, vl) && o >= 0) put(openingRoute[o], b, ko, kl, vo, vl)
                },
                onProgress = { p -> progress(p.fraction) },
                isCancelled = isCancelled,
            )
            val skipped = r.indexSkipped?.name ?: if (r.openingsUnordered) "UNORDERED" else null
            if (skipped != null) throw IllegalStateException("split: openings cannot be counted ($skipped)")
        } else {
            val under = if (plan.criterion == SplitCriterion.UNDER) {
                UnderTest(plan.base ?: throw IllegalArgumentException("split: no base position"))
            } else {
                null
            }
            val inRoute = routeOf(SplitBuckets.IN)
            val outRoute = routeOf(SplitBuckets.OUT)
            val solvedRoute = routeOf(SplitBuckets.SOLVED)
            val openRoute = routeOf(SplitBuckets.OPEN)
            var lastRk = -1
            var lastRoute = restIndex
            input.open().use { s ->
                Yxdb.read(
                    s, input.size,
                    onRecord = { b, ko, kl, vo, vl, _ ->
                        if (!writeMeta(b, ko, kl, vo, vl)) {
                            val i = when (plan.criterion) {
                                SplitCriterion.UNDER -> if (under!!.test(b, ko, kl)) inRoute else outRoute
                                SplitCriterion.SOLVED -> if (isSolved(Yxdb.valueLabel(b, vo, vl))) solvedRoute else openRoute
                                else -> {
                                    val rk = ruleKey(b, ko)
                                    if (rk != lastRk) {
                                        lastRk = rk
                                        lastRoute = routeOf(ruleName(rk))
                                    }
                                    lastRoute
                                }
                            }
                            put(i, b, ko, kl, vo, vl)
                        }
                        true
                    },
                    onChunk = { l, t -> progress(if (t > 0) l.toDouble() / t else 1.0) },
                    isCancelled = isCancelled,
                )
            }
        }
        if (isCancelled?.invoke() == true) throw YxdbCancelled()
        if (meta && !sawMeta) throw YxdbFormatException("split: the metadata record is gone")
        for (w in writers) w.finish()
        return got.toList()
    }
}
