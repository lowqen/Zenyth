package dev.gomoku.zenyth.domain.library

import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.SplitBase
import dev.gomoku.zenyth.domain.library.yxdb.YxdbKey

/**
 * From a library entry's stats to a line the board can play (web
 * `libraryStore.movesOfKey` · `startMovesOf` · `openingMoves`, design §6.3).
 */
/** «DB 열 때 시작 국면» (L5-6 P3, web `libraryStore` `StartMode`): where «이 DB 열기» puts the board. */
enum class StartMode(val key: String) {
    /** Down from the largest root while one child holds 9/10 — where the records branch. The default. */
    BRANCH("branch"),
    /** The largest root itself — often the empty board (the engine's lookup records lead there). */
    ROOT("root"),
    EMPTY("empty");

    companion object {
        const val PREF = "library.openAt"
        fun of(key: String?): StartMode = entries.firstOrNull { it.key == key } ?: BRANCH
    }
}

/** A DB's main rule: an engine rule (0 freestyle, 1 standard, 2 renju) on a [size]-line board. */
data class DbRule(val rule: Int, val size: Int)

object LibraryPositions {

    /**
     * A database key (hex) → moves, black and white alternating. Null when the
     * board size differs from [size], a pass slot is in it, the colours cannot
     * alternate, or the key is malformed.
     */
    fun movesOfKey(hex: String, size: Int): List<Move>? {
        val k = runCatching { YxdbKey.decode(YxdbKey.fromHex(hex)) }.getOrNull() ?: return null
        if (k.pass || k.w != size || k.h != size) return null
        val d = k.black.size - k.white.size
        if (d != 0 && d != 1) return null
        val out = ArrayList<Move>(k.black.size + k.white.size)
        var i = 0
        while (i < k.black.size || i < k.white.size) {
            if (i < k.black.size) out.add(k.black[i])
            if (i < k.white.size) out.add(k.white[i])
            i++
        }
        return out
    }

    /**
     * Where the database's record starts — the largest root that reads on this
     * board size (decision 2026-11-23: open a DB on its own position), at its
     * start when the index found one (down while the heaviest child holds 9/10,
     * index version 3 — L5-6), else the root. Null without roots (the index was
     * skipped) or when none fits.
     */
    fun startMovesOf(stats: DbStats?, size: Int, mode: StartMode = StartMode.BRANCH): List<Move>? {
        if (mode == StartMode.EMPTY) return emptyList()
        for (r in stats?.roots.orEmpty()) {
            val root = movesOfKey(r.key, size) ?: continue
            if (mode == StartMode.ROOT) return root
            return r.start?.let { movesOfKey(it, size) } ?: root
        }
        return null
    }

    /**
     * The rule most of the DB's positions are under (`stats.rules`, `renju-15`
     * …) as an engine rule (0 freestyle, 1 standard, 2 renju) and a square
     * board size; null without stats or when that is not a known square rule.
     * «이 DB 열기» warns when it differs from the board's (L5-6 P3).
     */
    fun mainRuleOf(stats: DbStats?): DbRule? = stats?.rules?.maxByOrNull { it.value }?.key?.let(::parseRule)

    private fun parseRule(name: String): DbRule? {
        val dash = name.lastIndexOf('-')
        if (dash <= 0) return null
        val rule = RULE_NAMES.indexOf(name.substring(0, dash))
        val size = name.substring(dash + 1).toIntOrNull() ?: return null
        return if (rule < 0) null else DbRule(rule, size)
    }

    /**
     * The board as «이 국면 아래»'s base for a split (L5-7, web `boardBase`): its
     * stones, black and white alternating, under the DB's most common rule of
     * this board size (renju when it has none). Null on an empty board.
     */
    fun splitBase(stats: DbStats?, moves: List<Move>, size: Int): SplitBase? {
        if (moves.isEmpty()) return null
        val rule = stats?.rules.orEmpty().entries
            .mapNotNull { (k, n) -> parseRule(k)?.takeIf { it.size == size }?.let { it to n } }
            .maxByOrNull { it.second }?.first?.rule ?: RENJU
        return SplitBase(rule, size, moves.filterIndexed { i, _ -> i % 2 == 0 }, moves.filterIndexed { i, _ -> i % 2 == 1 })
    }

    private const val RENJU = 2

    private val RULE_NAMES = listOf("freestyle", "standard", "renju")

    /** An opening's three representative stones (the cover's and the distribution row's). */
    fun openingMoves(index: Int): List<Move> = Opening26.representative(index)
}
