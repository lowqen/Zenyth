package dev.gomoku.zenyth.domain.library.yxdb

/**
 * LZ4 frame — streaming decode and encode with no dependency (Zenyth-web
 * `lz4frame.ts`, byte for byte). The engine (Rapfi `lz4_stream`, LZ4-HC)
 * writes 64 KB linked blocks plus a content checksum (FLG 0x44 · BD 0x40);
 * the decoder unpacks one block at a time and keeps only the previous 64 KB
 * window, so the ~700 MB of raw bytes behind a 79 MB file are never held at
 * once. The encoder writes independent 4 MB LZ4-fast blocks.
 *
 * Format: lz4_Frame_format.md · lz4_Block_format.md (lz4/lz4 on GitHub).
 */
class Lz4Exception(message: String) : RuntimeException(message)

/** Receives decoded bytes; `buf[off, off+len)` is valid only during the call. */
fun interface Lz4Sink {
    fun accept(buf: ByteArray, off: Int, len: Int)
}

object Lz4 {
    const val MAGIC = 0x184d2204
    private const val SKIPPABLE_MASK = -0x10          // 0xfffffff0
    private const val SKIPPABLE_MAGIC = 0x184d2a50
    const val WINDOW = 64 * 1024
    /** BD block-max id 4..7 → bytes. */
    val BLOCK_MAX = intArrayOf(0, 0, 0, 0, 64 shl 10, 256 shl 10, 1 shl 20, 4 shl 20)

    fun isFrame(head: ByteArray, len: Int = head.size): Boolean = len >= 4 && u32(head, 0) == MAGIC

    internal fun isSkippable(m: Int): Boolean = (m and SKIPPABLE_MASK) == SKIPPABLE_MAGIC

    /**
     * Decodes one LZ4 block into `dst[dp..]`; matches may reach back to
     * [dictStart] (a linked block's window). Returns the new end position.
     */
    fun decodeBlock(src: ByteArray, sp0: Int, send: Int, dst: ByteArray, dp0: Int, dend: Int, dictStart: Int): Int {
        var sp = sp0
        var dp = dp0
        while (true) {
            if (sp >= send) throw Lz4Exception("LZ4: block ends without a token")
            val tok = src[sp++].toInt() and 0xff
            var lit = tok ushr 4
            if (lit == 15) {
                var x: Int
                do {
                    if (sp >= send) throw Lz4Exception("LZ4: literal length cut")
                    x = src[sp++].toInt() and 0xff
                    lit += x
                } while (x == 255)
            }
            if (sp + lit > send || dp + lit > dend) throw Lz4Exception("LZ4: literal outside the block")
            if (lit < 16) {
                for (i in 0 until lit) dst[dp + i] = src[sp + i]
            } else {
                System.arraycopy(src, sp, dst, dp, lit)
            }
            sp += lit
            dp += lit
            if (sp >= send) return dp // the last sequence is literals only
            if (sp + 2 > send) throw Lz4Exception("LZ4: offset cut")
            val off = u16(src, sp)
            sp += 2
            var ml = tok and 15
            if (ml == 15) {
                var x: Int
                do {
                    if (sp >= send) throw Lz4Exception("LZ4: match length cut")
                    x = src[sp++].toInt() and 0xff
                    ml += x
                } while (x == 255)
            }
            ml += 4
            var s = dp - off
            if (off == 0 || s < dictStart) throw Lz4Exception("LZ4: bad offset")
            if (dp + ml > dend) throw Lz4Exception("LZ4: match outside the block")
            if (off >= ml) {
                System.arraycopy(dst, s, dst, dp, ml)
                dp += ml
            } else {
                // Overlapping copy: byte by byte reproduces the repetition.
                val e = dp + ml
                while (dp < e) dst[dp++] = dst[s++]
            }
        }
    }

    // ── block compression (LZ4 fast) ────────────────────────────────────

