package dev.gomoku.zenyth.domain.library

import dev.gomoku.zenyth.domain.library.yxdb.DbStats

/**
 * Which cover a library entry draws (web `CoverKey`, design §6.4): one of the
 * 26 openings (0..25) or one of the two «일식» patterns. The cover itself is
 * never stored as a picture — the opening number and the entry's seed are all
 * there is, so a folder read on the PC draws the same cover.
 *
 * [code] 0..25 is the opening, [E0] / [E1] the eclipses. [toString] is the web
 * `[data-cov]` value (`"7"`, `"e0"`, `"e1"`), used as a cache key and in tests.
 */
@JvmInline
value class CoverKey(val code: Int) {
    val isEclipse: Boolean get() = code >= OPENINGS

    /** The opening number, or null for an eclipse. */
    val opening: Int? get() = if (code < OPENINGS) code else null

    override fun toString(): String = when (code) {
        E0.code -> "e0"
        E1.code -> "e1"
        else -> code.toString()
    }

    companion object {
        const val OPENINGS = 26
        val E0 = CoverKey(26)
        val E1 = CoverKey(27)

        /** Every key in the order the web bakes its tokens: 26 openings, then the two eclipses. */
        val ALL: List<CoverKey> = (0 until OPENINGS).map(::CoverKey) + listOf(E0, E1)

        fun opening(i: Int): CoverKey {
            require(i in 0 until OPENINGS) { "opening $i" }
            return CoverKey(i)
        }
    }
}

object Covers {
    /** The eclipse chip pressed again turns the pattern (web `CoverSheet`). */
    const val SEEDS = 48

    /**
     * The key an entry's cover draws (web `coverKeyOf`): a chosen opening as
     * is; «자동» the most frequent opening, or an eclipse when nothing was
     * classified; an out-of-range opening is an eclipse. The eclipse is the
     * seed's parity.
     */
    fun keyOf(cover: LibraryCover, stats: DbStats?): CoverKey {
        val o = cover.opening
        if (cover.mode == LibraryCover.OPENING && o != null && o in 0 until CoverKey.OPENINGS) return CoverKey(o)
        if (cover.mode == LibraryCover.AUTO) dominantOpening(stats)?.let { return CoverKey(it) }
        return if (Math.floorMod(cover.seed, 2) == 0) CoverKey.E0 else CoverKey.E1
    }

    /** The most frequent opening (0..25), the lower number on a tie; null when none was classified. */
    fun dominantOpening(stats: DbStats?): Int? {
        val o = stats?.openings ?: return null
        var best = -1
        var n = 0L
        for (i in 0 until minOf(CoverKey.OPENINGS, o.size)) if (o[i] > n) {
            best = i
            n = o[i]
        }
        return best.takeIf { it >= 0 }
    }
}
