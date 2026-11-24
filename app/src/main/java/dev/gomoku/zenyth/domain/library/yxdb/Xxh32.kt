package dev.gomoku.zenyth.domain.library.yxdb

/**
 * Streaming xxHash32 — the LZ4 frame header and content checksums
 * (Zenyth-web `lz4frame.ts`, same vectors: `""` → 02cc5d05, `"abc"` → 32d153ff).
 */
class Xxh32(private val seed: Int = 0) {
    private var v1 = seed + P1 + P2
    private var v2 = seed + P2
    private var v3 = seed
    private var v4 = seed - P1
    private var total = 0L
    private val mem = ByteArray(16)
    private var memLen = 0

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size - off): Xxh32 {
        total += len
        var p = off
        val end = off + len
        if (memLen > 0) {
            val take = minOf(16 - memLen, len)
            System.arraycopy(b, p, mem, memLen, take)
            memLen += take
            p += take
            if (memLen < 16) return this
            v1 = round(v1, u32(mem, 0))
            v2 = round(v2, u32(mem, 4))
            v3 = round(v3, u32(mem, 8))
            v4 = round(v4, u32(mem, 12))
            memLen = 0
        }
        var a = v1
        var bb = v2
        var c = v3
        var d = v4
        while (p + 16 <= end) {
            a = round(a, u32(b, p))
            bb = round(bb, u32(b, p + 4))
            c = round(c, u32(b, p + 8))
            d = round(d, u32(b, p + 12))
            p += 16
        }
        v1 = a; v2 = bb; v3 = c; v4 = d
        if (p < end) {
            System.arraycopy(b, p, mem, 0, end - p)
            memLen = end - p
        }
        return this
    }

    fun digest(): Int {
        var h = if (total >= 16) {
            Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7) +
                Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18)
        } else {
            seed + P5
        }
        h += total.toInt()
        var p = 0
        while (p + 4 <= memLen) {
            h = Integer.rotateLeft(h + u32(mem, p) * P3, 17) * P4
            p += 4
        }
        while (p < memLen) {
            h = Integer.rotateLeft(h + (mem[p].toInt() and 0xff) * P5, 11) * P1
            p++
        }
        h = h xor (h ushr 15)
        h *= P2
        h = h xor (h ushr 13)
        h *= P3
        h = h xor (h ushr 16)
        return h
    }

    companion object {
        const val P1 = -0x61c8864f   // 2654435761
        const val P2 = -0x7a143589   // 2246822519
        const val P3 = -0x3d4d51c3   // 3266489917
        const val P4 = 0x27d4eb2f    // 668265263
        const val P5 = 0x165667b1    // 374761393

        private fun round(acc: Int, w: Int): Int = Integer.rotateLeft(acc + w * P2, 13) * P1

        fun of(b: ByteArray, off: Int = 0, len: Int = b.size - off, seed: Int = 0): Int =
            Xxh32(seed).update(b, off, len).digest()
    }
}

internal fun u32(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
        ((b[p + 2].toInt() and 0xff) shl 16) or (b[p + 3].toInt() shl 24)

internal fun u16(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
