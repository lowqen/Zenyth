package dev.gomoku.zenyth.feature.library

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.designsystem.component.LocalWaveFrames
import dev.gomoku.zenyth.core.designsystem.theme.ZenythTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import dev.gomoku.zenyth.data.library.job.JobResult
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The L5-7 screens in motion — pick mode from a long press, the merge order's
 * ▲▼, the split's check line, and the [+ 추가] button's spoken name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ko-rKR-w411dp-h891dp-xhdpi")
class LibraryOpsFlowTest {

    @get:Rule val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        ZenythTheme(darkTheme = false) {
            CompositionLocalProvider(LocalWaveFrames provides false) { body() }
        }
    }

    private val cards = listOf(
        LibraryCard("a", "연습 DB", CoverKey(4), 0, "렌주 · 12,840 국면", attached = true),
        LibraryCard("b", "화월 연구", CoverKey(1), 0, "렌주 · 4,210 국면"),
        LibraryCard("c", "포월 정석", CoverKey(16), 0, "렌주 · 900 국면"),
    )

    private val home = LibraryUiState(loaded = true, cards = cards, now = cards[0])

    @Test
    fun `the add button reads out as one named button`() {
        content { LibraryHome(home, LibraryHomeActions()) }
        compose.onNodeWithContentDescription("추가").assertHasClickAction()
        assertThat(compose.onAllNodesWithText("추가").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a long press starts picking, then a tap picks more, and the bar offers what fits`() {
        var ui by mutableStateOf(home)
        val actions = LibraryHomeActions(
            onStartPick = { id -> ui = ui.copy(picked = listOf(id)) },
            onTogglePick = { id -> ui = ui.copy(picked = if (id in ui.picked) ui.picked - id else ui.picked + id) },
            onClearPicks = { ui = ui.copy(picked = emptyList()) },
        )
        content { LibraryHome(ui, actions) }

        compose.onNodeWithText("화월 연구").performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(ui.picked).containsExactly("b")
        compose.onNodeWithText("1개 선택").assertExists()
        compose.onNodeWithText("나누기").assertExists()

        compose.onNodeWithText("포월 정석").performClick()
        compose.waitForIdle()
        assertThat(ui.picked).containsExactly("b", "c").inOrder()
        compose.onNodeWithText("2개 선택").assertExists()
        compose.onNodeWithText("나누기").assertDoesNotExist()

        compose.onNodeWithContentDescription("고르기 끝내기").performClick()
        compose.waitForIdle()
        assertThat(ui.picking).isFalse()
        compose.onNodeWithContentDescription("추가").assertExists()
    }

    @Test
    fun `the merge order moves by its buttons and the policy is a choice`() {
        val moves = ArrayList<Pair<Int, Int>>()
        var policy: MergePolicy? = null
        var ui by mutableStateOf(
            MergeUi(
                rows = listOf(
                    MergeRow("b", "화월 연구", CoverKey(1), 0, "4,210 국면", "renju-15"),
                    MergeRow("c", "포월 정석", CoverKey(16), 0, "900 국면", "renju-15"),
                ),
            ),
        )
        val actions = MergeSheetActions(
            onMove = { i, by ->
                moves += i to by
                val rows = ui.rows.toMutableList()
                rows[i] = rows[i + by].also { rows[i + by] = rows[i] }
                ui = ui.copy(rows = rows)
            },
            onPolicy = { policy = it },
        )
        content { MergeSheetBody(ui, null, actions) }

        compose.onNodeWithContentDescription("«포월 정석» 위로").performClick()
        compose.waitForIdle()
        assertThat(moves).containsExactly(1 to -1)
        assertThat(ui.rows.first().title).isEqualTo("포월 정석")

        ui = ui.copy(step = 2)
        compose.waitForIdle()
        compose.onNodeWithText("기준 우선", substring = true).performClick()
        assertThat(policy).isEqualTo(MergePolicy.BASE)
    }

    @Test
    fun `the split preview checks results plus the rest against the original`() {
        val counts = JobResult.SplitCounted(
            total = 100,
            meta = true,
            openings = List(27) { i -> when (i) { 1 -> 40L; 4 -> 30L; else -> 0L } },
            solved = 25,
            rules = mapOf("renju-15" to 100L),
        )
        val base = SplitUi("x", "연구", LibraryCover(LibraryCover.AUTO, seed = 0), attached = false, counting = false, counts = counts)
        val ui = SplitPlanner.defaults(base, counts).copy(step = 3, move = true)
        content { SplitSheetBody(ui, null, SplitSheetActions()) }

        compose.onNodeWithText("결과 70 + 남는 것 30 = 원본 100 ✓").assertExists()
    }
}
