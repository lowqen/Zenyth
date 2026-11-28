package dev.gomoku.zenyth.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.gomoku.zenyth.R

/**
 * Five bottom-bar entries, the same five as Zenyth Web after its U2 (library
 * plan): 보드 · 연구 · 정석 · 라이브러리 · 설정 — in expert mode too.
 *
 * Seven was too many to read at a glance, and three of them (리뷰·익스플로러·랭킹)
 * are the same activity — studying a position away from the board. They now live
 * as tabs inside [Destination.Research], the way the desktop groups them under
 * one Analysis menu. Nothing was dropped; the sub-tabs keep their own state.
 *
 * The engine tab is gone (L5-5): its state line, start/stop and console are the
 * board sheet's «로그» pane, the server address is in Settings ▸ 엔진. The
 * library took its place, and the DB editor — an expert tab of its own before —
 * is the library's `library/edit`, open to everyone ([Routes]).
 *
 * Each tab has two icons, Mornye's way (UI overhaul 2, M6): [icon] outlined while
 * it waits, [selectedIcon] filled once chosen. So every pair has to differ in
 * more than weight — 그리드 · 저장 look the same either way in Material Icons,
 * hence the archive box and the board's own [FilledGrid].
 */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Board("board", R.string.tab_board, Icons.Outlined.GridOn, FilledGrid),
    Research("research", R.string.tab_research, Icons.Outlined.Science, Icons.Filled.Science),
    Joseki("joseki", R.string.tab_joseki, Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    Library("library", R.string.tab_library, Icons.Outlined.Inventory2, Icons.Filled.Inventory2),
    Settings("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Routes that are not tabs of their own (design §6.1). */
object Routes {
    /** The library's first page — the graph's start (L5-6). */
    const val LIBRARY_HOME = "library/home"

    /** A library entry's page (narrow screens; wide ones show it beside the grid). */
    const val LIBRARY_DETAIL = "library/db/{id}"

    fun libraryDetail(id: String): String = "library/db/" + android.net.Uri.encode(id)

    /** The position editor — the old DB tab, under the library since L5-6. */
    const val LIBRARY_EDIT = "library/edit"

    /** The old DB tab: a saved back stack or an old link lands on the library tab. */
    const val LEGACY_DATABASE = "database"

    /** The old engine tab: lands on the board with its «로그» pane open. */
    const val LEGACY_CONNECT = "connect"
}
