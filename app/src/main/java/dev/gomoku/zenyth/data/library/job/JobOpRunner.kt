package dev.gomoku.zenyth.data.library.job

import android.os.ParcelFileDescriptor
import dev.gomoku.zenyth.data.library.LibraryOpRunner
import dev.gomoku.zenyth.data.library.OpFile
import dev.gomoku.zenyth.data.library.StagedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryOpRunner] on the `:library` process (L5-7): this process holds the
 * SAF grants, so it opens every descriptor — inputs `r`, each output `wt` and
 * its read-back `r` — and hands them over when the job's turn comes (closed
 * here if it never comes, like [JobCopier]).
 */
@Singleton
class JobOpRunner @Inject constructor(private val client: LibraryJobClient) : LibraryOpRunner {
    override suspend fun run(job: LibraryJob, inputs: List<OpFile>, outputs: List<StagedFile>): JobOutcome {
        val opened = ArrayList<ParcelFileDescriptor>()
        var handed = false
        try {
            val ins = inputs.map { f -> (f.source.openFd(f.name, "r") ?: throw FileNotFoundException(f.name)).also { opened += it } }
            val (outs, verifies) = withContext(Dispatchers.IO) {
                val o = outputs.map { s -> s.openFd("wt").also { opened += it } }
                val v = if (job.verifies > 0) outputs.map { s -> s.openFd("r").also { opened += it } } else emptyList()
                o to v
            }
            return client.run(job, {
                handed = true
                JobFds(ins, outs, verifies)
            })
        } catch (e: FileNotFoundException) {
            return JobOutcome.Failed(e.message ?: "file not found")
        } finally {
            if (!handed) for (pfd in opened) runCatching { pfd.close() }
        }
    }
}
