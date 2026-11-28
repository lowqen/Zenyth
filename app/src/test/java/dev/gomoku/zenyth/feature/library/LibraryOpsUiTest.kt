package dev.gomoku.zenyth.feature.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.data.library.job.JobResult
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryPositions
import dev.gomoku.zenyth.domain.library.yxdb.DbLabels
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.SplitBuckets
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The pick mode's rules and the split sheet's plan (L5-7) — pure, no Android. */
class LibraryOpsUiTest {

    private val saved = Locale.getDefault()

    @Before fun korean() = Locale.setDefault(Locale.KOREAN)

    @After fun restore() = Locale.setDefault(saved)

    // ── pick mode ──────────────────────────────────────────────────────

    private fun card(id: String, hasFile: Boolean = true, attached: Boolean = false, working: Boolean = false) =
        LibraryCard(id, id, CoverKey(1), 0, "", attached = attached, working = working, hasFile = hasFile)

    private val cards = listOf(card("a", attached = true), card("b"), card("c", hasFile = false), card("d", working = true))

    @Test
    fun `merge needs two picks with files, none busy`() {
        val ui = LibraryUiState(cards = cards)
        assertThat(ui.copy(picked = listOf("a")).canMerge).isFalse()
        assertThat(ui.copy(picked = listOf("a", "b")).canMerge).isTrue()
        assertThat(ui.copy(picked = listOf("a", "c")).canMerge).isFalse()
        assertThat(ui.copy(picked = listOf("b", "d")).canMerge).isFalse()
    }

    @Test
    fun `split needs exactly one pick with a file`() {
        val ui = LibraryUiState(cards = cards)
        assertThat(ui.copy(picked = listOf("b")).canSplit).isTrue()
        assertThat(ui.copy(picked = listOf("c")).canSplit).isFalse()
        assertThat(ui.copy(picked = listOf("a", "b")).canSplit).isFalse()
    }

    @Test
    fun `delete is offered while one pick can go — the attached one stays`() {
        val ui = LibraryUiState(cards = cards)
        assertThat(ui.copy(picked = listOf("a")).canTrash).isFalse()
        assertThat(ui.copy(picked = listOf("a", "b")).canTrash).isTrue()
        assertThat(ui.picking).isFalse()
        assertThat(ui.copy(picked = listOf("a")).picking).isTrue()
    }

    // ── split plan ─────────────────────────────────────────────────────

    private val counts = JobResult.SplitCounted(
        total = 100,
        meta = true,
        openings = List(27) { i -> when (i) { 1 -> 40L; 4 -> 30L; 26 -> 10L; else -> 0L } },
        under = 12,
        solved = 25,
        rules = linkedMapOf("renju-15" to 90L, "freestyle-15" to 10L),
    )

    private fun ui() = SplitUi("x", "연구", LibraryCover(LibraryCover.AUTO, seed = 5), attached = false, counting = false, counts = counts, baseStones = 3)

    @Test
    fun `the default is every classified opening, one file each`() {
        val u = SplitPlanner.defaults(ui(), counts)
        assertThat(u.criterion).isEqualTo(SplitCriterion.OPENING)
        assertThat(u.picked).containsExactly(1, 4)
        val p = SplitPlanner.planned(u)
        assertThat(p.map { it.out.openings }).containsExactly(listOf(1), listOf(4)).inOrder()
        assertThat(p.map { it.out.count }).containsExactly(40L, 30L).inOrder()
        assertThat(p[0].cover.mode).isEqualTo(LibraryCover.OPENING)
        assertThat(p[0].cover.opening).isEqualTo(1)
        assertThat(p[0].title).startsWith("연구 · ")
        assertThat(SplitPlanner.rest(u)).isEqualTo(30)
    }

    @Test
    fun `grouped openings make one file, unclassified gets an eclipse`() {
        val u = SplitPlanner.defaults(ui(), counts).copy(picked = setOf(1, 4, 26))
        assertThat(SplitPlanner.planned(u.copy(grouped = true)).single().out.count).isEqualTo(80)
        val each = SplitPlanner.planned(u)
        assertThat(each.last().cover.mode).isEqualTo(LibraryCover.ECLIPSE)
        assertThat(each.last().title).isEqualTo("연구 · 미분류")
    }

    @Test
    fun `results plus the rest are the original`() {
        for (c in SplitCriterion.entries) {
            val u = SplitPlanner.defaults(ui(), counts).copy(criterion = c)
            val p = SplitPlanner.planned(u)
            assertThat(p.sumOf { it.out.count } + SplitPlanner.rest(u, p)).isEqualTo(counts.total)
        }
    }

    @Test
    fun `under, solved and rule criteria name their buckets`() {
        val base = SplitPlanner.defaults(ui(), counts)
        assertThat(SplitPlanner.planned(base.copy(criterion = SplitCriterion.UNDER)).single().out.names).containsExactly(SplitBuckets.IN)
        assertThat(SplitPlanner.planned(base.copy(criterion = SplitCriterion.UNDER, baseStones = 0))).isEmpty()
        assertThat(SplitPlanner.planned(base.copy(criterion = SplitCriterion.SOLVED)).single().out.count).isEqualTo(25)
        val rules = SplitPlanner.planned(base.copy(criterion = SplitCriterion.RULE, rules = setOf("freestyle-15")))
        assertThat(rules.single().out.names).containsExactly("freestyle-15")
    }

    @Test
    fun `a typed name replaces the suggested one, a blank one does not`() {
        val u = SplitPlanner.defaults(ui(), counts)
        assertThat(SplitPlanner.planned(u.copy(names = mapOf(0 to "  화월 묶음 "))).first().title).isEqualTo("화월 묶음")
        assertThat(SplitPlanner.planned(u.copy(names = mapOf(0 to " "))).first().title).startsWith("연구 · ")
    }

    @Test
    fun `no openings without the index — solved is the default`() {
        val c = counts.copy(openings = null, openingsSkipped = "MEMORY")
        val u = SplitPlanner.defaults(ui().copy(counts = c), c)
        assertThat(u.criterion).isEqualTo(SplitCriterion.SOLVED)
        assertThat(SplitPlanner.available(c, 3)[SplitCriterion.OPENING]).isNull()
    }

    // ── «이 국면 아래» base ─────────────────────────────────────────────

    @Test
    fun `the board's stones alternate under the DB's rule of that size`() {
        val moves = listOf(Move(7, 7), Move(8, 6), Move(9, 7))
        val stats = DbStats(records = 10, rules = mapOf("freestyle-15" to 7L, "renju-15" to 2L, "standard-20" to 30L), labels = DbLabels(), commented = 0)
        val b = LibraryPositions.splitBase(stats, moves, 15)!!
        assertThat(b.rule).isEqualTo(0)
        assertThat(b.size).isEqualTo(15)
        assertThat(b.black).containsExactly(Move(7, 7), Move(9, 7)).inOrder()
        assertThat(b.white).containsExactly(Move(8, 6))
        assertThat(LibraryPositions.splitBase(null, moves, 15)!!.rule).isEqualTo(2)
        assertThat(LibraryPositions.splitBase(stats, emptyList(), 15)).isNull()
    }
}
