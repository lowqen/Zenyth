package dev.gomoku.zenyth.core.designsystem.cover

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.CoverKey
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A library cover (web `DbCover.tsx`, L5 design §6.4) — drawn, never stored:
 * the opening number and the entry's seed are all it needs. Colours follow
 * the theme ([CoverPalette.coverArt]).
 *
 * - An opening: the centre ±2 cut large, its three representative stones.
 *   **直 = a square grid, 間 = the grid turned 45°** so the indirect second move
 *   (I9, up right of the centre) stands upright. Black = the hue's tone 10,
 *   white = pearl. The three stones' screen box is centred.
 * - «일식»: the app mark's geometry — an ink stone passing in front of a pearl.
 *   The seed turns the direction (8) and the gap (3).
 *
 * Laid out in a 100 × 100 space scaled to the size; the corner is [corner]
 * (20dp on the detail, 12dp in the grid — design §6.4). Paths are not cached
 * across frames because there are none: a handful of lines and circles, drawn
 * straight from the values [drawWithCache] holds.
 */
@Composable
fun DbCover(
    key: CoverKey,
    modifier: Modifier = Modifier,
    seed: Int = 0,
    corner: Dp = 12.dp,
    /** Set: read as a picture with this name. Unset: decoration. */
    label: String? = null,
) {
    val art = CoverPalette.coverArt(key, coverDark)
    val shape = remember(corner) { RoundedCornerShape(corner) }
    Spacer(
        modifier
            .clip(shape)
            .then(
                if (label != null) {
                    Modifier.semantics {
                        contentDescription = label
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val unit = min(size.width, size.height) / 100f
                val background = Brush.linearGradient(listOf(art.a, art.b), Offset.Zero, Offset(size.width, size.height))
                val opening = key.opening
                val geometry = opening?.let { OpeningGeometry.of(it) }
                onDrawBehind {
                    drawRect(background)
                    withTransform({
                        translate((size.width - 100f * unit) / 2f, (size.height - 100f * unit) / 2f)
                        scale(unit, unit, pivot = Offset.Zero)
                    }) {
                        if (geometry != null) drawOpening(geometry, art) else drawEclipse(seed, art)
                    }
                }
            },
    )
}

/**
 * Inside a tinted page the art keeps the theme's own brightness — a cover on
 * the detail page must not read the tinted surface (the same brightness, but
 * said once).
 */
internal val LocalCoverDark = staticCompositionLocalOf<Boolean?> { null }

/** The page re-coloured from [key]'s hue ([CoverPalette.coverScheme]) — `WoodTheme`'s way, for this subtree only. */
@Composable
fun CoverTheme(key: CoverKey, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.surface.luminance() < 0.5f
    val scheme = remember(base, key) { CoverPalette.coverScheme(base, key) }
    CompositionLocalProvider(LocalCoverDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Is the theme here a dark one — what a cover reads its art by. */
val coverDark: Boolean
    @Composable @ReadOnlyComposable get() =
        LocalCoverDark.current ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)

/** An opening cover's layout, in the 100-unit space (web `OpeningArt`). */
internal class OpeningGeometry(
    val direct: Boolean,
    val step: Float,
    val tx: Float,
    val ty: Float,
    /** Grid coordinates of the three stones (before the turn). */
    val stones: List<Offset>,
) {
    companion object {
        private val cache = arrayOfNulls<OpeningGeometry>(CoverKey.OPENINGS)

        fun of(index: Int): OpeningGeometry = cache[index] ?: build(index).also { cache[index] = it }

        private fun build(index: Int): OpeningGeometry {
            val direct = Opening26.isDirect(index)
            // 直 spans three cells upright, 間 turned spans 4.2 on the diagonal — both inside the picture.
            val step = if (direct) 20f else 15f
            val stones = Opening26.representative(index).map { Offset((it.x - 7) * step, (it.y - 7) * step) }
            val c = sqrt(0.5f)
            val screen = stones.map { p -> if (direct) p else Offset(c * p.x + c * p.y, -c * p.x + c * p.y) }
            fun mid(sel: (Offset) -> Float): Float {
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (p in screen) {
                    lo = min(lo, sel(p))
                    hi = max(hi, sel(p))
                }
                return (lo + hi) / 2f
            }
            return OpeningGeometry(direct, step, 50f - mid { it.x }, 50f - mid { it.y }, stones)
        }
    }
}

private fun DrawScope.drawOpening(g: OpeningGeometry, art: CoverArt) {
    withTransform({
        translate(g.tx, g.ty)
        if (!g.direct) rotate(-45f, pivot = Offset.Zero)
    }) {
        for (i in -5..5) {
            val p = i * g.step
            drawLine(art.line, Offset(-120f, p), Offset(120f, p), strokeWidth = 1f)
            drawLine(art.line, Offset(p, -120f), Offset(p, 120f), strokeWidth = 1f)
        }
        val r = g.step * 0.44f
        g.stones.forEachIndexed { i, c ->
            drawCircle(if (i % 2 == 0) art.stone else CoverPalette.PEARL, r, c)
            drawCircle(art.rim, r, c, style = Stroke(width = 1.2f))
        }
    }
}

/** Eight directions × three gaps — two eclipses side by side on one shelf still differ. */
private fun DrawScope.drawEclipse(seed: Int, art: CoverArt) {
    val s = Math.floorMod(seed, Int.MAX_VALUE)
    val angle = (s % 8) * 45f
    val gap = floatArrayOf(17f, 19f, 21f)[(s / 8) % 3]
    rotate(angle, pivot = Offset(50f, 50f)) {
        drawCircle(CoverPalette.PEARL, 23f, Offset(50f + gap / 2f + 1f, 50f))
        drawCircle(art.b, 25.25f, Offset(50f - gap / 2f, 50f))
        drawCircle(art.stone, 20.75f, Offset(50f - gap / 2f, 50f))
    }
}
