package dev.gomoku.zenyth.domain.library.yxdb

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * yixindb merge (Zenyth-web `yxdbmerge.ts`; library plan §7 · L3, L5 design
 * §2.1). Engine files are in key order, so each input holds one block and the
 * inputs stream through a k-way merge. An input not in key order (written by
 * another program) is cut into sorted runs, spilled ([Spill]) and those runs
 * are merged instead.
 *
 * The header carries the record count first, so a merge streams twice — a
 * preview (no `out`: totals · overlaps · differing records) and the write
 * (`out.count` = the preview's `records`). If an input changed in between,
 * the writer stops at the count.
 */
data class JobProgress(val phase: String, val fraction: Double, val records: Long = -1)

class YxdbUnsorted(val rank: Int) : RuntimeException("yixindb: input $rank is not in key order")

/** A re-openable yixindb: every [open] streams from the start. [size] (bytes) is for progress. */
class YxdbInput(val size: Long, val open: () -> InputStream) {
    companion object {
        fun of(file: File) = YxdbInput(file.length()) { FileInputStream(file) }
        fun of(bytes: ByteArray) = YxdbInput(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}

// ── pull reading ──────────────────────────────────────────────────────

/**
 * Pulls records one at a time ([Yxdb.read] pushes). Unpacks one stream chunk
 * and copies just its records into a small arena — the current record
 * ([buf] · [keyOff] …) stays valid until the next [next].
 */
class YxdbCursor(
    private val input: InputStream,
    private val isCancelled: (() -> Boolean)? = null,
    private val checkOrder: Boolean = false,
) : Closeable {
    var buf = ByteArray(1 shl 16)
        private set
    var keyOff = 0
        private set
    var keyLen = 0
        private set
    var valOff = 0
        private set
    var valLen = 0
        private set
    /** Header record count (-1 before it is read). */
    var count = -1L
        private set
    /** Bytes read (compressed). */
    var loaded = 0L
        private set
    /** A key smaller than the one before came up ([checkOrder]). */
    var unsorted = false
        private set
    /** With [checkOrder]: sign of compare(previous key, this key) — 0 = a duplicate of the previous; -1 for the first. */
    var order = -1
        private set

    private var offs = IntArray(4 * 1024)
    private var n = 0
    private var i = 0
    private var len = 0
    private var ended = false
    private var decided = false
    private val head = ByteArray(4)
    private var headLen = 0
    private var decoder: Lz4FrameDecoder? = null
    private val parser = YxdbRecordParser({ b, ko, kl, vo, vl, _ -> keep(b, ko, kl, vo, vl); true }, { count = it })
    private val feed = Lz4Sink { b, off, l -> parser.push(b, off, l) }
    private val chunk = ByteArray(Yxdb.CHUNK)
    private val trail = KeyTrail()

    private fun keep(b: ByteArray, ko: Int, kl: Int, vo: Int, vl: Int) {
        val need = len + kl + vl
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
        if (4 * (n + 1) > offs.size) offs = offs.copyOf(offs.size * 2)
        val o = 4 * n++
        offs[o] = len
        offs[o + 1] = kl
        System.arraycopy(b, ko, buf, len, kl)
        len += kl
        offs[o + 2] = len
        offs[o + 3] = vl
        System.arraycopy(b, vo, buf, len, vl)
        len += vl
    }

    private fun push(b: ByteArray, off: Int, l: Int) {
        if (l <= 0) return
        val d = decoder
        if (d != null) d.push(b, off, l) else feed.accept(b, off, l)
    }

    /** Reads one stream chunk — it may yield no record at all. */
    private fun fill() {
        if (isCancelled?.invoke() == true) throw YxdbCancelled()
        val r = input.read(chunk)
        if (r < 0) {
            if (!decided && headLen > 0) feed.accept(head, 0, headLen)
            decoder?.end()
            parser.end()
            ended = true
            return
        }
        if (r == 0) return
        loaded += r
        if (!decided) {
            val take = minOf(4 - headLen, r)
            System.arraycopy(chunk, 0, head, headLen, take)
            headLen += take
            if (headLen < 4) return
            decided = true
            if (Lz4.isFrame(head, 4)) decoder = Lz4FrameDecoder(feed)
            push(head, 0, 4)
            push(chunk, take, r - take)
            return
        }
        push(chunk, 0, r)
    }

    /** Moves to the next record; false at the end. */
    fun next(): Boolean {
        i++
        while (i >= n) {
            if (ended) return false
            n = 0
            i = 0
            len = 0
            fill()
        }
        val o = 4 * i
        keyOff = offs[o]
        keyLen = offs[o + 1]
        valOff = offs[o + 2]
        valLen = offs[o + 3]
        if (checkOrder) {
            order = trail.next(buf, keyOff, keyLen)
            if (order > 0) unsorted = true
        }
        return true
    }

    /** Stops before the end. */
    override fun close() {
        ended = true
        input.close()
    }
}

// ── spill ─────────────────────────────────────────────────────────────

/** Where temporary bytes go; [finish] hands them back as an input. */
interface Spill {
    fun write(b: ByteArray, off: Int, len: Int)
    fun finish(): YxdbInput
    val bytes: Long
}

/** In memory — tests and small inputs. */
class MemorySpill : Spill {
    private val out = ByteArrayOutputStream()
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun finish(): YxdbInput = YxdbInput.of(out.toByteArray())
    override val bytes: Long get() = out.size().toLong()
}

/**
 * A file (`cacheDir/library/spill/<job>/` on the device, L5 §2.1 — the web
 * folds Blobs instead). The job layer sweeps the folder afterwards, also
 * after a kill.
 */
class FileSpill(private val file: File) : Spill {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    private var n = 0L
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        n += len
    }
    override fun finish(): YxdbInput {
        out.close()
        return YxdbInput.of(file)
    }
    override val bytes: Long get() = n

    companion object {
        /** A factory for `run-<k>.yxdb` files in [dir]. */
        fun factory(dir: File): () -> Spill {
            dir.mkdirs()
            var k = 0
            return { FileSpill(File(dir, "run-${k++}.yxdb")) }
        }
    }
}

// ── an unordered input → sorted runs ─────────────────────────────────

object YxdbMerge {
    const val RUN_BYTES = 32 shl 20

    /**
     * Cuts the file every [runBytes], sorts each run into key order and spills
     * it uncompressed. Holds one run in memory. Merge combines the runs under
     * the original input's rank.
     */
    fun sortedRuns(
        input: YxdbInput,
        newSpill: () -> Spill,
        runBytes: Int = RUN_BYTES,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((Double) -> Unit)? = null,
    ): List<YxdbInput> {
        val runs = ArrayList<YxdbInput>()
        var arena = ByteArray(minOf(runBytes + 0x20000, 1 shl 20))
        var len = 0
        var offs = IntArray(4 * 4096)
        var n = 0
        fun flush() {
            if (n == 0) return
            val a = arena
            val o = offs
            val order = sortedOrder(n) { x, y -> Yxdb.compareKeys(a, o[4 * x], o[4 * x + 1], a, o[4 * y], o[4 * y + 1]) }
            val spill = newSpill()
            val w = YxdbWriter(n.toLong(), { b, off, l -> spill.write(b, off, l) }, compress = false)
            for (i in order) w.recordKV(a, o[4 * i], o[4 * i + 1], a, o[4 * i + 2], o[4 * i + 3])
            w.finish()
            runs.add(spill.finish())
            len = 0
            n = 0
        }
        input.open().use { s ->
            Yxdb.read(
                s, input.size,
                onRecord = { b, ko, kl, vo, vl, _ ->
                    if (len + kl + vl > runBytes && n > 0) flush()
                    if (len + kl + vl > arena.size) {
                        arena = arena.copyOf(maxOf(len + kl + vl, minOf(arena.size * 2, runBytes + 0x20000)))
                    }
                    if (4 * (n + 1) > offs.size) offs = offs.copyOf(offs.size * 2)
                    val q = 4 * n++
                    offs[q] = len
                    offs[q + 1] = kl
                    System.arraycopy(b, ko, arena, len, kl)
                    len += kl
                    offs[q + 2] = len
                    offs[q + 3] = vl
                    System.arraycopy(b, vo, arena, len, vl)
                    len += vl
                    true
                },
                onChunk = { loaded, total -> onProgress?.invoke(if (total > 0) loaded.toDouble() / total else 1.0) },
                isCancelled = isCancelled,
            )
        }
        flush()
        return runs
    }

    /** Stable sort of `0 until n` (bottom-up merge sort on primitives — equal keys keep file order, like JS `sort`). */
    internal fun sortedOrder(n: Int, cmp: (Int, Int) -> Int): IntArray {
        var a = IntArray(n) { it }
        var t = IntArray(n)
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) t[k++] = if (cmp(a[j], a[i]) < 0) a[j++] else a[i++]
                while (i < mid) t[k++] = a[i++]
                while (j < hi) t[k++] = a[j++]
                lo = hi
            }
            val s = a
            a = t
            t = s
            width *= 2
        }
        return a
    }

    // ── merge ──────────────────────────────────────────────────────────

    /**
     * Merges key-ordered inputs. Without [out] it only counts (the preview).
     * With it, writes `out.count` (the preview's `records`) into the header
     * and streams — a different count (an input changed) makes
     * [YxdbWriter] throw. An unordered input throws [YxdbUnsorted] with its
     * rank; the caller replaces it with [sortedRuns] and calls again.
     */
    fun merge(sources: List<MergeSource>, opts: MergeOptions, out: MergeOut? = null): MergeSummary {
        val cancelled = { opts.isCancelled?.invoke() == true }
        val total = sources.sumOf { it.input.size }.takeIf { it > 0 } ?: 1L
        val ranks = (sources.maxOfOrNull { it.rank } ?: -1).coerceAtLeast(0) + 1
        val cursors = ArrayList<YxdbCursor>(sources.size)
        try {
            val alive = BooleanArray(sources.size)
            for ((j, s) in sources.withIndex()) {
                val c = YxdbCursor(s.input.open(), cancelled, checkOrder = true)
                cursors.add(c)
                alive[j] = c.next()
                if (c.unsorted) throw YxdbUnsorted(s.rank)
            }
            val writer = out?.let { YxdbWriter(it.count, it.sink, blockKB = it.blockKB) }
            var records = 0L
            var positions = 0L
            val inputPositions = LongArray(ranks)
            var overlaps = 0L
            var differing = 0L
            val key = ByteArray(0x10000)
            // This position's values, in rank order (within a rank, as they came).
            var vals = ByteArray(1 shl 16)
            var vOff = IntArray(16)
            var vLen = IntArray(16)
            var vRank = IntArray(16)
            var vn = 0
            val seenRank = BooleanArray(ranks)
            val outVal = ByteArray(0x10000)
            var lastPost = Long.MIN_VALUE / 2
            val phase = if (out != null) "write" else "count"
            fun post(force: Boolean) {
                val now = System.nanoTime()
                if (!force && now - lastPost < 100_000_000L) return
                lastPost = now
                val loaded = cursors.sumOf { it.loaded }
                opts.onProgress?.invoke(JobProgress(phase, if (force) 1.0 else minOf(1.0, loaded.toDouble() / total), positions))
            }

            val cur = cursors.toTypedArray()
            val rankOf = IntArray(cur.size) { sources[it].rank }
            val tied = BooleanArray(cur.size)
            var step = 0L
            while (true) {
                // The smallest head and every head equal to it, in one scan (k − 1 compares).
                var m = -1
                for (j in cur.indices) {
                    if (!alive[j]) continue
                    if (m < 0) {
                        m = j
                        tied[j] = true
                        continue
                    }
                    val a = cur[j]
                    val b = cur[m]
                    val c = Yxdb.compareKeys(a.buf, a.keyOff, a.keyLen, b.buf, b.keyOff, b.keyLen)
                    if (c < 0) {
                        for (t in 0 until j) tied[t] = false
                        m = j
                        tied[j] = true
                    } else if (c == 0) {
                        tied[j] = true
                        if (rankOf[j] < rankOf[m]) m = j
                    }
                }
                if (m < 0) break
                val cm = cur[m]
                val kl = cm.keyLen
                System.arraycopy(cm.buf, cm.keyOff, key, 0, kl)
                vn = 0
                seenRank.fill(false)
                var vlen = 0
                // Gather the tied heads; a cursor's own order check spots duplicates within an input.
                for (j in cur.indices) {
                    if (!tied[j]) continue
                    tied[j] = false
                    val c = cur[j]
                    var more = true
                    while (more) {
                        if (vlen + c.valLen > vals.size) vals = vals.copyOf(maxOf(vlen + c.valLen, vals.size * 2))
                        System.arraycopy(c.buf, c.valOff, vals, vlen, c.valLen)
                        if (vn == vOff.size) {
                            vOff = vOff.copyOf(vn * 2); vLen = vLen.copyOf(vn * 2); vRank = vRank.copyOf(vn * 2)
                        }
                        // Insert in rank order.
                        val r = rankOf[j]
                        var at = vn
                        while (at > 0 && vRank[at - 1] > r) {
                            vOff[at] = vOff[at - 1]; vLen[at] = vLen[at - 1]; vRank[at] = vRank[at - 1]
                            at--
                        }
                        vOff[at] = vlen; vLen[at] = c.valLen; vRank[at] = r
                        vn++
                        vlen += c.valLen
                        seenRank[r] = true
                        alive[j] = c.next()
                        if (c.unsorted) throw YxdbUnsorted(r)
                        more = alive[j] && c.order == 0
                    }
                }
                val meta = Yxdb.isMetaKey(key, 0, kl)
                var distinct = 0
                for (r in 0 until ranks) if (seenRank[r]) {
                    distinct++
                    if (!meta) inputPositions[r]++
                }

                // Pick
                var pick = -1
                for (i in 0 until vn) {
                    if (vLen[i] == 0) continue
                    if (pick < 0) {
                        pick = i
                        if (opts.policy == MergePolicy.BASE) break
                        continue
                    }
                    val di = depthOf(vals, vOff[i], vLen[i])
                    val dp = depthOf(vals, vOff[pick], vLen[pick])
                    if (di > dp || (di == dp && isSolved(vals[vOff[i]].toInt() and 0xff) && !isSolved(vals[vOff[pick]].toInt() and 0xff))) pick = i
                }
                if (!meta && distinct > 1) {
                    overlaps++
                    var differ = false
                    var first = -1
                    var i = 0
                    while (i < vn && !differ) {
                        if (vLen[i] != 0) {
                            if (first < 0) {
                                first = i
                            } else {
                                for (k in 0 until 5) {
                                    if (byteAt(vals, vOff[i], vLen[i], k) != byteAt(vals, vOff[first], vLen[first], k)) {
                                        differ = true
                                        break
                                    }
                                }
                            }
                        }
                        i++
                    }
                    if (differ) differing++
                }
                records++
                if (!meta) positions++

                if (writer != null) {
                    var ob = vals
                    var oo = 0
                    var ol = 0
                    if (pick >= 0) {
                        oo = vOff[pick]
                        ol = vLen[pick]
                        if (opts.joinComments && vn > 1 && ol >= 5) {
                            // Keep the other inputs' comments even when the picked record has none.
                            val joined = joinTexts(vals, vOff, vLen, vn, pick, outVal)
                            if (joined > 0) {
                                ob = outVal
                                oo = 0
                                ol = joined
                            }
                        }
                    }
                    writer.recordKV(key, 0, kl, ob, oo, ol)
                }
                if (step and 1023L == 0L) {
                    if (cancelled()) throw YxdbCancelled()
                    post(false)
                }
                step++
            }
            writer?.finish()
            post(true)
            return MergeSummary(records, positions, inputPositions.toList(), overlaps, differing)
        } finally {
            for (c in cursors) runCatching { c.close() }
        }
    }

    private const val LABEL_W = 0x77
    private const val LABEL_L = 0x6c
    private const val LABEL_D = 0x64

    private fun isSolved(l: Int) =
        l == LABEL_W || l == LABEL_L || l == LABEL_D || l == 0x57 || l == 0x4c || l == 0x44

    /** Byte k of a value, -1 past its end (the web reads `undefined`). */
    private fun byteAt(b: ByteArray, o: Int, l: Int, k: Int): Int = if (k < l) b[o + k].toInt() and 0xff else -1

    /**
     * A value's depth — `depthbound` is i16 `depth shl 2 or bound` (Rapfi
     * `DBRecord`). Values without a record never reach here (skipped first).
     */
    private fun depthOf(b: ByteArray, o: Int, l: Int): Int {
        if (l < 5) return -1_000_000_000
        val v = ((b[o + 3].toInt() and 0xff) or ((b[o + 4].toInt() and 0xff) shl 8)).toShort().toInt()
        return v shr 2
    }

    /**
     * The picked record's head (5 bytes) + the comments in rank order, joined
     * by newlines (equal texts once). 0 when it would overflow 64 KB — the
     * picked record is written as is.
     */
    private fun joinTexts(vals: ByteArray, vOff: IntArray, vLen: IntArray, vn: Int, pick: Int, out: ByteArray): Int {
        val to = IntArray(vn)
        val tl = IntArray(vn)
        var tn = 0
        for (i in 0 until vn) {
            val l = vLen[i] - 5
            if (l <= 0) continue
            val o = vOff[i] + 5
            var dup = false
            for (t in 0 until tn) {
                if (tl[t] != l) continue
                var same = true
                for (k in 0 until l) if (vals[to[t] + k] != vals[o + k]) { same = false; break }
                if (same) { dup = true; break }
            }
            if (!dup) {
                to[tn] = o
                tl[tn] = l
                tn++
            }
        }
        if (tn == 0) return 0
        // One comment, and it is the picked record's own: as is.
        if (tn == 1 && to[0] == vOff[pick] + 5) return 0
        var n = 5
        for (t in 0 until tn) n += tl[t] + 1
        n -= 1
        if (n > out.size) return 0
        System.arraycopy(vals, vOff[pick], out, 0, 5)
        var p = 5
        for (t in 0 until tn) {
            if (t > 0) out[p++] = 0x0a
            System.arraycopy(vals, to[t], out, p, tl[t])
            p += tl[t]
        }
        return p
    }

    /** Counts a file's records again (the atomic write's check). */
    fun count(input: YxdbInput, isCancelled: (() -> Boolean)? = null): Long {
        var n = 0L
        input.open().use { s -> Yxdb.read(s, input.size, { _, _, _, _, _, _ -> n++; true }, isCancelled = isCancelled) }
        return n
    }
}

/** When a position is in more than one input — the deeper record (recommended) or the base (top) input first. */
enum class MergePolicy { DEEPER, BASE }

class MergeOptions(
    val policy: MergePolicy,
    /** Join the comments (equal texts once). Off: only the picked record's comment. */
    val joinComments: Boolean,
    val isCancelled: (() -> Boolean)? = null,
    val onProgress: ((JobProgress) -> Unit)? = null,
)

/** One input — the runs of an unordered file share one [rank] (original order, 0 = base). */
class MergeSource(val input: YxdbInput, val rank: Int)

class MergeOut(val count: Long, val sink: (ByteArray, Int, Int) -> Unit, val blockKB: Int = 4096)

data class MergeSummary(
    /** Records to write (metadata included) — the header count. */
    val records: Long,
    /** Positions in the result (metadata excluded). */
    val positions: Long,
    /** Positions per input rank (duplicates within an input once). */
    val inputPositions: List<Long>,
    /** Positions found in more than one input. */
    val overlaps: Long,
    /** Of the overlaps, those whose records (label · value · depth) differ. */
    val differing: Long,
)
