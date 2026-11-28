package dev.gomoku.zenyth.core.designsystem.cover

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.gomoku.zenyth.core.designsystem.theme.TonalRamp
import dev.gomoku.zenyth.core.designsystem.theme.Violet
import dev.gomoku.zenyth.domain.library.CoverKey

/**
 * The library covers' colour (web `core/design/covers.ts`, L5 design §6.4) —
 * one hue per opening and the two «일식» ramps, the same numbers as the web so
 * a folder looks the same on the PC and the phone (`CoverPaletteTest` holds the
 * properties the web's `covers.test.ts` holds).
 *
 * - **月 (16) = teal … violet (180° … 330°, the night sky), 星 (10) = coral … gold
 *   (20° … 110°, starlight)**, split by the name's last syllable (월 · 성).
 * - The hues sit one per 10° slot, **skipping** slots in opening order (月 5,
 *   星 3), so openings with neighbouring numbers never get neighbouring colours.
 *
 * Two kinds of token, as on the web:
 * - **art** ([coverArt]): what [DbCover] draws — the background gradient in the
 *   container tones (light 90 → 80, dark 30 → 20), black stones at tone 10,
 *   white stones pearl ([PEARL]), the rim 3:1 off the background;
 * - **tint** ([coverScheme]): the detail page re-coloured from the cover, the
 *   way [dev.gomoku.zenyth.feature.board.WoodTheme] colours the board screen. An
 *   eclipse is not tinted — it is the app's own violet already.
 */
object CoverPalette {
    /** 星 openings — the names ending in «성» (`Opening26.korean`; the test checks it). */
    private val STARS = setOf(0, 2, 6, 10, 12, 13, 15, 17, 22, 25)

    fun isMoon(i: Int): Boolean = i !in STARS

    /** The art's chroma — background and stones on one ramp. */
    const val CHROMA = 0.07f
    private const val MOON_FROM = 180
    private const val STAR_FROM = 20
    private const val MOON_STRIDE = 5
    private const val STAR_STRIDE = 3

    /** 26 hues, in opening order. */
    val HUES: List<Int> = run {
        var moon = 0
        var star = 0
        List(CoverKey.OPENINGS) { i ->
            if (isMoon(i)) MOON_FROM + ((moon++ * MOON_STRIDE) % 16) * 10
            else STAR_FROM + ((star++ * STAR_STRIDE) % 10) * 10
        }
    }

    /** White stones — pearl, whatever the theme. */
    val PEARL = Color(0xFFF6F1E9)

    private val ramps: List<TonalRamp> = HUES.map { TonalRamp(it.toFloat(), CHROMA) }
    private val eclipse0 = TonalRamp(Violet.hue, CHROMA)
    private val eclipse1 = TonalRamp(Violet.hue, 0.025f)

    private fun artRamp(k: CoverKey): TonalRamp = when (k) {
        CoverKey.E0 -> eclipse0
        CoverKey.E1 -> eclipse1
        else -> ramps[k.code]
    }

    private val artCache = arrayOfNulls<CoverArt>(CoverKey.ALL.size * 2)

    /** The art tokens of [k] in a light or dark theme. Computed once per pair. */
    fun coverArt(k: CoverKey, dark: Boolean): CoverArt {
        val slot = k.code * 2 + if (dark) 1 else 0
        artCache[slot]?.let { return it }
        val r = artRamp(k)
        val art = if (dark) {
            CoverArt(a = r.tone(30), b = r.tone(20), stone = r.tone(10), rim = r.tone(72), line = r.tone(48))
        } else {
            CoverArt(a = r.tone(90), b = r.tone(80), stone = r.tone(10), rim = r.tone(30), line = r.tone(58))
        }
        artCache[slot] = art
        return art
    }

    /**
     * [base] re-coloured from opening [k]'s hue — web `coverTint`, on the same
     * frame as `woodScheme` (paper · ink · pale). The accent is ink, low in
     * chroma, so it never passes for a colour that means something. An eclipse
     * returns [base]; a black (AMOLED) page keeps its black faces and takes
     * only the accents.
     */
    fun coverScheme(base: ColorScheme, k: CoverKey): ColorScheme {
        val i = k.opening ?: return base
        val dark = base.surface.luminance() < 0.5f
        val black = base.surface.luminance() < 0.001f
        val h = HUES[i].toFloat()
        val paper = TonalRamp(h, if (dark) 0.012f else 0.014f)
        val ink = TonalRamp(h, 0.018f)
        val pale = TonalRamp(h, 0.035f)
        val accents = if (dark) {
            base.copy(
                primary = paper.tone(92), onPrimary = ink.tone(12),
                primaryContainer = paper.tone(30), onPrimaryContainer = paper.tone(92),
                secondaryContainer = pale.tone(28), onSecondaryContainer = paper.tone(92),
                onSurfaceVariant = ink.tone(78), outlineVariant = paper.tone(32),
            )
        } else {
            base.copy(
                primary = ink.tone(22), onPrimary = paper.tone(99),
                primaryContainer = pale.tone(88), onPrimaryContainer = ink.tone(20),
                secondaryContainer = pale.tone(89), onSecondaryContainer = ink.tone(24),
                onSurfaceVariant = ink.tone(38), outlineVariant = paper.tone(84),
            )
        }
        if (black) return accents
        return if (dark) {
            accents.copy(
                background = paper.tone(10), surface = paper.tone(10), surfaceVariant = paper.tone(26),
                surfaceContainerLowest = paper.tone(7), surfaceContainerLow = paper.tone(14),
                surfaceContainer = paper.tone(18), surfaceContainerHigh = paper.tone(22),
                surfaceContainerHighest = paper.tone(26),
            )
        } else {
            accents.copy(
                background = paper.tone(98), surface = paper.tone(98), surfaceVariant = paper.tone(90),
                surfaceContainerLowest = paper.tone(100), surfaceContainerLow = paper.tone(96),
                surfaceContainer = paper.tone(94), surfaceContainerHigh = paper.tone(91),
                surfaceContainerHighest = paper.tone(89),
            )
        }
    }
}

/** What a cover is drawn with (web `--cov-a` · `--cov-b` · `--cov-stone` · `--cov-rim` · `--cov-line`). */
data class CoverArt(
    /** Background gradient, top left. */
    val a: Color,
    /** Background gradient, bottom right; the eclipse's halo. */
    val b: Color,
    /** Black stones; the eclipse's ink disc. */
    val stone: Color,
    /** The stones' rim. */
    val rim: Color,
    /** The grid. */
    val line: Color,
)
