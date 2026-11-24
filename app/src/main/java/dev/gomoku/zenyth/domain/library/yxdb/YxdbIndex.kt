package dev.gomoku.zenyth.domain.library.yxdb

import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * yixindb stats and index (Zenyth-web `yxdbindex.ts`; library plan §4 · §5).
 * Runs in the `:library` job process.
 *
 * Raw bytes are never held whole. Stats alone stream once in O(1) memory; the
 * index holds fixed-width arrays per record and no key bytes:
 *
 * | array | width | use |
 * |---|---|---|
 * | slots `[hi, lo, info]` | 4+4+4 | symmetric hash (sorted) · opening · stone count — after pass 2 hi = subtree size, lo = order list |
 * | `parent` | 4 | first parent's slot (roots · subtrees) |
 * | bloom | 1 | filters absent parent candidates before touching memory |
 *
 * 1. Pass 1: stats + hashes, bucketed in place by the top bits of lo (American
 *    flag sort), insertion-sorted inside buckets.
 * 2. Pass 2: for each record, its parent candidates (one stone of the side
 *    that moved last removed). Engine files are count-ordered, so parents are
 *    settled first — every parent's opening is looked at: all equal → that
 *    one; any different, unclassified, or none found → unclassified (§5: no
 *    guessing). Three-stone positions go through `Opening26.classify`.
 * 3. Subtree sizes: add each slot into its first parent, largest stone count
 *    first. A slot with no parent is a root; keep the largest N.
 *
 * Files not in stone-count order (written by other programs) skip openings
 * (`openingsSkipped = UNORDERED`) and still produce roots.
 */
@Serializable
data class DbLabels(val W: Long = 0, val L: Long = 0, val D: Long = 0, val other: Long = 0)

@Serializable
data class DbRoot(
    /** Key bytes in hex (`YxdbKey.toHex`). */
    val key: String,
    /** Positions in the subtree linked by first parents (itself included). */
    val subtree: Long,
)

@Serializable
data class DbStats(
    /** Position records (metadata excluded). */
    val records: Long,
    /** `renju-15` etc. → positions. Shows mixing. */
    val rules: Map<String, Long>,
    val labels: DbLabels,
    /** Records with text (a comment). */
    val commented: Long,
    /** 27 = 26 openings + unclassified (renju · standard 15-line positions only). Absent when skipped or none. */
    val openings: List<Long>? = null,
    /** Parentless positions, largest subtree first. Absent when the index was skipped. */
    val roots: List<DbRoot>? = null,
)

enum class ScanPhase { STATS, HASH, LINK, ROOTS }

enum class IndexSkipped { MEMORY, ALLOC }

data class ScanProgress(val phase: ScanPhase, val fraction: Double, val records: Long)

class ScanResult(
    val stats: DbStats,
    /** Header record count (metadata included). */
    val count: Long,
    val compressed: Boolean,
    val rawBytes: Long,
    /** In engine key order — merge can stream (§4). */
    val sorted: Boolean,
    val duplicates: Long,
    /** In stone-count order — openings can be settled in one pass. */
    val countOrdered: Boolean,
    /** Unhashable records (metadata · pass slots · non-square boards). */
    val special: Long,
    val indexSkipped: IndexSkipped?,
    val openingsUnordered: Boolean,
    val indexBytes: Long?,
    val ms: Map<ScanPhase, Long>,
    val totalMs: Long,
)

/** Called in pass 2 for every record with its opening (0..25, 26 unclassified, -1 metadata). Split uses it. */
fun interface ClassifiedFn {
    fun classified(buf: ByteArray, keyOff: Int, keyLen: Int, valOff: Int, valLen: Int, opening: Int)
}

object YxdbIndex {
    const val BYTES_PER_RECORD = 16
    const val UNCLASSIFIED = Opening26.NONSTD // 26
    private const val ROOT_KEY_CAP = 8 shl 20

    internal fun log2Ceil(v: Double): Int = kotlin.math.ceil(kotlin.math.log2(v)).toInt()

    internal fun bloomBits(n: Long): Int = log2Ceil(maxOf(n, 1L) * 8.0).coerceIn(10, 31)

