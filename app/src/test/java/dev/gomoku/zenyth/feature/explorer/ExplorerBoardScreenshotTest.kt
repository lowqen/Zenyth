package dev.gomoku.zenyth.feature.explorer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gomoku.zenyth.core.designsystem.component.BoardRender
import dev.gomoku.zenyth.core.designsystem.component.GomokuBoard
import dev.gomoku.zenyth.core.designsystem.theme.ZenythTheme
import dev.gomoku.zenyth.core.model.ExplorerNext
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.OpeningEval
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The opening explorer's board ([explorerBoardMarks]): every played next move
 * as a coloured count, graded ones with their shape. Light and dark only — the
 * board's text is drawn with `Paint` and does not follow the font scale.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "ko-rKR-w420dp-h900dp-xhdpi")
class ExplorerBoardScreenshotTest(private val dark: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    private fun move(label: String) = Move.fromLabel(label, 15)!!

    /** A count with a result split that leans by [lean] (−1 … +1, Black's view). */
    private fun row(label: String, games: Int, lean: Double, grade: OpeningEval.Grade? = null): ExplorerNext {
        val b = (games * (0.45 + 0.4 * lean)).toInt().coerceIn(0, games)
        val d = games / 20
        return ExplorerNext(move(label), games, b, d, (games - b - d).coerceAtLeast(0), grade = grade)
    }

    /**
     * The empty board: the 36 first moves of the symmetry triangle, with the
     * counts Zenyth Web shows for the 2026-10-08 RenjuNet file. All of them,
     * where the app used to show twelve.
     */
    @Test
    fun emptyBoard() {
        val counts = listOf(
            "a15" to 56, "b15" to 9, "c15" to 2, "d15" to 16, "e15" to 4, "f15" to 8, "g15" to 53, "h15" to 270,
            "b14" to 320, "c14" to 205, "d14" to 244, "e14" to 425, "f14" to 171, "g14" to 228, "h14" to 75,
            "c13" to 387, "d13" to 869, "e13" to 545, "f13" to 436, "g13" to 431, "h13" to 165,
            "d12" to 399, "e12" to 244, "f12" to 248, "g12" to 215, "h12" to 104,
            "e11" to 121, "f11" to 148, "g11" to 166, "h11" to 124,
            "f10" to 260, "g10" to 358, "h10" to 279,
            "g9" to 244, "h9" to 366,
            "h8" to 113_234,
        )
        val rows = counts.mapIndexed { i, (l, n) -> row(l, n, lean = ((i * 37) % 21 - 10) / 10.0) }
        shot("explorer_board_empty", emptyList(), rows)
    }

    /**
     * Four moves in, Black to play the fifth: graded moves that were also played
     * carry their shape in the corner, graded ones never played the shape alone.
     */
    @Test
    fun fifthMove() {
        val stones = listOf("h8", "i9", "j8", "j9").map(::move)
        val ladder = OpeningEval.ladder
        val rows = listOf(
            row("i7", 1_840, 0.6, ladder[1]),
            row("k10", 960, 0.2, ladder[3]),
            row("h10", 412, -0.3, ladder[6]),
            row("g9", 57, 0.0, ladder[5]),
            row("k8", 23, -0.8),
            row("j10", 0, 0.0, ladder[0]),
            row("l9", 0, 0.0, ladder.last()),
        )
        shot("explorer_board_fifth", stones, rows)
    }

    private fun shot(name: String, stones: List<Move>, rows: List<ExplorerNext>) {
        val marks = explorerBoardMarks(rows, blackToMove = stones.size % 2 == 0)
        val render = BoardRender(
            stones = stones,
            lastMove = stones.lastOrNull(),
            tags = marks.tags,
            gradeMarks = marks.grades,
        )
        val tag = if (dark) "dark" else "light"
        captureRoboImage("src/test/screenshots/${name}_$tag.png") {
            ZenythTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.width(420.dp).padding(12.dp)) {
                        GomokuBoard(render = render, modifier = Modifier.width(396.dp))
                    }
                }
            }
        }
    }
}
