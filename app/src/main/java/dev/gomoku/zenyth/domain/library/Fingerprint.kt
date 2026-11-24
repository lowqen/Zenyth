package dev.gomoku.zenyth.domain.library

/**
 * A library file's identity — size plus FNV-1a 64 over its last 4 KB
 * (Zenyth-web `data/library.ts` `fingerprintOf`, the same function the engine's
 * DB6 journal uses). `mtime` is only a hint for "maybe changed": two
 * fingerprints are the same file when size and tail match.
 */
data class Fingerprint(val size: Long, val mtime: Long, val tail: String) {
    fun sameFile(other: Fingerprint): Boolean = size == other.size && tail == other.tail

    companion object {
        const val TAIL_BYTES = 4096

        /** [tailBytes] = the last `min(size, 4096)` bytes of the file. */
        fun of(size: Long, mtime: Long, tailBytes: ByteArray, off: Int = 0, len: Int = tailBytes.size - off) =
            Fingerprint(size, mtime, hex16(Fnv1a64.of(tailBytes, off, len)))

        fun hex16(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')
    }
}

object Fnv1a64 {
    const val OFFSET = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
    const val PRIME = 0x100000001b3L

    fun of(b: ByteArray, off: Int = 0, len: Int = b.size - off): Long {
        var h = OFFSET
        for (i in off until off + len) {
            h = (h xor (b[i].toLong() and 0xff)) * PRIME
        }
        return h
    }
}