    internal fun bucketBits(n: Long): Int = (log2Ceil(maxOf(n, 1L).toDouble()) - 3).coerceIn(4, 22)

    /** Index memory estimate for n records: arrays + bucket table + bloom + ~4 MB read buffers. */
    fun bytesEstimate(n: Long): Long =
        n * BYTES_PER_RECORD + 4L * (1L shl bucketBits(n)) + (1L shl bloomBits(n)) / 8 + (4L shl 20)

    private fun classifiable(rule: Int, w: Int) = w == 15 && (rule == Yxdb.RULE_RENJU || rule == Yxdb.RULE_STANDARD)

    /**
     * Scans a file. [open] is called once per pass (2 or 3 times); [total] is
     * the file's byte size, for progress.
     */
    fun scan(
        open: () -> InputStream,
        total: Long,
        index: Boolean = true,
        maxIndexBytes: Long = Long.MAX_VALUE,
        rootsTop: Int = 32,
        verify: Boolean = false,
        onClassified: ClassifiedFn? = null,
        onProgress: ((ScanProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): ScanResult {
        val t0 = System.nanoTime()
        val cancelled = { isCancelled?.invoke() == true }
        val span = HashMap<ScanPhase, DoubleArray>().apply {
            if (index) {
                put(ScanPhase.STATS, doubleArrayOf(0.0, 1.0)); put(ScanPhase.HASH, doubleArrayOf(0.0, 0.45))
                put(ScanPhase.LINK, doubleArrayOf(0.45, 0.95)); put(ScanPhase.ROOTS, doubleArrayOf(0.95, 1.0))
            } else {
                for (p in ScanPhase.entries) put(p, doubleArrayOf(0.0, 1.0))
            }
        }
        var lastPost = Long.MIN_VALUE / 2
        var records = 0L
        fun progress(phase: ScanPhase, f: Double) {
            val now = System.nanoTime()
            if (now - lastPost < 100_000_000L && f < 1) return // 10 Hz
            lastPost = now
            val (a, b) = span.getValue(phase).let { it[0] to it[1] }
            onProgress?.invoke(ScanProgress(phase, a + (b - a) * minOf(1.0, f), records))
        }

        // ── pass 1: stats (+ hashes) ──
        var w = 0L; var l = 0L; var d = 0L; var other = 0L; var commented = 0L
        val ruleCount = LinkedHashMap<Int, Long>() // first-seen order, like the web's Map
        val lastN = HashMap<Int, Int>()
        var curRk = -1
        var curCount = 0L
        var curN = -1
        fun switchRule(rk: Int) {
            if (curRk >= 0) {
                ruleCount[curRk] = (ruleCount[curRk] ?: 0L) + curCount
                lastN[curRk] = curN
            }
            curRk = rk
            curCount = 0
            curN = if (rk >= 0) lastN[rk] ?: -1 else -1
        }
        var countOrdered = true
        var sorted = true
        var duplicates = 0L
        var special = 0L
        val prev = ByteArray(0x10000)
        var prevLen = -1
        var idx: Index? = null
        var indexSkipped: IndexSkipped? = null
        val hasher = KeyHasher()
        var phase1 = if (index) ScanPhase.HASH else ScanPhase.STATS

        val r1 = open().use { input ->
            Yxdb.read(
                input, total,
                onRecord = { b, ko, kl, vo, vl, _ ->
                    if (prevLen >= 0) {
                        val c = Yxdb.compareKeys(prev, 0, prevLen, b, ko, kl)
                        if (c > 0) sorted = false else if (c == 0) duplicates++
                    }
                    System.arraycopy(b, ko, prev, 0, kl)
                    prevLen = kl
                    if (Yxdb.isMetaKey(b, ko, kl)) {
                        special++
                        return@read true
                    }
                    records++
                    val rk = (b[ko].toInt() and 0xff) or ((b[ko + 1].toInt() and 0xff) shl 8) or
                        ((b[ko + 2].toInt() and 0xff) shl 16)
                    if (rk != curRk) switchRule(rk)
                    curCount++
                    when (Yxdb.valueLabel(b, vo, vl)) {
                        0 -> Unit
                        0x77 -> w++
                        0x6c -> l++
                        0x64 -> d++
                        else -> other++
                    }
                    if (Yxdb.valueTextLength(vl) > 0) commented++
                    if (!hasher.load(b, ko, kl)) {
                        special++
                        return@read true
                    }
                    if (hasher.n < curN) countOrdered = false
                    curN = hasher.n
                    val ix = idx
                    if (ix != null) {
                        hasher.hash()
                        hasher.canon()
                        ix.add(hasher.hi, hasher.lo, hasher.n)
                    }
                    true
                },
                verify = verify,
                onCount = { n ->
                    if (index) {
                        if (bytesEstimate(n) > maxIndexBytes || n > Int.MAX_VALUE / 3) {
                            indexSkipped = IndexSkipped.MEMORY
                        } else {
                            try {
                                idx = Index(n.toInt())
                            } catch (_: OutOfMemoryError) {
                                indexSkipped = IndexSkipped.ALLOC
                            }
                        }
                        if (indexSkipped != null) {
                            phase1 = ScanPhase.STATS
                            span[ScanPhase.STATS] = doubleArrayOf(0.0, 1.0)
                        }
                    }
                },
                onChunk = { loaded, tot -> if (tot > 0) progress(phase1, loaded.toDouble() / tot) },
                isCancelled = cancelled,
            )
        }
        switchRule(-1)
        val positions = records // pass 2 reuses `records` for progress only
        val rules = LinkedHashMap<String, Long>()
        for ((k, v) in ruleCount) rules[Yxdb.ruleBoardName(k and 0xff, (k ushr 8) and 0xff, (k ushr 16) and 0xff)] = v
        val labels = DbLabels(w, l, d, other)
        val tStats = System.nanoTime()
        val ms = LinkedHashMap<ScanPhase, Long>()
        val ix = idx
        ms[if (ix != null) ScanPhase.HASH else ScanPhase.STATS] = (tStats - t0) / 1_000_000

        if (ix == null) {
            return ScanResult(
                DbStats(positions, rules, labels, commented), r1.count, r1.compressed, r1.rawBytes,
                sorted, duplicates, countOrdered, special, indexSkipped, false, null, ms,
                (System.nanoTime() - t0) / 1_000_000,
            )
        }
        if (cancelled()) throw YxdbCancelled()
        ix.sort()
        if (cancelled()) throw YxdbCancelled()
        val indexBytes = ix.bytes

        // ── pass 2: parents · openings (slots stand in for record numbers) ──
        val parent = ix.parent
        val rootKeys = HashMap<Int, String>()
        var rootKeyBytes = 0L
        var rootKeysDropped = false
        val counts = LongArray(Opening26.COUNT + 1)
        var classified = 0L
        val qh = IntArray(1024)
        val ql = IntArray(1024)
        val qs = IntArray(1024)
        records = 0
        open().use { input ->
            Yxdb.read(
                input, total,
                onRecord = { b, ko, kl, vo, vl, _ ->
                    if (!hasher.load(b, ko, kl)) {
                        onClassified?.classified(b, ko, kl, vo, vl, if (Yxdb.isMetaKey(b, ko, kl)) -1 else UNCLASSIFIED)
                        return@read true
                    }
                    hasher.hash()
                    hasher.canon()
                    val own = ix.find(hasher.hi, hasher.lo)
                    if (own < 0 || ix.visited(own)) {
                        // The same position again — only the first counts.
                        onClassified?.classified(b, ko, kl, vo, vl, if (own < 0) UNCLASSIFIED else ix.openingOf(own))
                        return@read true
                    }
                    records++
                    val cls = classifiable(hasher.rule, hasher.w) && countOrdered
                    val n = hasher.n
                    var first = -1
                    var op = -1 // parents' opening: -1 none yet
                    if (!cls || n <= 3) {
                        // Roots and subtrees only need the first parent.
                        var k = hasher.lastFrom
                        val to = hasher.lastTo
                        while (k < to && first < 0) {
                            hasher.without(k)
                            first = ix.find(hasher.hi, hasher.lo)
                            k++
                        }
                    } else {
                        // Every parent — gather candidates and look them up together (overlaps cache misses).
                        var q = 0
                        for (k in hasher.lastFrom until hasher.lastTo) {
                            hasher.without(k)
                            qh[q] = hasher.hi
                            ql[q] = hasher.lo
                            q++
                        }
                        ix.findMany(qh, ql, q, qs)
                        for (k in 0 until q) {
                            val j = qs[k]
                            if (j < 0) continue
                            if (first < 0) first = j
                            val pj = ix.openingOf(j)
                            if (op == -1) op = pj else if (pj != op) op = UNCLASSIFIED
                            if (op == UNCLASSIFIED) break
                        }
                    }
                    parent[own] = first
                    val o = when {
                        !cls || n < 3 -> UNCLASSIFIED
                        n == 3 -> classifyKey(hasher)
                        op < 0 -> UNCLASSIFIED
                        else -> op
                    }
                    ix.setOpening(own, o)
                    onClassified?.classified(b, ko, kl, vo, vl, o)
                    if (cls) {
                        counts[o]++
                        classified++
                    }
                    if (first < 0 && !rootKeysDropped) {
                        rootKeyBytes += kl * 2L + 32
                        if (rootKeyBytes > ROOT_KEY_CAP) {
                            rootKeysDropped = true
                            rootKeys.clear()
                        } else {
                            rootKeys[own] = YxdbKey.toHex(b, ko, kl)
                        }
                    }
                    true
                },
                onChunk = { loaded, tot -> if (tot > 0) progress(ScanPhase.LINK, loaded.toDouble() / tot) },
                isCancelled = cancelled,
            )
        }
        val tLink = System.nanoTime()
        ms[ScanPhase.LINK] = (tLink - tStats) / 1_000_000

        val openings = if (countOrdered && classified > 0) counts.toList() else null

        // ── subtrees · roots ── (a first parent has one stone fewer, whatever the file order)
        ix.subtreeSizes()
        val topS = IntArray(rootsTop)
        val topZ = LongArray(rootsTop)
        var topN = 0
        for (sl in 0 until ix.m) {
            if (parent[sl] != -1 || !ix.visited(sl)) continue
            val z = ix.subtreeOf(sl)
            if (topN == rootsTop && z <= topZ[topN - 1]) continue
            var i = topN
            while (i > 0 && topZ[i - 1] < z) i--
            val last = minOf(topN, rootsTop - 1)
            for (j in last downTo i + 1) {
                topS[j] = topS[j - 1]
                topZ[j] = topZ[j - 1]
            }
            topS[i] = sl
            topZ[i] = z
            if (topN < rootsTop) topN++
        }
        if (rootKeysDropped && topN > 0) {
            val want = HashSet<Int>()
            for (i in 0 until topN) want.add(topS[i])
            open().use { input ->
                Yxdb.read(
                    input, total,
                    onRecord = { b, ko, kl, _, _, _ ->
                        if (!hasher.load(b, ko, kl)) return@read true
                        hasher.hash()
                        hasher.canon()
                        val sl = ix.find(hasher.hi, hasher.lo)
                        if (want.remove(sl)) rootKeys[sl] = YxdbKey.toHex(b, ko, kl)
                        want.isNotEmpty()
                    },
                    onChunk = { loaded, tot -> if (tot > 0) progress(ScanPhase.ROOTS, loaded.toDouble() / tot) },
                    isCancelled = cancelled,
                )
            }
        }
        val roots = (0 until topN).map { DbRoot(rootKeys[topS[it]] ?: "", topZ[it]) }
        progress(ScanPhase.ROOTS, 1.0)
        val tEnd = System.nanoTime()
        ms[ScanPhase.ROOTS] = (tEnd - tLink) / 1_000_000
        ix.release()
        return ScanResult(
            DbStats(positions, rules, labels, commented, openings, roots), r1.count, r1.compressed, r1.rawBytes,
            sorted, duplicates, countOrdered, special, null, !countOrdered, indexBytes, ms,
            (tEnd - t0) / 1_000_000,
        )
    }

    /** Three-stone key → opening. Of the two black stones the centre is move 1, the other move 3. */
    private fun classifyKey(h: KeyHasher): Int {
        val c0 = h.cells[0]
        val c1 = h.cells[1]
        val cw = h.cells[2]
        val ctr = (7 shl 5) or 7
        val other = if (c0 == ctr) c1 else if (c1 == ctr) c0 else -1
        if (other < 0) return UNCLASSIFIED
        return Opening26.classify(listOf(Move(7, 7), Move(cw shr 5, cw and 31), Move(other shr 5, other and 31)))
    }
}

private const val INFO_OPENING = 0xff        // bits 0-7: opening (0xFF = not yet)
private const val INFO_N_SHIFT = 8           // bits 8-23: stone count
private const val INFO_VISITED = 1 shl 24    // settled in pass 2

/**
 * Slot array `[hi, lo, info] × m`, sorted by hash. A lookup is one cache line
 * after the bucket table, and the parent's opening sits on that same line —
 * pass 2 is almost all random lookups, so cache misses set its speed.
 */
internal class Index(n: Int) {
    /** Hashed records (special ones excluded). */
    var m = 0
        private set
    private var sl = IntArray(3 * n)
    val parent = IntArray(n).also { it.fill(-1) }
    private var start: IntArray
    private val shift: Int
    /** Bloom bits for maybe-present hashes (8 bits per record → ~12% false positives). */
    private var bloom: IntArray
    private val bloomMask: Int

    init {
        val bits = YxdbIndex.bucketBits(n.toLong())
        shift = 32 - bits
        start = IntArray((1 shl bits) + 1)
        val bb = YxdbIndex.bloomBits(n.toLong())
        bloom = IntArray(1 shl (bb - 5))
        bloomMask = ((1L shl bb) - 1).toInt()
    }

    val bytes: Long get() = 4L * (sl.size + parent.size + start.size + bloom.size)

    private fun bloomIndex(h: Int, l: Int): Int = (l xor (h * BLOOM_MUL)) and bloomMask

    fun add(hi: Int, lo: Int, n: Int) {
        val o = 3 * m++
        sl[o] = hi
        sl[o + 1] = lo
        sl[o + 2] = INFO_OPENING or (n shl INFO_N_SHIFT)
        val bi = bloomIndex(hi, lo)
        bloom[bi ushr 5] = bloom[bi ushr 5] or (1 shl (bi and 31))
    }

    fun mayHave(h: Int, l: Int): Boolean {
        val bi = bloomIndex(h, l)
        return bloom[bi ushr 5] and (1 shl (bi and 31)) != 0
    }

    fun visited(s: Int): Boolean = sl[3 * s + 2] and INFO_VISITED != 0

    fun openingOf(s: Int): Int = sl[3 * s + 2] and INFO_OPENING

    fun setOpening(s: Int, o: Int) {
        val i = 3 * s + 2
        sl[i] = (sl[i] and INFO_OPENING.inv()) or o or INFO_VISITED
    }

    /**
     * In-place distribution by the top bits of **lo** (American flag sort),
     * then insertion sort within buckets by (hi, lo), signed — like [find].
     * hi is a minimum of 8 and clusters in its top bits; lo does not.
     */
    fun sort() {
        val s = sl
        val sh = shift
        val st = start
        val nb = st.size - 1
        val next = IntArray(nb)
        for (i in 0 until m) next[s[3 * i + 1] ushr sh]++
        var acc = 0
        for (b in 0 until nb) {
            st[b] = acc
            acc += next[b]
            next[b] = st[b]
        }
        st[nb] = acc
        for (b in 0 until nb) {
            val end = st[b + 1]
            while (next[b] < end) {
                val i = next[b]
                var h = s[3 * i]
                var l = s[3 * i + 1]
                var f = s[3 * i + 2]
                var tb = l ushr sh
                while (tb != b) {
                    val j = 3 * next[tb]++
                    val h2 = s[j]; val l2 = s[j + 1]; val f2 = s[j + 2]
                    s[j] = h; s[j + 1] = l; s[j + 2] = f
                    h = h2; l = l2; f = f2
                    tb = l ushr sh
                }
                s[3 * i] = h; s[3 * i + 1] = l; s[3 * i + 2] = f
                next[b]++
            }
        }
        for (b in 0 until nb) {
            val lo0 = st[b]
            val e = st[b + 1]
            for (i in lo0 + 1 until e) {
                val h = s[3 * i]; val l = s[3 * i + 1]; val f = s[3 * i + 2]
                var j = i - 1
                while (j >= lo0) {
                    val hj = s[3 * j]
                    val lj = s[3 * j + 1]
                    if (hj < h || (hj == h && lj <= l)) break
                    s[3 * j + 3] = hj; s[3 * j + 4] = lj; s[3 * j + 5] = s[3 * j + 2]
                    j--
                }
                s[3 * j + 3] = h; s[3 * j + 4] = l; s[3 * j + 5] = f
            }
        }
    }

    /** Hash → slot (the first when repeated), or -1. */
    fun find(h: Int, l: Int): Int {
        if (!mayHave(h, l)) return -1
        val s = sl
        val b = l ushr shift
        var i = start[b]
        val e = start[b + 1]
        while (i < e) {
            val hv = s[3 * i]
            if (hv < h) { i++; continue }
            if (hv > h) return -1
            val lv = s[3 * i + 1]
            if (lv < l) { i++; continue }
            return if (lv == l) i else -1
        }
        return -1
    }

    private val qb = IntArray(1024)
    private val qe = IntArray(1024)
    /** Holds the prefetch reads so the optimiser cannot drop them. */
    var touched = 0
        private set

    /** Several lookups at once — bucket starts and first slots in separate loops so misses overlap. */
    fun findMany(hs: IntArray, ls: IntArray, k: Int, out: IntArray) {
        val s = sl
        val st = start
        for (i in 0 until k) {
            if (!mayHave(hs[i], ls[i])) {
                qb[i] = 0; qe[i] = 0
                continue
            }
            val b = ls[i] ushr shift
            qb[i] = st[b]
            qe[i] = st[b + 1]
        }
        var t = 0
        for (i in 0 until k) if (qb[i] < qe[i]) t = t xor s[3 * qb[i]]
        touched = touched xor t
        for (i in 0 until k) {
            val h = hs[i]
            val l = ls[i]
            var r = -1
            var j = qb[i]
            val e = qe[i]
            while (j < e) {
                val hv = s[3 * j]
                if (hv < h) { j++; continue }
                if (hv > h) break
                val lv = s[3 * j + 1]
                if (lv < l) { j++; continue }
                if (lv == l) r = j
                break
            }
            out[i] = r
        }
    }

    /**
     * After pass 2 the hashes are done with: hi becomes the subtree size, lo the
     * stone-count order list; add slots into their first parent, largest count first.
     */
    fun subtreeSizes() {
        val s = sl
        start = IntArray(0)
        bloom = IntArray(0)
        var maxN = 0
        for (k in 0 until m) {
            s[3 * k] = 1
            maxN = maxOf(maxN, nOf(s[3 * k + 2]))
        }
        val cnt = IntArray(maxN + 2)
        for (k in 0 until m) cnt[nOf(s[3 * k + 2]) + 1]++
        for (k in 0..maxN) cnt[k + 1] += cnt[k]
        for (k in 0 until m) s[3 * cnt[nOf(s[3 * k + 2])]++ + 1] = k
        for (k in m - 1 downTo 0) {
            val x = s[3 * k + 1]
            val p = parent[x]
            if (p >= 0) s[3 * p] += s[3 * x]
        }
    }

    fun subtreeOf(s: Int): Long = sl[3 * s].toLong() and 0xffffffffL

    fun release() {
        sl = IntArray(0)
    }

    private fun nOf(info: Int): Int = (info ushr INFO_N_SHIFT) and 0xffff

    private companion object {
        const val BLOOM_MUL = -0x61c8864f // 0x9e3779b1
    }
}