    private const val MINMATCH = 4
    private const val LASTLITERALS = 5
    private const val MFLIMIT = 12
    private const val HASH_BITS = 16
    private const val MAX_OFFSET = 65535

    fun compressBound(n: Int): Int = n + n / 255 + 16

    private fun hash(v: Int): Int = (v * Xxh32.P1) ushr (32 - HASH_BITS)

    /**
     * Compresses `src[s0, s0+len)` into `dst[d0..]` and returns the length.
     * [table] is a reusable `IntArray(1 shl 16)`; dst must have
     * [compressBound] bytes left. Same greedy matcher as the web, so the two
     * produce identical bytes.
     */
    fun compressBlock(src: ByteArray, s0: Int, len: Int, dst: ByteArray, d0: Int, table: IntArray): Int {
        val end = s0 + len
        var op = d0
        var anchor = s0
        if (len >= MFLIMIT + 1) {
            table.fill(-1)
            val mflimit = end - MFLIMIT
            val matchlimit = end - LASTLITERALS
            var ip = s0 + 1
            table[hash(u32(src, s0))] = s0
            outer@ while (true) {
                var ref: Int
                var attempts = 1 shl 6
                while (true) {
                    if (ip >= mflimit) break@outer
                    val seq = u32(src, ip)
                    val h = hash(seq)
                    ref = table[h]
                    table[h] = ip
                    if (ref >= s0 && ip - ref <= MAX_OFFSET && u32(src, ref) == seq) break
                    ip += attempts++ ushr 6
                }
                while (ip > anchor && ref > s0 && src[ip - 1] == src[ref - 1]) {
                    ip--
                    ref--
                }
                var ml = MINMATCH
                while (ip + ml < matchlimit && src[ip + ml] == src[ref + ml]) ml++
                val lit = ip - anchor
                val tok = op++
                if (lit >= 15) {
                    dst[tok] = 0xf0.toByte()
                    var l = lit - 15
                    while (l >= 255) { dst[op++] = 255.toByte(); l -= 255 }
                    dst[op++] = l.toByte()
                } else {
                    dst[tok] = (lit shl 4).toByte()
                }
                System.arraycopy(src, anchor, dst, op, lit)
                op += lit
                val off = ip - ref
                dst[op++] = (off and 0xff).toByte()
                dst[op++] = (off ushr 8).toByte()
                var mcode = ml - MINMATCH
                if (mcode >= 15) {
                    dst[tok] = (dst[tok].toInt() or 15).toByte()
                    mcode -= 15
                    while (mcode >= 255) { dst[op++] = 255.toByte(); mcode -= 255 }
                    dst[op++] = mcode.toByte()
                } else {
                    dst[tok] = (dst[tok].toInt() or mcode).toByte()
                }
                ip += ml
                anchor = ip
                if (ip >= mflimit) break
                table[hash(u32(src, ip - 2))] = ip - 2
            }
        }
        val lit = end - anchor
        val tok = op++
        if (lit >= 15) {
            dst[tok] = 0xf0.toByte()
            var l = lit - 15
            while (l >= 255) { dst[op++] = 255.toByte(); l -= 255 }
            dst[op++] = l.toByte()
        } else {
            dst[tok] = (lit shl 4).toByte()
        }
        System.arraycopy(src, anchor, dst, op, lit)
        op += lit
        return op - d0
    }
}

/**
 * Decodes LZ4 frames pushed in chunks (concatenated and skippable frames
 * included), one block at a time. Holds one input block plus
 * (64 KB window + max block) of output.
 */
class Lz4FrameDecoder(private val sink: Lz4Sink, private val verify: Boolean = false) {
    private enum class St { MAGIC, FLG_BD, HEAD_REST, BLOCK_SIZE, BLOCK, CONTENT_CHECKSUM, SKIP_SIZE, SKIP }

    private var inBuf = ByteArray(1 shl 16)
    private var inLen = 0
    private var st = St.MAGIC
    private var need = 4
    private var flg = 0
    private var bd = 0
    private var blockMax = 0
    private var blockSize = 0
    private var blockRaw = false
    private var skipLeft = 0L
    private var out = ByteArray(0)
    private var outPos = 0
    private var hash: Xxh32? = null

