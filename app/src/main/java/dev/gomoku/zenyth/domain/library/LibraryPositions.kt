package dev.gomoku.zenyth.domain.library

import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.YxdbKey

/**
 * From a library entry's stats to a line the board can play (web
 * `libraryStore.movesOfKey` · `startMovesOf` · `openingMoves`, design §6.3).
 */
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
     * board size (decision 2026-11-23: open a DB on its own position). Null
     * without roots (the index was skipped) or when none fits.
     */
    fun startMovesOf(stats: DbStats?, size: Int): List<Move>? {
        for (r in stats?.roots.orEmpty()) movesOfKey(r.key, size)?.let { return it }
        return null
    }

    /** An opening's three representative stones (the cover's and the distribution row's). */
    fun openingMoves(index: Int): List<Move> = Opening26.representative(index)
}
