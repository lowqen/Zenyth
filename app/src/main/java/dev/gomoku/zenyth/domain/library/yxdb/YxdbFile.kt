package dev.gomoku.zenyth.domain.library.yxdb

import java.io.InputStream

/**
 * yixindb file — streaming codec (Zenyth-web `yxdbfile.ts`; library plan §4).
 *
 * ```
 * [LZ4 frame (optional)] u32 count · (u16 keyLen · key · u16 valLen · value) × count
 * key   = rule(1) · width(1) · height(1) · (x, y) × stones — black ceil(n/2) slots, then white
 * value = label(1) · value(i16) · depthbound(i16) · text(UTF-8); length 0 = "no record"
 * ```
 *
 * - The first record is metadata with width = height = 0 (`charset="UTF-8"`).
 * - A slot (0xFF, 0xFF) is a pass: it ends its colour section and is not a stone.
 * - The engine (DB5) writes in `(rule, w, h, stone count, stone bytes, side)`
 *   order — stone count first, so a parent always precedes its children.
 *
 * Raw bytes are never held whole: records are read straight off the
 * decoder's block, and only a record straddling two blocks is copied into a
 * small carry buffer.
 */
class YxdbFormatException(message: String) : RuntimeException(message)

class YxdbCancelled : RuntimeException("cancelled")

/** One record; `buf` is valid only during the call. Return false to stop reading. */
fun interface YxdbRecordFn {
    fun record(buf: ByteArray, keyOff: Int, keyLen: Int, valOff: Int, valLen: Int, index: Int): Boolean
}

private const val MAX_RECORD = 2 + 0xffff + 2 + 0xffff

/**
 * Cuts pushed raw bytes into records. Reads as many records as the header
 * says and stops, as the engine's loader does; bytes past that are counted in
 * [trailing].
 */
class YxdbRecordParser(
    private val onRecord: YxdbRecordFn,
    private val onCount: ((Long) -> Unit)? = null,
) {
    /** Header record count (-1 before the header). */
    var count = -1L
        private set
    /** Records read, keyLen-0 slots included (indices follow file order). */
    var index = 0L
        private set
    var trailing = 0L
        private set
    var stopped = false
        private set
    private val carry = ByteArray(MAX_RECORD)
    private var carryLen = 0

    val done: Boolean get() = stopped || (count >= 0 && index >= count)

    fun push(buf: ByteArray, off: Int, len: Int) {
        var p = off
        val end = off + len
        if (done) {
            if (!stopped) trailing += len
            return
        }
        if (carryLen > 0) {
            // Each length prefix read can raise how much is needed — fill until it stops growing.
            while (true) {
                val want = wanted(carry, 0, carryLen)
                if (carryLen >= want) break
                if (p >= end) return
                val take = minOf(want - carryLen, end - p)
                System.arraycopy(buf, p, carry, carryLen, take)
                carryLen += take
                p += take
            }
            val used = consume(carry, 0)
            if (used != carryLen) throw YxdbFormatException("yixindb: carry buffer error")
            carryLen = 0
            if (done) {
                if (!stopped) trailing += end - p
                return
            }
        }
        while (p < end) {
            val want = wanted(buf, p, end - p)
            if (end - p < want) {
                System.arraycopy(buf, p, carry, 0, end - p)
                carryLen = end - p
                return
            }
            p += consume(buf, p)
            if (done) {
                if (!stopped) trailing += end - p
                return
            }
        }
    }

    /** Bytes needed to finish the next unit (header or one record), as far as known. */
    private fun wanted(b: ByteArray, p: Int, avail: Int): Int {
        if (count < 0) return 4
        if (avail < 2) return 2
        val kl = u16(b, p)
        if (kl == 0) return 2 // the loader skips it too (no value field)
        if (avail < 2 + kl + 2) return 2 + kl + 2
        return 2 + kl + 2 + u16(b, p + 2 + kl)
    }

    private fun consume(b: ByteArray, p: Int): Int {
        if (count < 0) {
            count = u32(b, p).toLong() and 0xffffffffL
            onCount?.invoke(count)
            return 4
        }
        val kl = u16(b, p)
        val i = index++
        if (kl == 0) return 2
        val vl = u16(b, p + 2 + kl)
        if (!onRecord.record(b, p + 2, kl, p + 4 + kl, vl, i.toInt())) stopped = true
        return 4 + kl + vl
    }

    /** Input ended — fewer records than the header promised means a cut file. */
    fun end() {
        if (stopped) return
        if (count < 0) throw YxdbFormatException("yixindb: no record-count header")
        if (index < count) throw YxdbFormatException("yixindb: cut at record $index/$count")
    }
}

data class ReadYxdbResult(
    val count: Long,
    val read: Long,
    val compressed: Boolean,
    val rawBytes: Long,
    val trailing: Long,
    val stopped: Boolean,
)

