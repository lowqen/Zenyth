package dev.gomoku.zenyth.core.designsystem.cover

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import dev.gomoku.zenyth.core.designsystem.theme.ZenythColors
import dev.gomoku.zenyth.core.designsystem.theme.colorSchemeFor
import dev.gomoku.zenyth.core.designsystem.theme.contrastRatio
import dev.gomoku.zenyth.core.designsystem.theme.srgbToLinear
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.CoverKey
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * The cover palette's properties — web `core/design/covers.test.ts`, ported
 * case for case (222 there): every cover × light · dark · black page keeps text
 * at AA, stones and rims at 3:1 (WCAG 1.4.11), neighbouring openings apart in
 * OKLab, the tinted accent apart from the colours that mean something.
 *
 * Each test walks its cases and reports every one that fails, not the first.
 */
class CoverPaletteTest {

    private class Failures {
        private val list = mutableListOf<String>()
        var cases = 0
            private set

        fun check(ok: Boolean, what: () -> String) {
            cases++
            if (!ok) list += what()
        }

        fun assertNone(name: String) {
            assertWithMessage("$name — ${list.size} of $cases failed:\n" + list.joinToString("\n")).that(list).isEmpty()
        }
    }

    private fun oklab(c: Color): FloatArray {
        val r = srgbToLinear(c.red)
        val g = srgbToLinear(c.green)
        val b = srgbToLinear(c.blue)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    private fun dist(a: Color, b: Color): Float {
        val p = oklab(a)
        val q = oklab(b)
        val d0 = p[0] - q[0]
        val d1 = p[1] - q[1]
        val d2 = p[2] - q[2]
        return sqrt(d0 * d0 + d1 * d1 + d2 * d2)
    }

    private class Theme(val name: String, val base: ColorScheme, val dark: Boolean, val app: ZenythColors)

    private val themes = listOf(
        Theme("light", colorSchemeFor(dark = false, amoled = false, chromeHue = null), false, ZenythColors.Light),
        Theme("dark", colorSchemeFor(dark = true, amoled = false, chromeHue = null), true, ZenythColors.Dark),
        Theme("black", colorSchemeFor(dark = true, amoled = true, chromeHue = null), true, ZenythColors.Dark),
    )

    private fun name(k: CoverKey) = k.opening?.let { Opening26.korean[it] } ?: k.toString()

    // ── the hue table ──────────────────────────────────────────────────

    @Test
    fun `moon and star split as the opening names do`() {
        val f = Failures()
        for (i in 0 until Opening26.COUNT) {
            f.check(CoverPalette.isMoon(i) == Opening26.korean[i].endsWith("월")) { "${Opening26.korean[i]}: isMoon ${CoverPalette.isMoon(i)}" }
        }
        f.assertNone("moon/star")
    }

    @Test
    fun `26 hues all differ, moons in 180 - 330 and stars in 20 - 110`() {
        val f = Failures()
        f.check(CoverPalette.HUES.toSet().size == Opening26.COUNT) { "hues not unique: ${CoverPalette.HUES}" }
        CoverPalette.HUES.forEachIndexed { i, h ->
            val range = if (CoverPalette.isMoon(i)) 180..330 else 20..110
            f.check(h in range) { "${Opening26.korean[i]}: $h not in $range" }
        }
        f.assertNone("hues")
    }

    @Test
    fun `neighbouring openings draw at least 0_03 apart in OKLab, light and dark`() {
        val f = Failures()
        for (dark in listOf(false, true)) {
            for (i in 0 until Opening26.COUNT - 1) {
                val a = CoverPalette.coverArt(CoverKey(i), dark)
                val b = CoverPalette.coverArt(CoverKey(i + 1), dark)
                for ((k, x, y) in listOf(Triple("a", a.a, b.a), Triple("b", a.b, b.b))) {
                    val d = dist(x, y)
                    f.check(d >= 0.03f) { "${if (dark) "dark" else "light"} ${Opening26.korean[i]} ↔ ${Opening26.korean[i + 1]} $k: %.4f".format(d) }
                }
            }
        }
        f.assertNone("neighbours")
    }

    // ── the art ────────────────────────────────────────────────────────

    @Test
    fun `rims stand 3 to 1 off the background, and so does the stone that touches it`() {
        val f = Failures()
        for (k in CoverKey.ALL) for (dark in listOf(false, true)) {
            val art = CoverPalette.coverArt(k, dark)
            // Light: the black stone stands as a body; dark: the pearl does.
            val body = if (dark) CoverPalette.PEARL else art.stone
            for ((bgName, bg) in listOf("a" to art.a, "b" to art.b)) {
                val rim = contrastRatio(art.rim, bg)
                val stone = contrastRatio(body, bg)
                val t = if (dark) "dark" else "light"
                f.check(rim >= 3f) { "${name(k)} $t rim/$bgName %.2f".format(rim) }
                f.check(stone >= 3f) { "${name(k)} $t body/$bgName %.2f".format(stone) }
            }
        }
        f.assertNone("art")
    }

    // ── the tinted page ────────────────────────────────────────────────

    @Test
    fun `tinted text pairs clear AA on every cover and page`() {
        val f = Failures()
        for (k in CoverKey.ALL) for (th in themes) {
            val s = CoverPalette.coverScheme(th.base, k)
            val art = CoverPalette.coverArt(k, th.dark)
            val pairs = listOf(
                "onSurface / art a" to (s.onSurface to art.a),
                "onSurface / art b" to (s.onSurface to art.b),
                "onSurfaceVariant / art a" to (s.onSurfaceVariant to art.a),
                "onSurface / background" to (s.onSurface to s.background),
                "onSurface / surfaceContainer" to (s.onSurface to s.surfaceContainer),
                "onSurfaceVariant / surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
                "onSurface / surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
                "onSurfaceVariant / surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
                "onPrimary / primary" to (s.onPrimary to s.primary),
                "onSecondaryContainer / secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            )
            for ((label, p) in pairs) {
                val r = contrastRatio(p.first, p.second)
                f.check(r >= 4.5f) { "${name(k)} ${th.name} $label %.2f".format(r) }
            }
        }
        f.assertNone("tinted text")
    }

    @Test
    fun `tinted cards lift off the page and the accent keeps clear of meaning colours`() {
        val f = Failures()
        for (i in 0 until Opening26.COUNT) for (th in themes) {
            val s = CoverPalette.coverScheme(th.base, CoverKey(i))
            val lift = contrastRatio(s.surfaceContainer, s.background)
            f.check(lift >= 1.06f) { "${Opening26.korean[i]} ${th.name} card/page %.3f".format(lift) }
            val meanings = listOf(
                "resultBlack" to th.app.resultBlack,
                "resultWhite" to th.app.resultWhite,
                "error" to s.error,
                "positive" to th.app.positive,
                "caution" to th.app.caution,
            )
            for ((m, c) in meanings) {
                val d = dist(s.primary, c)
                f.check(d >= 0.08f) { "${Opening26.korean[i]} ${th.name} primary↔$m %.3f".format(d) }
            }
        }
        f.assertNone("faces and accents")
    }
}