    /** Bytes decoded so far. */
    var produced = 0L
        private set

    private val linked: Boolean get() = (flg and 0x20) == 0

    fun push(chunk: ByteArray, off: Int = 0, len: Int = chunk.size - off) {
        var p = off
        val end = off + len
        while (p < end) {
            if (st == St.SKIP) {
                val k = minOf(skipLeft, (end - p).toLong()).toInt()
                skipLeft -= k
                p += k
                if (skipLeft == 0L) go(St.MAGIC, 4)
                continue
            }
            val n = need
            if (inLen == 0 && end - p >= n) {
                // The whole unit is inside this chunk: no copy.
                p += n
                unit(chunk, p - n)
                continue
            }
            if (inBuf.size < n) inBuf = inBuf.copyOf(maxOf(n, inBuf.size * 2))
            val take = minOf(n - inLen, end - p)
            System.arraycopy(chunk, p, inBuf, inLen, take)
            inLen += take
            p += take
            if (inLen == n) {
                inLen = 0
                unit(inBuf, 0)
            }
        }
    }

    /** Input ended — anywhere but a frame boundary means a cut file. */
    fun end() {
        if (st == St.MAGIC && inLen == 0) return
        throw Lz4Exception("LZ4: file is cut")
    }

    private fun go(s: St, n: Int) {
        st = s
        need = n
    }

    private fun unit(b: ByteArray, p: Int) {
        when (st) {
            St.MAGIC -> {
                val m = u32(b, p)
                when {
                    m == Lz4.MAGIC -> go(St.FLG_BD, 2)
                    Lz4.isSkippable(m) -> go(St.SKIP_SIZE, 4)
                    else -> throw Lz4Exception("LZ4: not a frame magic")
                }
            }
            St.SKIP_SIZE -> {
                skipLeft = u32(b, p).toLong() and 0xffffffffL
                if (skipLeft == 0L) go(St.MAGIC, 4) else go(St.SKIP, 0)
            }
            St.FLG_BD -> {
                val f = b[p].toInt() and 0xff
                if (f ushr 6 != 1) throw Lz4Exception("LZ4: unknown frame version")
                flg = f
                bd = b[p + 1].toInt() and 0xff
                go(St.HEAD_REST, (if (f and 8 != 0) 8 else 0) + (if (f and 1 != 0) 4 else 0) + 1)
            }
            St.HEAD_REST -> {
                val n = need
                val h = ByteArray(1 + n)
                h[0] = flg.toByte()
                h[1] = bd.toByte()
                System.arraycopy(b, p, h, 2, n - 1)
                if (((Xxh32.of(h, 0, n + 1) ushr 8) and 0xff) != (b[p + n - 1].toInt() and 0xff)) {
                    throw Lz4Exception("LZ4: header checksum mismatch")
                }
                val max = Lz4.BLOCK_MAX[(bd ushr 4) and 7]
                if (max == 0) throw Lz4Exception("LZ4: unknown block size")
                blockMax = max
                val size = (if (linked) Lz4.WINDOW else 0) + max
                if (out.size < size) out = ByteArray(size)
                outPos = 0
                hash = if (verify && flg and 4 != 0) Xxh32() else null
                go(St.BLOCK_SIZE, 4)
            }
            St.BLOCK_SIZE -> {
                val bs = u32(b, p)
                if (bs == 0) {
                    if (flg and 4 != 0) go(St.CONTENT_CHECKSUM, 4) else go(St.MAGIC, 4)
                    return
                }
                blockRaw = bs < 0
                blockSize = bs and 0x7fffffff
                if (blockSize > blockMax) throw Lz4Exception("LZ4: block larger than its maximum")
                go(St.BLOCK, blockSize + if (flg and 16 != 0) 4 else 0)
            }
            St.BLOCK -> {
                block(b, p)
                go(St.BLOCK_SIZE, 4)
            }
            St.CONTENT_CHECKSUM -> {
                val hh = hash
                if (hh != null && hh.digest() != u32(b, p)) throw Lz4Exception("LZ4: content checksum mismatch")
                go(St.MAGIC, 4)
            }
            St.SKIP -> throw Lz4Exception("LZ4: state error")
        }
    }

