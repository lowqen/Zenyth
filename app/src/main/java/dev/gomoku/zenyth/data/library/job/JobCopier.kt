package dev.gomoku.zenyth.data.library.job

import dev.gomoku.zenyth.data.library.CopyFrom
import dev.gomoku.zenyth.data.library.CopyTo
import dev.gomoku.zenyth.data.library.LibraryCopier
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryCopier] on the `:library` process (design §2.2 `copy`): this process
 * opens both descriptors — it holds the SAF grants — and the job copies,
 * computing the tail identity on the way. Queued behind other jobs.
 */
@Singleton
class JobCopier @Inject constructor(private val client: LibraryJobClient) : LibraryCopier {
    override suspend fun copy(size: Long, from: CopyFrom, to: CopyTo): String {
        // Opened here (suspending, on this side's grants) and handed over when the
        // job's turn comes; closed here if it never comes.
        val input = from.fd()
        val output = try {
            to.fd()
        } catch (t: Throwable) {
            input.close()
            throw t
        }
        var handed = false
        val out = try {
            client.run(LibraryJob.Copy(size), {
                handed = true
                JobFds(listOf(input), listOf(output))
            })
        } finally {
            if (!handed) {
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
        val r = (out as? JobOutcome.Done)?.result as? JobResult.Copied
            ?: throw IOException("copy: $out")
        if (r.bytes != size) throw IOException("copied ${r.bytes} of $size bytes")
        return r.tail
    }
}
