package dev.gomoku.zenyth.data.library

import android.os.ParcelFileDescriptor
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.yxdb.Yxdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One end of a copy: the job process opens it by descriptor, tests by stream. */
class CopyFrom(val stream: suspend () -> InputStream, val fd: suspend () -> ParcelFileDescriptor)
class CopyTo(val stream: suspend () -> OutputStream, val fd: suspend () -> ParcelFileDescriptor)

/**
 * The byte copy of staging and write-back (L5-4): `size` bytes from → to,
 * returning the tail identity of what went across (FNV-1a 64 of the last
 * 4 KB, as [Fingerprint]). Throws when it did not finish — the caller's atomic
 * write then leaves the target as it was.
 *
 * On a device this is the `:library` job ([dev.gomoku.zenyth.data.library.job.JobCopier]),
 * so a 100 MB copy neither shares this process's heap nor outlives a cancel.
 */
fun interface LibraryCopier {
    suspend fun copy(size: Long, from: CopyFrom, to: CopyTo): String
}

/** In this process, over streams — tests, and anything too small to send away. */
object StreamCopier : LibraryCopier {
    override suspend fun copy(size: Long, from: CopyFrom, to: CopyTo): String = withContext(Dispatchers.IO) {
        val tail = TailRing()
        var total = 0L
        from.stream().use { input ->
            to.stream().use { out ->
                val buf = ByteArray(Yxdb.CHUNK)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    tail.add(buf, r)
                    total += r
                }
            }
        }
        if (total != size) throw IOException("copied $total of $size bytes")
        tail.hex()
    }

    /** The last 4 KB seen, as a ring. */
    private class TailRing {
        private val ring = ByteArray(Fingerprint.TAIL_BYTES)
        private var pos = 0
        private var seen = 0L

        fun add(b: ByteArray, n: Int) {
            var i = maxOf(0, n - ring.size)
            while (i < n) {
                val k = minOf(n - i, ring.size - pos)
                System.arraycopy(b, i, ring, pos, k)
                i += k
                pos = (pos + k) % ring.size
            }
            seen += n
        }

        fun hex(): String {
            val len = minOf(seen, ring.size.toLong()).toInt()
            val last = ByteArray(len)
            val start = (pos - len + ring.size) % ring.size
            for (j in 0 until len) last[j] = ring[(start + j) % ring.size]
            return Fingerprint.of(seen, 0, last).tail
        }
    }
}
