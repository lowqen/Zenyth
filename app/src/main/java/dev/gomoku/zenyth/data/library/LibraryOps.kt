package dev.gomoku.zenyth.data.library

import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.library.job.BaseSpec
import dev.gomoku.zenyth.data.library.job.JobOutcome
import dev.gomoku.zenyth.data.library.job.JobResult
import dev.gomoku.zenyth.data.library.job.LibraryJob
import dev.gomoku.zenyth.data.library.job.SplitOutSpec
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.FileStat
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryFileNames
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitBase
import dev.gomoku.zenyth.domain.library.yxdb.SplitPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** An op's answer: done, cancelled (nothing left behind), or why not. */
sealed interface OpResult<out T> {
    data class Done<T>(val value: T) : OpResult<T>
    data object Cancelled : OpResult<Nothing>
    data class Failed(val message: String) : OpResult<Nothing>
}

/** One entry an op reads, with the source it lives in. */
class OpInput(val source: LibrarySource, val entry: LibraryEntry)

/** A file an op hands to a job: [size] as measured when the op looked. */
class OpFile(val source: LibrarySource, val name: String, val size: Long)

/**
 * Runs a [LibraryJob] on files (L5-7): the device opens descriptors and sends
 * them to `:library` ([dev.gomoku.zenyth.data.library.job.JobOpRunner]); tests
 * run [dev.gomoku.zenyth.data.library.job.LibraryJobRunner] in place. Outputs
 * are temp files of atomic writes — the job writes them, then reads them back.
 * Cancelling the caller cancels the job.
 */
fun interface LibraryOpRunner {
    suspend fun run(job: LibraryJob, inputs: List<OpFile>, outputs: List<StagedFile>): JobOutcome
}

/** [LibraryOps.previewMerge]'s count, carried to [LibraryOps.commitMerge]. */
data class MergePrepared(
    val inputs: List<OpInput>,
    val policy: MergePolicy,
    val joinComments: Boolean,
    val summary: JobResult.Merged,
    /** Identities when counted — a file that moved since is counted again. */
    val seen: List<Fingerprint>,
)

/** [LibraryOps.previewSplit]'s count. [base] = the «이 국면 아래» position it counted under. */
data class SplitPrepared(
    val input: OpInput,
    val counts: JobResult.SplitCounted,
    val base: SplitBase?,
    val seen: Fingerprint,
)

/** One result of a split: its plan row and the entry it becomes. */
data class SplitResultSpec(val out: SplitOutSpec, val title: String, val cover: LibraryCover)

/**
 * [LibraryOps.commitSplit]: the new entries; on a move, the original's entry
 * (same id, the rest's identity, stats dropped) and the trashed backup of the
 * original («… (나누기 전)», restorable for 30 days).
 */
data class SplitDone(val made: List<LibraryEntry>, val replaced: LibraryEntry?, val backup: LibraryEntry?)

/**
 * Merge · split between library entries (L5-7 — web `libraryOps.ts`).
 *
 * - **Inputs rest.** An attached entry is folded first ([fold]: the engine's
 *   `YXCOMPACTDATABASE`, and a folder entry's staging copy goes home), then any
 *   journal still holding saves refuses the op — its records would be missed.
 * - **Originals stay** unless asked: results are new entries. A split «move»
 *   writes the rest beside the results, moves the original to the trash (its
 *   backup, restorable for 30 days) and renames the rest into its place.
 * - **All or nothing.** Results are written through nested atomic writes; a
 *   failure (or a cancel) after some were placed deletes those again, so the
 *   source holds either every result or none.
 *
 * Sidecars are the caller's (like [LibraryTransfer]): it writes the returned
 * entries under the source's lock.
 */
