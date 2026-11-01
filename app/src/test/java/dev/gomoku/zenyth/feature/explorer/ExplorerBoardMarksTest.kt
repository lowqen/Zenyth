package dev.gomoku.zenyth.feature.explorer

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.ExplorerNext
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.OpeningEval
import dev.gomoku.zenyth.core.model.TagKind
import org.junit.Test

class ExplorerBoardMarksTest {

    private fun row(x: Int, y: Int, games: Int, b: Int = 0, d: Int = 0, w: Int = 0, grade: OpeningEval.Grade? = null) =
        ExplorerNext(Move(x, y), games, b, d, w, grade = grade)

    @Test
    fun `every played move gets a count, not just the most played`() {
        // The empty position's 36 first moves — the board used to show 12.
        val rows = (0 until 36).map { row(it % 15, it / 15, games = 100 - it, b = 1) }
        val marks = explorerBoardMarks(rows, blackToMove = true)
        assertThat(marks.tags).hasSize(36)
        assertThat(marks.tags.values.map { it.kind }.toSet()).containsExactly(TagKind.RATE)
    }

    @Test
    fun `colour follows the score of the side to move`() {
        val r = row(7, 7, games = 10, b = 6, d = 2, w = 2)
        // Black to move: (6 + 1) / 10 → 70. White to move: (2 + 1) / 10 → 30.
        assertThat(explorerBoardMarks(listOf(r), blackToMove = true).tags[r.move]!!.winRatePct).isEqualTo(70)
        assertThat(explorerBoardMarks(listOf(r), blackToMove = false).tags[r.move]!!.winRatePct).isEqualTo(30)
        // Games with no recorded result read as even rather than as a loss.
        val unknown = row(1, 1, games = 3)
        assertThat(explorerBoardMarks(listOf(unknown), blackToMove = true).tags[unknown.move]!!.winRatePct).isEqualTo(50)
    }

    @Test
    fun `a graded move keeps its shape next to its count, an unplayed one shape only`() {
        val g = OpeningEval.ladder.first()
        val played = row(3, 3, games = 5, b = 5, grade = g)
        val theory = row(4, 4, games = 0, grade = g)
        val marks = explorerBoardMarks(listOf(played, theory), blackToMove = true)
        assertThat(marks.grades.keys).containsExactly(played.move, theory.move)
        assertThat(marks.tags.keys).containsExactly(played.move)
    }

    @Test
    fun `counts are shortened to fit one point`() {
        assertThat(chipCount(7)).isEqualTo("7")
        assertThat(chipCount(999)).isEqualTo("999")
        assertThat(chipCount(1234)).isEqualTo("1.2k")
        assertThat(chipCount(9_999)).isEqualTo("10.0k")
        assertThat(chipCount(113_234)).isEqualTo("113k")
    }
}
