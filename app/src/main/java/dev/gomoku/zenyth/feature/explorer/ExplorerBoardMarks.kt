package dev.gomoku.zenyth.feature.explorer

import dev.gomoku.zenyth.core.model.CellTag
import dev.gomoku.zenyth.core.model.ExplorerNext
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.OpeningEval
import dev.gomoku.zenyth.core.model.TagKind
import kotlin.math.roundToInt

/**
 * What the opening explorer writes on its board: [tags] for every next move
 * that was played, [grades] for every graded one.
 */
internal data class ExplorerBoardMarks(
    val tags: Map<Move, CellTag>,
    val grades: Map<Move, OpeningEval.Grade>,
)

/**
 * The explorer's board, as Zenyth Web draws it (`OpeningExplorer.tsx`).
 *
 * **Every** played next move gets its game count, written like an engine value:
 * the same outlined text, the same size, coloured by the score of the side to
 * move — `(wins + draws / 2) / decided` — so it reads the way a win rate does
 * (high: the move went well for whoever played it). It used to be the twelve
 * most-played only, in one cream colour at 0.9 size, and a graded move showed
 * its shape *instead of* its count; the board then showed a third of the
 * empty position's 36 first moves.
 *
 * A graded move keeps its shape: the renderer moves it to the point's corner
 * when a count sits there too.
 */
internal fun explorerBoardMarks(next: List<ExplorerNext>, blackToMove: Boolean): ExplorerBoardMarks {
    val tags = LinkedHashMap<Move, CellTag>()
    val grades = LinkedHashMap<Move, OpeningEval.Grade>()
    for (row in next) {
        row.grade?.let { grades[row.move] = it }
        if (row.games > 0) {
            val decided = row.blackWins + row.draws + row.whiteWins
            val mover = if (blackToMove) row.blackWins else row.whiteWins
            val score = if (decided > 0) (mover + row.draws / 2.0) / decided * 100 else 50.0
            tags[row.move] = CellTag(
                label = chipCount(row.games),
                kind = TagKind.RATE,
                depth = 0,
                winRatePct = score.roundToInt(),
            )
        }
    }
    return ExplorerBoardMarks(tags, grades)
}

/** A game count that fits one point: 1234 → «1.2k», 113234 → «113k». */
internal fun chipCount(n: Int): String = when {
    n >= 10_000 -> "${(n / 1000.0).roundToInt()}k"
    n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000.0)
    else -> "$n"
}
