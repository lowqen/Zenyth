package dev.gomoku.zenyth.feature.library

import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.data.library.job.JobResult
import dev.gomoku.zenyth.data.library.job.SplitOutSpec
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.SplitBuckets
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion

/** One DB in the merge order (row 0 = the base). */
data class MergeRow(val id: String, val title: String, val cover: CoverKey, val seed: Int, val subtitle: String, val rule: String?)

/** The merge preview's numbers (step ③). */
data class MergeCounts(val records: Long, val positions: Long, val overlaps: Long, val differing: Long, val inputs: List<Long>)

/**
 * The merge sheet (design §6.5, web `MergeSheet`): ① 순서 (▲▼, the top is the
 * base) → ② 겹칠 때 → ③ 미리 보기 (counted before anything is written).
 */
data class MergeUi(
    val rows: List<MergeRow>,
    val step: Int = 1,
    val policy: MergePolicy = MergePolicy.DEEPER,
    val joinComments: Boolean = true,
    /** The attached DB is one of them: it is folded first. */
    val hasAttached: Boolean = false,
    /** Counting the preview. */
    val counting: Boolean = false,
    val counts: MergeCounts? = null,
    val title: String = "",
    val trashOriginals: Boolean = false,
    /** Writing. */
    val running: Boolean = false,
    val error: String? = null,
) {
    /** Rules · boards differ among them — mergeable (the key holds the rule), but worth a word. */
    val mixedRules: Boolean get() = rows.mapNotNull { it.rule }.toSet().size > 1
    val busy: Boolean get() = counting || running
}

/** One planned split result (step ③): its buckets, count, name and cover. */
data class PlannedOut(val out: SplitOutSpec, val title: String, val cover: LibraryCover)

/**
 * The split sheet (design §6.5, web `SplitSheet`): ① 기준 (counts from one
 * pass when the sheet opens) → ② 나머지 (copy, or move out of the original) →
 * ③ 미리 보기 with the check «결과 + 남는 것 = 원본 ✓».
 */
data class SplitUi(
    val id: String,
    val title: String,
    val cover: LibraryCover,
    val attached: Boolean,
    val step: Int = 1,
    val counting: Boolean = true,
    val counts: JobResult.SplitCounted? = null,
    /** Stones on the board «이 국면 아래» counted under (0: an empty board — nothing to count). */
    val baseStones: Int = 0,
    val criterion: SplitCriterion = SplitCriterion.SOLVED,
    /** Openings picked (0..25, 26 unclassified). */
    val picked: Set<Int> = emptySet(),
    /** The picked openings into one file instead of one each. */
    val grouped: Boolean = false,
    /** Rule · board keys picked. */
    val rules: Set<String> = emptySet(),
    val move: Boolean = false,
    /** Names the user typed, by planned index. */
    val names: Map<Int, String> = emptyMap(),
    val confirmMove: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = counting || running
}

/** Turns the split sheet's choices into results (pure — the sheet, the view model and the tests share it). */
object SplitPlanner {
    /** Bucket 26 — what the index could not classify (web `NONSTD`). */
    const val UNCLASSIFIED = Opening26.COUNT

    fun openingName(i: Int): String = if (i == UNCLASSIFIED) tr("미분류", "Unclassified", "未分类") else Opening26.name(i)

    /** The criteria this count can offer, with their counts (null: not available). */
    fun available(c: JobResult.SplitCounted, baseStones: Int): Map<SplitCriterion, Long?> = linkedMapOf(
        SplitCriterion.OPENING to c.openings?.let { o -> o.take(UNCLASSIFIED).sum() },
        SplitCriterion.UNDER to c.under?.takeIf { baseStones > 0 },
        SplitCriterion.SOLVED to c.solved,
        SplitCriterion.RULE to c.rules.size.takeIf { it > 1 }?.let { c.total },
    )

    /** The sheet's opening defaults: every classified opening with records, by opening; else solved. */
    fun defaults(ui: SplitUi, c: JobResult.SplitCounted): SplitUi {
        val o = c.openings
        return if (o != null && o.take(UNCLASSIFIED).any { it > 0 }) {
            ui.copy(
                criterion = SplitCriterion.OPENING,
                picked = o.indices.filter { it != UNCLASSIFIED && o[it] > 0 }.toSet(),
                rules = c.rules.keys,
            )
        } else {
            ui.copy(criterion = SplitCriterion.SOLVED, rules = c.rules.keys)
        }
    }

    fun planned(ui: SplitUi): List<PlannedOut> {
        val c = ui.counts ?: return emptyList()
        fun name(s: String) = "${ui.title} · $s"
        val planned = when (ui.criterion) {
            SplitCriterion.OPENING -> {
                val o = c.openings ?: return emptyList()
                val ids = ui.picked.sorted().filter { o.getOrElse(it) { 0L } > 0 }
                if (ids.isEmpty()) {
                    emptyList()
                } else if (ui.grouped) {
                    listOf(
                        PlannedOut(
                            SplitOutSpec(openings = ids, count = ids.sumOf { o[it] }),
                            name(tr("주형 ${ids.size}개", "${ids.size} openings", "${ids.size} 个开局")),
                            LibraryCover(LibraryCover.AUTO, seed = ui.cover.seed),
                        ),
                    )
                } else {
                    ids.map { i ->
                        PlannedOut(
                            SplitOutSpec(openings = listOf(i), count = o[i]),
                            name(openingName(i)),
                            if (i == UNCLASSIFIED) LibraryCover(LibraryCover.ECLIPSE, seed = ui.cover.seed)
                            else LibraryCover(LibraryCover.OPENING, opening = i, seed = ui.cover.seed),
                        )
                    }
                }
            }
            SplitCriterion.UNDER -> {
                val n = c.under ?: 0L
                if (n > 0 && ui.baseStones > 0) {
                    listOf(PlannedOut(SplitOutSpec(names = listOf(SplitBuckets.IN), count = n), name(tr("${ui.baseStones}수 국면 아래", "under the ${ui.baseStones}-move position", "${ui.baseStones} 手局面之下")), ui.cover))
                } else {
                    emptyList()
                }
            }
            SplitCriterion.SOLVED ->
                if (c.solved > 0) listOf(PlannedOut(SplitOutSpec(names = listOf(SplitBuckets.SOLVED), count = c.solved), name(tr("풀린 국면", "solved", "已解局面")), ui.cover))
                else emptyList()
            SplitCriterion.RULE -> c.rules.keys.filter { it in ui.rules && (c.rules[it] ?: 0L) > 0 }.map { k ->
                PlannedOut(SplitOutSpec(names = listOf(k), count = c.rules.getValue(k)), name(LibraryFormat.rule(k)), ui.cover)
            }
        }
        return planned.mapIndexed { i, p -> ui.names[i]?.trim()?.takeIf { it.isNotEmpty() }?.let { p.copy(title = it) } ?: p }
    }

    /** What stays in no result — the original's remainder on a move. */
    fun rest(ui: SplitUi, planned: List<PlannedOut> = planned(ui)): Long = (ui.counts?.total ?: 0L) - planned.sumOf { it.out.count }
}