object Yxdb {
    const val RULE_FREESTYLE = 0
    const val RULE_STANDARD = 1
    const val RULE_RENJU = 2
    private val RULE_NAMES = arrayOf("freestyle", "standard", "renju")

    /** Read granularity: one cancel check and one progress report per chunk. */
    const val CHUNK = 256 * 1024

    /**
     * Streams [input] from the start, calling [onRecord] per record. Unpacks an
     * LZ4 frame, or reads an uncompressed yixindb as is. [total] (compressed
     * bytes, for progress) may be 0 when unknown.
     */
    fun read(
        input: InputStream,
        total: Long,
        onRecord: YxdbRecordFn,
        verify: Boolean = false,
        onCount: ((Long) -> Unit)? = null,
        onChunk: ((loaded: Long, total: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): ReadYxdbResult {
        val parser = YxdbRecordParser(onRecord, onCount)
        var rawBytes = 0L
        val feed = Lz4Sink { b, off, len ->
            rawBytes += len
            parser.push(b, off, len)
        }
        var decoder: Lz4FrameDecoder? = null
        val chunk = ByteArray(CHUNK)
        var loaded = 0L
        // Decide compressed or not from the first 4 bytes.
        var headLen = 0
        while (headLen < 4) {
            val r = input.read(chunk, headLen, 4 - headLen)
            if (r < 0) break
            headLen += r
        }
        loaded += headLen
        if (Lz4.isFrame(chunk, headLen)) decoder = Lz4FrameDecoder(feed, verify)
        if (headLen > 0) {
            if (decoder != null) decoder.push(chunk, 0, headLen) else feed.accept(chunk, 0, headLen)
        }
        while (!parser.stopped) {
            if (isCancelled?.invoke() == true) throw YxdbCancelled()
            val r = input.read(chunk)
            if (r < 0) break
            if (r == 0) continue
            loaded += r
            if (decoder != null) decoder.push(chunk, 0, r) else feed.accept(chunk, 0, r)
            onChunk?.invoke(loaded, total)
        }
        if (!parser.stopped) {
            decoder?.end()
            parser.end()
        }
        return ReadYxdbResult(parser.count, parser.index, decoder != null, rawBytes, parser.trailing, parser.stopped)
    }

    /** Stats key — `renju-15`, `renju-15x20` when not square. */
    fun ruleBoardName(rule: Int, w: Int, h: Int): String {
        val r = RULE_NAMES.getOrNull(rule) ?: "rule$rule"
        return if (w == h) "$r-$w" else "$r-${w}x$h"
    }

    fun isMetaKey(b: ByteArray, keyOff: Int, keyLen: Int): Boolean =
        keyLen >= 3 && b[keyOff + 1].toInt() == 0 && b[keyOff + 2].toInt() == 0

    /** Real stone count (pass slots excluded); -1 when a coordinate is off the board. */
    fun keyStoneCount(b: ByteArray, o: Int, l: Int): Int {
        if (l < 3) return -1
        val w = b[o + 1].toInt() and 0xff
        val h = b[o + 2].toInt() and 0xff
        val slots = (l - 3) shr 1
        val nb = (slots + 1) shr 1
        var n = 0
        for (c in 0..1) {
            val begin = if (c == 0) 0 else nb
            val cnt = if (c == 0) nb else slots - nb
            for (i in 0 until cnt) {
                val x = b[o + 3 + 2 * (begin + i)].toInt() and 0xff
                val y = b[o + 4 + 2 * (begin + i)].toInt() and 0xff
                if (x == 0xff && y == 0xff) break
                if (x >= w || y >= h) return -1
                n++
            }
        }
        return n
    }

    /**
     * Engine key order (DB5 `compareEntry`): rule · width · height · stone count ·
     * stone bytes (unsigned memcmp) · side to move.
     */
    fun compareKeys(a: ByteArray, ao: Int, al: Int, b: ByteArray, bo: Int, bl: Int): Int {
        for (i in 0 until 3) {
            val d = (if (i < al) a[ao + i].toInt() and 0xff else 0) - (if (i < bl) b[bo + i].toInt() and 0xff else 0)
            if (d != 0) return d
        }
        if (!hasPassSlot(a, ao, al) && !hasPassSlot(b, bo, bl)) {
            if (al != bl) return al - bl
            for (i in 3 until al) {
                val x = a[ao + i].toInt() and 0xff
                val y = b[bo + i].toInt() and 0xff
                if (x != y) return x - y
            }
            return 0
        }
        val na = keyStoneCount(a, ao, al)
        val nb = keyStoneCount(b, bo, bl)
        if (na != nb) return na - nb
        val sa = stoneBytes(a, ao, al)
        val sb = stoneBytes(b, bo, bl)
        var i = 0
        while (i < sa.size && i < sb.size) {
            if (sa[i] != sb[i]) return sa[i] - sb[i]
            i++
        }
        val stmA = ((al - 3) shr 1) % 2
        val stmB = ((bl - 3) shr 1) % 2
        return stmA - stmB
    }

    /** Pass slots only sit at a section's end (engine `putRecord`). */
    private fun hasPassSlot(b: ByteArray, o: Int, l: Int): Boolean {
        val slots = (l - 3) shr 1
        if (slots <= 0) return false
        val nb = (slots + 1) shr 1
        val pb = o + 3 + 2 * (nb - 1)
        val pw = o + 3 + 2 * (slots - 1)
        return (b[pb] == FF && b[pb + 1] == FF) || (b[pw] == FF && b[pw + 1] == FF)
    }

    private const val FF: Byte = -1

    private fun stoneBytes(b: ByteArray, o: Int, l: Int): IntArray {
        val slots = (l - 3) shr 1
        val nb = (slots + 1) shr 1
        val out = ArrayList<Int>(slots * 2)
        for (c in 0..1) {
            val begin = if (c == 0) 0 else nb
            val cnt = if (c == 0) nb else slots - nb
            for (i in 0 until cnt) {
                val x = b[o + 3 + 2 * (begin + i)].toInt() and 0xff
                val y = b[o + 4 + 2 * (begin + i)].toInt() and 0xff
                if (x == 0xff && y == 0xff) break
                out.add(x)
                out.add(y)
            }
        }
        return out.toIntArray()
    }

    /** Label byte (0 = no record). The engine writes lowercase `w` `l` `d`. */
    fun valueLabel(b: ByteArray, valOff: Int, valLen: Int): Int = if (valLen > 0) b[valOff].toInt() and 0xff else 0

    fun valueTextLength(valLen: Int): Int = if (valLen > 5) valLen - 5 else 0

    /** The metadata record the engine writes first (`charset="UTF-8"`). */
    fun metaRecord(): Pair<ByteArray, ByteArray> {
        val text = "charset=\"UTF-8\"".toByteArray(Charsets.UTF_8)
        val value = ByteArray(5 + text.size)
        System.arraycopy(text, 0, value, 5, text.size)
        return byteArrayOf(0, 0, 0) to value
    }
}

/**
 * Streams a yixindb out. The record count goes in the header first, so it
 * must be known up front (merge and split count in their preview pass).
 * Holds the write buffer (1 MB default) plus one LZ4 block.
 */
class YxdbWriter(
    private val count: Long,
    private val sink: (ByteArray, Int, Int) -> Unit,
    compress: Boolean = true,
    blockKB: Int = 4096,
    bufferKB: Int = 1024,
) {
    private val enc: Lz4FrameEncoder? = if (compress) Lz4FrameEncoder(sink, blockKB) else null
    private val buf = ByteArray(maxOf(4, bufferKB) shl 10)
    private var len = 0
    private var flushed = 0L
    private var finished = false

    var records = 0L
        private set

    /** Bytes output so far (after compression); the buffer's tail is not counted yet. */
    val outBytes: Long get() = enc?.written ?: flushed

    init {
        require(count in 0..0xffffffffL) { "yixindb: record count out of range" }
        val h = ByteArray(4)
        putU32(h, 0, count.toInt())
        put(h, 0, 4)
    }

    private fun flush() {
        if (len == 0) return
        if (enc != null) {
            enc.write(buf, 0, len)
        } else {
            sink(buf, 0, len)
            flushed += len
        }
        len = 0
    }

    private fun put(b: ByteArray, off: Int, n: Int) {
        if (len + n > buf.size) {
            flush()
            if (n > buf.size) {
                if (enc != null) enc.write(b, off, n) else { sink(b, off, n); flushed += n }
                return
            }
        }
        System.arraycopy(b, off, buf, len, n)
        len += n
    }

    private fun u16(v: Int) {
        if (len + 2 > buf.size) flush()
        buf[len++] = v.toByte()
        buf[len++] = (v ushr 8).toByte()
    }

    fun record(b: ByteArray, keyOff: Int, keyLen: Int, valOff: Int, valLen: Int) =
        recordKV(b, keyOff, keyLen, b, valOff, valLen)

    fun recordKV(kb: ByteArray, keyOff: Int, keyLen: Int, vb: ByteArray, valOff: Int, valLen: Int) {
        if (finished) throw YxdbFormatException("yixindb: writer already closed")
        if (keyLen < 3 || keyLen > 0xffff || valLen > 0xffff) throw YxdbFormatException("yixindb: record length")
        if (records >= count) throw YxdbFormatException("yixindb: more records than the header ($count)")
        u16(keyLen)
        put(kb, keyOff, keyLen)
        u16(valLen)
        put(vb, valOff, valLen)
        records++
    }

    fun finish() {
        if (finished) return
        if (records != count) throw YxdbFormatException("yixindb: record count differs from the header ($records ≠ $count)")
        flush()
        enc?.finish()
        finished = true
    }
}