    private fun block(b: ByteArray, p: Int) {
        val lnk = linked
        val o = out
        var start = 0
        if (lnk) {
            // Window: keep only the last 64 KB, shifted to the front.
            if (outPos + blockMax > o.size) {
                val keep = minOf(Lz4.WINDOW, outPos)
                System.arraycopy(o, outPos - keep, o, 0, keep)
                outPos = keep
            }
            start = outPos
        }
        val end = if (blockRaw) {
            System.arraycopy(b, p, o, start, blockSize)
            start + blockSize
        } else {
            Lz4.decodeBlock(b, p, p + blockSize, o, start, start + blockMax, if (lnk) 0 else start)
        }
        outPos = end
        val len = end - start
        produced += len
        hash?.update(o, start, len)
        sink.accept(o, start, len)
    }
}

/**
 * LZ4 frame encoder — independent blocks, no block checksum, content
 * checksum. Feed with [write], close with [finish]. A chunk handed to [sink]
 * is valid only during the call (the buffer is reused).
 */
class Lz4FrameEncoder(private val sink: (ByteArray, Int, Int) -> Unit, blockKB: Int = 4096) {
    private val blockSize = blockKB * 1024
    private val bdId = Lz4.BLOCK_MAX.indexOf(blockSize).also {
        require(it >= 4) { "LZ4 block size must be 64, 256, 1024 or 4096 KB" }
    }
    private val buf = ByteArray(blockSize)
    private var bufLen = 0
    private val table = IntArray(1 shl 16)
    private val hash = Xxh32()
    private val outBuf = ByteArray(4 + Lz4.compressBound(blockSize))
    private var started = false

    /** Compressed bytes emitted. */
    var written = 0L
        private set

    private fun emit(b: ByteArray, off: Int, len: Int) {
        written += len
        sink(b, off, len)
    }

    private fun header() {
        started = true
        val h = ByteArray(7)
        putU32(h, 0, Lz4.MAGIC)
        h[4] = (0x40 or 0x20 or 0x04).toByte() // version 01 · independent blocks · content checksum
        h[5] = (bdId shl 4).toByte()
        h[6] = ((Xxh32.of(h, 4, 2) ushr 8) and 0xff).toByte()
        emit(h, 0, 7)
    }

    fun write(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        if (!started) header()
        hash.update(src, off, len)
        var p = off
        val end = off + len
        while (p < end) {
            val take = minOf(blockSize - bufLen, end - p)
            System.arraycopy(src, p, buf, bufLen, take)
            bufLen += take
            p += take
            if (bufLen == blockSize) flushBlock()
        }
    }

    private fun flushBlock() {
        val n = bufLen
        if (n == 0) return
        val c = Lz4.compressBlock(buf, 0, n, outBuf, 4, table)
        if (c >= n) {
            putU32(outBuf, 0, n or Int.MIN_VALUE)
            System.arraycopy(buf, 0, outBuf, 4, n)
            emit(outBuf, 0, 4 + n)
        } else {
            putU32(outBuf, 0, c)
            emit(outBuf, 0, 4 + c)
        }
        bufLen = 0
    }

    fun finish() {
        if (!started) header()
        flushBlock()
        val tail = ByteArray(8)
        putU32(tail, 4, hash.digest())
        emit(tail, 0, 8)
    }
}

internal fun putU32(b: ByteArray, p: Int, v: Int) {
    b[p] = v.toByte()
    b[p + 1] = (v ushr 8).toByte()
    b[p + 2] = (v ushr 16).toByte()
    b[p + 3] = (v ushr 24).toByte()
}