class LibraryOps(
    private val runner: LibraryOpRunner,
    /** Before reading an entry: fold it if attached, send its staging copy home. Null or why not. */
    private val fold: suspend (entryId: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ── merge ──────────────────────────────────────────────────────────

    /** ③ 미리 보기 — [inputs] top first (the base, rank 0). */
    suspend fun previewMerge(inputs: List<OpInput>, policy: MergePolicy, joinComments: Boolean): OpResult<MergePrepared> = guard {
        require(inputs.size >= 2) { "merge: ${inputs.size} inputs" }
        val seen = inputs.map { resting(it) }
        val job = LibraryJob.MergeCount(seen.map { it.size }, inputs.indices.toList(), policy, joinComments)
        when (val out = runner.run(job, files(inputs, seen), emptyList())) {
            is JobOutcome.Done -> {
                val r = out.result as? JobResult.Merged ?: return@guard failed("merge: ${out.result}")
                OpResult.Done(MergePrepared(inputs, policy, joinComments, r, seen))
            }
            else -> outcome(out)
        }
    }

    /**
     * [합치기] — the merged file as a new entry in [target] (the base's source),
     * titled [title], with [cover]. [taken] = the target's entries (file names a
     * folder result must not take).
     */
    suspend fun commitMerge(
        prep: MergePrepared,
        title: String,
        cover: LibraryCover,
        target: LibrarySource,
        taken: List<LibraryEntry>,
    ): OpResult<LibraryEntry> = guard {
        val now = prep.inputs.map { resting(it) }
        if (now.zip(prep.seen).any { (a, b) -> !a.sameFile(b) }) return@guard changed()
        val made = LibraryEntries.newEntry(title, "", cover.mode, clock()).copy(cover = cover)
        val name = names(target, listOf(made.id to title), taken).single()
        val job = LibraryJob.MergeWrite(
            sizes = now.map { it.size },
            ranks = prep.inputs.indices.toList(),
            count = prep.summary.records,
            unsorted = prep.summary.unsorted,
            policy = prep.policy,
            joinComments = prep.joinComments,
        )
        var failure: OpResult<Nothing>? = null
        val placed = placeAll(target, listOf(name)) { staged ->
            when (val out = runner.run(job, files(prep.inputs, now), staged)) {
                is JobOutcome.Done -> Unit
                else -> {
                    failure = outcome(out)
                    throw Abort()
                }
            }
        } ?: return@guard failure ?: failed("merge")
        val st = placed.single()
        OpResult.Done(made.copy(file = name, fingerprint = fingerprintOf(target, st), updatedAt = st.mtime))
    }

    // ── split ──────────────────────────────────────────────────────────

    /** ① 기준 — every criterion's counts at once (openings need the index: [maxIndexBytes]). */
    suspend fun previewSplit(input: OpInput, base: SplitBase?, maxIndexBytes: Long): OpResult<SplitPrepared> = guard {
        val seen = resting(input)
        val job = LibraryJob.SplitCount(seen.size, base?.let(BaseSpec::of), withOpenings = true, maxIndexBytes = maxIndexBytes)
        when (val out = runner.run(job, files(listOf(input), listOf(seen)), emptyList())) {
            is JobOutcome.Done -> {
                val r = out.result as? JobResult.SplitCounted ?: return@guard failed("split: ${out.result}")
                OpResult.Done(SplitPrepared(input, r, base, seen))
            }
            else -> outcome(out)
        }
    }

    /**
     * [나누기] — each of [results] as a new entry beside the original. With
     * [move], the rest replaces the original's file (its copy goes to the
     * trash first). [attachedId]: a move of the attached entry is refused.
     */
    suspend fun commitSplit(
        prep: SplitPrepared,
        plan: SplitPlan,
        results: List<SplitResultSpec>,
        move: Boolean,
        taken: List<LibraryEntry>,
        maxIndexBytes: Long,
        attachedId: String?,
    ): OpResult<SplitDone> = guard {
        require(results.size == plan.outputs.size) { "split: ${results.size} names for ${plan.outputs.size} results" }
        require(move == (plan.rest != null)) { "split: a move needs the rest" }
        require(results.isNotEmpty()) { "split: no results" }
        val e = prep.input.entry
        val src = prep.input.source
        if (move && e.id == attachedId) {
            return@guard OpResult.Failed(tr("붙어 있는 DB 에서는 옮길 수 없습니다. 복사로 나누세요", "The attached DB cannot be split by moving — split by copying", "已连接的数据库不能移出拆分，请用复制拆分"))
        }
        val now = resting(prep.input)
        if (!now.sameFile(prep.seen)) return@guard changed()
        val stamp = clock()
        val made = results.map { r -> LibraryEntries.newEntry(r.title, "", r.cover.mode, stamp).copy(cover = r.cover) }
        val restTitle = tr("${e.title} (남는 것)", "${e.title} (rest)", "${e.title} (剩余)")
        val restId = LibraryEntries.newId()
        // The rest goes beside the results under a name of its own; it takes the original's
        // name only once the original is in the trash (a name being written cannot be trashed).
        val all = names(src, made.map { it.id to it.title } + (if (move) listOf(restId to restTitle) else emptyList()), taken)
        val resultNames = all.take(made.size)
        val job = LibraryJob.SplitWrite(
            size = now.size,
            criterion = plan.criterion,
            results = results.map { it.out },
            rest = plan.rest,
            base = plan.base?.let(BaseSpec::of),
            meta = prep.counts.meta,
            maxIndexBytes = maxIndexBytes,
        )
        var failure: OpResult<Nothing>? = null
        // Job slots: results first, then the rest — the order of [all].
        val placed = placeAll(src, all) { staged ->
            when (val out = runner.run(job, files(listOf(prep.input), listOf(now)), staged)) {
                is JobOutcome.Done -> Unit
                else -> {
                    failure = outcome(out)
                    throw Abort()
                }
            }
        } ?: return@guard failure ?: failed("split")
        val stats = all.zip(placed).toMap()
        var replaced: LibraryEntry? = null
        var backup: LibraryEntry? = null
        if (move) {
            val restName = all.last()
            val token = try {
                val t = src.trash(e.file)
                try {
                    src.rename(restName, e.file)
                } catch (x: Throwable) {
                    withContext(NonCancellable) { runCatching { src.restore(t, e.file) } }
                    throw x
                }
                t
            } catch (x: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { for (n in all) runCatching { src.delete(n) } }
                throw x
            }
            val fp = src.fingerprint(e.file) ?: throw IllegalStateException("rest ${e.file} is gone")
            val mtime = src.stat(e.file)?.mtime ?: stats.getValue(restName).mtime
            replaced = e.copy(fingerprint = fp.copy(mtime = mtime), updatedAt = mtime).withStats(null)
            val bid = LibraryEntries.newId()
            backup = e.copy(
                id = bid,
                title = tr("${e.title} (나누기 전)", "${e.title} (before split)", "${e.title} (拆分前)"),
                // App storage names files by id: the backup comes back under its own.
                file = if (src.kind == SourceKind.APP) AppDirSource.fileNameOf(bid) else e.file,
                createdAt = stamp,
                updatedAt = stamp,
                lastOpenedAt = null,
                trashedAt = stamp,
                trashFile = token,
                missingSince = null,
                fileRenamePending = null,
            )
        }
        val entries = made.mapIndexed { i, m ->
            val st = stats.getValue(resultNames[i])
            m.copy(file = resultNames[i], fingerprint = fingerprintOf(src, st), updatedAt = st.mtime)
        }
        OpResult.Done(SplitDone(entries, replaced, backup))
    }

    // ── shared ─────────────────────────────────────────────────────────

    /** Folds [input] if needed and checks it rests: a file, and no journal holding saves. Throws [Refusal]. */
    private suspend fun resting(input: OpInput): Fingerprint {
        val e = input.entry
        if (input.source.access.value != SourceAccess.GRANTED) {
            throw Refusal(tr("«${e.title}» 의 폴더를 다시 허용해야 합니다", "«${e.title}»'s folder needs access again", "需要重新授权«${e.title}»所在的文件夹"))
        }
        fold(e.id)?.let { throw Refusal(tr("«${e.title}» 을(를) 접어 저장하지 못했습니다: $it", "Could not fold «${e.title}»: $it", "无法合并保存«${e.title}»: $it")) }
        val fp = input.source.fingerprint(e.file)
        if (fp == null || fp.size == 0L) {
            throw Refusal(tr("«${e.title}» 은(는) 아직 저장된 파일이 없습니다", "«${e.title}» has no saved file yet", "«${e.title}»还没有已保存的文件"))
        }
        val journal = input.source.stat(e.file + JOURNAL)
        if (journal != null && journal.size > LibraryRepository.JOURNAL_HEADER) {
            throw Refusal(tr("«${e.title}» 의 저널에 접히지 않은 변경이 있습니다. 엔진을 켜고 DB 를 붙인 뒤 다시 하세요", "«${e.title}»'s journal holds changes not folded in — start the engine, attach the DB, then try again", "«${e.title}»的日志中有尚未合并的更改，请启动引擎并连接数据库后重试"))
        }
        return fp
    }

    private fun files(inputs: List<OpInput>, seen: List<Fingerprint>) =
        inputs.zip(seen).map { (i, fp) -> OpFile(i.source, i.entry.file, fp.size) }

    /** File names for new entries: app storage `<id>.db`, a folder the title past the names taken. */
    private suspend fun names(src: LibrarySource, made: List<Pair<String, String>>, taken: List<LibraryEntry>): List<String> {
        if (src.kind == SourceKind.APP) return made.map { AppDirSource.fileNameOf(it.first) }
        val used = (src.list().map { it.name } + taken.filter { it.trashedAt == null }.map { it.file }).toMutableList()
        return made.map { (_, title) -> LibraryFileNames.fileNameFor(title, used).also { used += it } }
    }

    private suspend fun fingerprintOf(src: LibrarySource, st: FileStat): Fingerprint {
        val fp = src.fingerprint(st.name) ?: throw IllegalStateException("placed ${st.name} is gone")
        return fp.copy(mtime = st.mtime)
    }

    /**
     * Writes [names] in [src] at once: one atomic write inside the next, so
     * [write] gets every temp file (in [names] order) and the files commit
     * innermost first. A failure — or a cancel — after some committed deletes
     * those. Null when [write] threw [Abort] (the caller kept the reason).
     */
    private suspend fun placeAll(
        src: LibrarySource,
        names: List<String>,
        write: suspend (List<StagedFile>) -> Unit,
    ): List<FileStat>? {
        val committed = ArrayList<FileStat>()
        suspend fun nest(i: Int, staged: List<StagedFile>) {
            if (i == names.size) {
                write(staged)
                return
            }
            val st = src.writeAtomic(names[i]) { s -> nest(i + 1, staged + s) }
            committed += st
        }
        return try {
            nest(0, emptyList())
            names.map { n -> committed.first { it.name == n || LibraryFileNames.key(it.name) == LibraryFileNames.key(n) } }
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                for (st in committed) runCatching { src.delete(st.name) }
            }
            if (t is Abort) null else throw t
        }
    }

    /** Runs an op body: cancels and refusals become results, other failures a message. */
    private suspend fun <T> guard(body: suspend () -> OpResult<T>): OpResult<T> = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (r: Refusal) {
        OpResult.Failed(r.message!!)
    } catch (t: Throwable) {
        OpResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName)
    }

    private fun outcome(out: JobOutcome): OpResult<Nothing> = when (out) {
        JobOutcome.Cancelled -> OpResult.Cancelled
        is JobOutcome.Failed -> OpResult.Failed(
            if (out.outOfMemory) tr("메모리가 모자라 멈췄습니다", "Stopped — not enough memory", "内存不足，已停止") else out.message,
        )
        JobOutcome.Died -> OpResult.Failed(tr("작업 프로세스가 멈췄습니다(메모리 부족일 수 있습니다)", "The job process stopped (possibly out of memory)", "处理进程已停止（可能是内存不足）"))
        is JobOutcome.Done -> OpResult.Failed("unexpected ${out.result}")
    }

    private fun failed(what: String) = OpResult.Failed(what)

    private fun changed() = OpResult.Failed(tr("미리 본 뒤에 DB 가 바뀌었습니다. 다시 미리 보세요", "A DB changed after the preview — preview again", "预览后数据库已更改，请重新预览"))

    private class Refusal(message: String) : Exception(message)
    private class Abort : Exception()

    companion object {
        private const val JOURNAL = ".journal"

        /** The merge's default title (web `defaultMergeTitle`): up to three names joined, else two «외 n». */
        fun defaultMergeTitle(titles: List<String>): String =
            if (titles.size <= 3) titles.joinToString(" + ")
            else tr("${titles.take(2).joinToString(" + ")} 외 ${titles.size - 2}", "${titles.take(2).joinToString(" + ")} and ${titles.size - 2} more", "${titles.take(2).joinToString(" + ")} 等 ${titles.size - 2} 个")
    }
}
