package dev.gomoku.zenyth.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gomoku.zenyth.core.designsystem.component.LocalWaveFrames
import dev.gomoku.zenyth.core.designsystem.theme.ZenythTheme
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.LibraryCover
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library (step L5-6), photographed as [dev.gomoku.zenyth.core.designsystem.DesignSystemScreenshotTest]
 * photographs the rest: light · dark at 100 % and 130 %, and the black page
 * once. The sheets are photographed by their bodies — a modal sheet is a
 * window of its own, which Robolectric's capture does not reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "ko-rKR-w420dp-h900dp-xhdpi")
class LibraryScreenshotTest(private val variant: Variant) {

    data class Variant(val dark: Boolean, val fontScale: Float) {
        val tag: String get() = (if (dark) "dark" else "light") + "_" + (fontScale * 100).toInt()
        override fun toString(): String = tag
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(
            Variant(dark = false, fontScale = 1f),
            Variant(dark = true, fontScale = 1f),
            Variant(dark = false, fontScale = 1.3f),
            Variant(dark = true, fontScale = 1.3f),
        ).map { arrayOf(it) }
    }

    @Test
    fun home() = screen("library_home", 411.dp, 900.dp) { LibraryHome(homeUi, LibraryHomeActions()) }

    @Test
    fun homeList() {
        assumeTrue(variant.fontScale == 1f)
        screen("library_home_list", 411.dp, 900.dp) { LibraryHome(homeUi.copy(list = true, job = JobLine("복사하는 중", 0.42f, false)), LibraryHomeActions()) }
    }

    @Test
    fun homeFolderLost() {
        assumeTrue(variant.fontScale == 1f)
        val lost = SourceChip("saf1", "폴더: 기보", folder = true, lost = true)
        screen("library_home_folder", 411.dp, 640.dp) {
            LibraryHome(
                LibraryUiState(loaded = true, sources = listOf(homeUi.sources[0], lost), sourceId = "saf1", folder = lost),
                LibraryHomeActions(),
            )
        }
    }

    @Test
    fun detail() = screen("library_detail", 411.dp, 1500.dp) { LibraryDetail(detailUi, LibraryDetailActions(), onBack = {}) }

    /** An unindexed DB: the roots lead, the distribution folds, a line says why. */
    @Test
    fun detailCounting() {
        assumeTrue(variant.fontScale == 1f)
        screen("library_detail_counting", 411.dp, 900.dp) {
            LibraryDetail(
                detailUi.copy(
                    coverKey = CoverKey.E0, cover = LibraryCover(LibraryCover.AUTO, seed = 6), scanLine = ScanLine.COUNTING,
                    scanFraction = 0.37f, openings = emptyList(), roots = emptyList(), records = null, chips = listOf("1.2MB"),
                ),
                LibraryDetailActions(),
                onBack = {},
            )
        }
    }

    @Test
    fun coverSheet() = shot("library_cover_sheet") {
        CoverSheetBody("연습 DB", LibraryCover(LibraryCover.OPENING, opening = 4, seed = 0), autoOpening = 4) {}
    }

    @Test
    fun renameSheet() {
        assumeTrue(variant.fontScale == 1f)
        shot("library_rename_sheet") { RenameSheetBody(detailUi.copy(inFolder = true, file = "연습.db"), "화월 연구", {}, true, {}) }
    }

    @Test
    fun amoled() {
        assumeTrue(variant == Variant(dark = false, fontScale = 1f))
        screen("library_home_amoled", 411.dp, 900.dp, dark = true, amoled = true, fileTag = null) { LibraryHome(homeUi, LibraryHomeActions()) }
        screen("library_detail_amoled", 411.dp, 1500.dp, dark = true, amoled = true, fileTag = null) {
            LibraryDetail(detailUi, LibraryDetailActions(), onBack = {})
        }
    }

    /** Every cover, light and dark, side by side — the palette at a glance. */
    @Test
    fun covers() {
        assumeTrue(variant.fontScale == 1f)
        shot("library_covers") {
            CoverKey.ALL.chunked(7).forEach { row ->
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k -> dev.gomoku.zenyth.core.designsystem.cover.DbCover(k, Modifier.size(48.dp), seed = k.code, corner = 8.dp) }
                }
            }
        }
    }

    // ---- fixtures -------------------------------------------------------------

    private val homeUi = LibraryUiState(
        loaded = true,
        sources = listOf(
            SourceChip("app", "이 기기", folder = false, lost = false),
            SourceChip("saf1", "폴더: 기보", folder = true, lost = false),
        ),
        sourceId = "app",
        now = LibraryCard("a", "연습 DB", CoverKey(4), 0, "렌주 · 12,840 국면 · 3.1MB", attached = true),
        cards = listOf(
            LibraryCard("a", "연습 DB", CoverKey(4), 0, "렌주 · 12,840 국면 · 3.1MB", attached = true),
            LibraryCard("b", "화월 연구", CoverKey(1), 0, "렌주 · 4,210 국면"),
            LibraryCard("c", "새 DB", CoverKey.E1, 3, "비어 있음"),
            LibraryCard("d", "포월 정석", CoverKey(16), 0, "세는 중 42%", working = true),
        ),
        trash = listOf(TrashRow("t", "옛 DB", "2026. 11. 20.")),
    )

    private val detailUi = LibraryDetailUi(
        id = "a",
        title = "연습 DB",
        cover = LibraryCover(LibraryCover.AUTO, seed = 0),
        coverKey = CoverKey(4),
        autoOpening = 4,
        chips = listOf("렌주", "12,840 국면", "3.1MB", "마지막 저장 2026. 11. 27."),
        attached = true,
        inFolder = false,
        file = "0b6c4c1e.db",
        size = 3_100_000,
        scanLine = ScanLine.NONE,
        openings = listOf(OpeningRow(4, 6200), OpeningRow(1, 2100), OpeningRow(16, 900), OpeningRow(10, 120)),
        classified = 9320,
        unclassified = 3520,
        rootsFirst = false,
        roots = listOf(
            RootRow("k1", listOf(Move(7, 7), Move(8, 6), Move(9, 7)), 9300),
            RootRow("k2", listOf(Move(7, 7)), 2400),
            RootRow("k3", null, 40),
        ),
        records = RecordsUi(w = 310, l = 280, d = 12, comments = 54, rules = null),
        targets = listOf(SourceChip("saf1", "폴더: 기보", folder = true, lost = false)),
    )

    // ---- helpers --------------------------------------------------------------

    private fun screen(
        name: String,
        width: Dp,
        height: Dp,
        dark: Boolean = variant.dark,
        amoled: Boolean = false,
        fileTag: String? = variant.tag,
        content: @Composable () -> Unit,
    ) {
        val file = if (fileTag == null) name else "${name}_$fileTag"
        captureRoboImage("src/test/screenshots/$file.png") {
            ZenythTheme(darkTheme = dark, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, variant.fontScale),
                    LocalWaveFrames provides false,
                ) {
                    Surface(Modifier.size(width, height), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/${name}_${variant.tag}.png") {
            ZenythTheme(darkTheme = variant.dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, variant.fontScale),
                    LocalWaveFrames provides false,
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.width(420.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                    }
                }
            }
        }
    }
}
