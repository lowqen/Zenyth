package dev.gomoku.zenyth.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.designsystem.component.Button
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LabeledProgress
import dev.gomoku.zenyth.core.designsystem.component.StepDots
import dev.gomoku.zenyth.core.designsystem.component.ZenythFilterChip
import dev.gomoku.zenyth.core.designsystem.component.ZenythSegmented
import dev.gomoku.zenyth.core.designsystem.component.ZenythSheet
import dev.gomoku.zenyth.core.designsystem.cover.DbCover
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.domain.library.Covers
import dev.gomoku.zenyth.domain.library.yxdb.OpeningsSkipped
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion

/** What the split sheet asks for. */
class SplitSheetActions(
    val onCriterion: (SplitCriterion) -> Unit = {},
    val onOpening: (Int) -> Unit = {},
    val onGrouped: (Boolean) -> Unit = {},
    val onRule: (String) -> Unit = {},
    val onMove: (Boolean) -> Unit = {},
    val onName: (Int, String) -> Unit = { _, _ -> },
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onCommit: () -> Unit = {},
    val onConfirmMove: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onCancelJob: () -> Unit = {},
)

/**
 * «나누기» (design §6.5, web `SplitSheet`) — three steps: ① the criterion,
 * each with its count from one pass made when the sheet opened (by opening ·
 * under the board's position · solved only · rule and board) → ② what stays:
 * the original as it is (a copy, recommended) or moved out of it → ③ the
 * results, names editable, and the check «결과 + 남는 것 = 원본 ✓». A move
 * asks once more: the original becomes the rest, its copy goes to the trash.
 */
@Composable
fun LibrarySplitSheet(ui: SplitUi, job: JobLine?, actions: SplitSheetActions) {
    val planned = SplitPlanner.planned(ui)
    ZenythSheet(
        title = tr("나누기", "Split", "拆分"),
        onDismiss = actions.onClose,
        dismiss = {
            if (ui.step == 1) {
                TextButton(onClick = actions.onClose) { Text(tr("닫기", "Close", "关闭")) }
            } else {
                TextButton(onClick = actions.onBack, enabled = !ui.running) { Text(tr("이전", "Back", "上一步")) }
            }
        },
        confirm = {
            if (ui.step < 3) {
                Button(onClick = actions.onNext, enabled = !ui.busy && planned.isNotEmpty()) { Text(tr("다음", "Next", "下一步")) }
            } else {
                Button(onClick = actions.onCommit, enabled = !ui.busy && planned.isNotEmpty()) { Text(tr("나누기", "Split", "拆分")) }
            }
        },
    ) {
        SplitSheetBody(ui, job, actions)
    }
    if (ui.confirmMove) {
        AlertDialog(
            onDismissRequest = actions.onDismissConfirm,
            title = { Text(tr("원본에서 빼서 옮길까요?", "Move them out of the original?", "从原件中移出？")) },
            text = { Text(tr("«${ui.title}» 에는 남는 것만 둡니다. 원본 사본은 휴지통에 백업됩니다.", "«${ui.title}» keeps only the rest. A copy of the original is backed up in the trash.", "«${ui.title}»只保留剩余部分。原件副本会备份到回收站。")) },
            confirmButton = { Button(onClick = actions.onConfirmMove) { Text(tr("옮기기", "Move", "移动")) } },
            dismissButton = { TextButton(onClick = actions.onDismissConfirm) { Text(tr("취소", "Cancel", "取消")) } },
        )
    }
}

/** The sheet's body as the state says it — stateless, for the sheet and its screenshots. */
@Composable
fun SplitSheetBody(ui: SplitUi, job: JobLine?, actions: SplitSheetActions) {
    StepDots(ui.step, listOf(tr("기준", "Criterion", "依据"), tr("나머지", "The rest", "剩余"), tr("미리 보기", "Preview", "预览")))
    val c = ui.counts
    if (c == null) {
        if (ui.error == null) {
            Text(tr("세는 중…", "Counting…", "统计中…"), style = MaterialTheme.typography.bodyMedium)
            LabeledProgress(job?.fraction ?: 0f)
        }
    } else {
        when (ui.step) {
            1 -> SplitCriteria(ui, actions)
            2 -> SplitRest(ui, actions)
            else -> SplitPreview(ui, job, actions)
        }
    }
    ui.error?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitCriteria(ui: SplitUi, actions: SplitSheetActions) {
    val c = ui.counts ?: return
    val o = c.openings
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // By opening — needs the index.
        val classified = o?.take(SplitPlanner.UNCLASSIFIED)?.count { it > 0 } ?: 0
        ChoiceRow(
            selected = ui.criterion == SplitCriterion.OPENING,
            enabled = o != null,
            title = tr("주형별", "By opening", "按开局"),
            body = when {
                o != null -> tr(
                    "${classified}개 주형 · 미분류 ${LibraryFormat.count(o.getOrElse(SplitPlanner.UNCLASSIFIED) { 0L })}",
                    "$classified openings · unclassified ${LibraryFormat.count(o.getOrElse(SplitPlanner.UNCLASSIFIED) { 0L })}",
                    "$classified 个开局 · 未分类 ${LibraryFormat.count(o.getOrElse(SplitPlanner.UNCLASSIFIED) { 0L })}",
                )
                c.openingsSkipped == OpeningsSkipped.UNORDERED.name ->
                    tr("돌 수 순서로 저장된 파일이 아니라 주형을 셀 수 없습니다.", "Openings cannot be counted: the file is not in stone-count order.", "文件未按棋子数排序，无法统计开局。")
                else -> tr("이 기기의 메모리로는 주형을 셀 수 없습니다.", "This device does not have the memory to count openings.", "本机内存不足，无法统计开局。")
            },
            onClick = { actions.onCriterion(SplitCriterion.OPENING) },
        )
        if (ui.criterion == SplitCriterion.OPENING && o != null) {
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZenythSegmented(
                    options = listOf(false to tr("주형마다 한 파일", "One file each", "每个开局一个文件"), true to tr("묶어 한 파일", "All in one file", "合为一个文件")),
                    selection = ui.grouped,
                    onSelect = actions.onGrouped,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.indices.filter { o[it] > 0 }.forEach { i ->
                        ZenythFilterChip(
                            selected = i in ui.picked,
                            onClick = { actions.onOpening(i) },
                            label = { Text("${SplitPlanner.openingName(i)} ${LibraryFormat.count(o[i])}") },
                        )
                    }
                }
            }
        }
        ChoiceRow(
            selected = ui.criterion == SplitCriterion.UNDER,
            enabled = ui.baseStones > 0 && c.under != null,
            title = tr("이 국면 아래", "Under this position", "此局面之下"),
            body = if (ui.baseStones > 0 && c.under != null) {
                tr("지금 판 ${ui.baseStones}수 국면과 그 아래 ${LibraryFormat.count(c.under)} (대칭 포함)", "The board's ${ui.baseStones}-move position and below: ${LibraryFormat.count(c.under)} (symmetries included)", "当前棋盘 ${ui.baseStones} 手局面及其之下 ${LibraryFormat.count(c.under)}（含对称）")
            } else {
                tr("판에 돌을 놓으면 그 국면 아래를 나눌 수 있습니다.", "Put stones on the board to split what lies under that position.", "在棋盘上落子后即可拆分该局面之下的部分。")
            },
            onClick = { actions.onCriterion(SplitCriterion.UNDER) },
        )
        ChoiceRow(
            selected = ui.criterion == SplitCriterion.SOLVED,
            title = tr("풀린 국면만", "Solved positions only", "仅已解局面"),
            body = tr("승패가 정해진(W · L · D) 국면 ${LibraryFormat.count(c.solved)}", "Positions with a result (W · L · D): ${LibraryFormat.count(c.solved)}", "已定胜负（W · L · D）的局面 ${LibraryFormat.count(c.solved)}"),
            onClick = { actions.onCriterion(SplitCriterion.SOLVED) },
        )
        if (c.rules.size > 1) {
            ChoiceRow(
                selected = ui.criterion == SplitCriterion.RULE,
                title = tr("규칙 · 판", "Rule · board", "规则 · 棋盘"),
                body = tr("규칙 · 판 ${c.rules.size}가지가 섞여 있습니다", "${c.rules.size} rules or boards are mixed", "混有 ${c.rules.size} 种规则或棋盘"),
                onClick = { actions.onCriterion(SplitCriterion.RULE) },
            )
            if (ui.criterion == SplitCriterion.RULE) {
                FlowRow(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.rules.forEach { (k, n) ->
                        ZenythFilterChip(selected = k in ui.rules, onClick = { actions.onRule(k) }, label = { Text("${LibraryFormat.rule(k)} ${LibraryFormat.count(n)}") })
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitRest(ui: SplitUi, actions: SplitSheetActions) {
    val rest = SplitPlanner.rest(ui)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChoiceRow(
            selected = !ui.move,
            title = tr("원본 그대로(권장, 복사)", "Keep the original (recommended, copy)", "保留原件（推荐，复制）"),
            body = tr("원본은 바꾸지 않고 고른 국면을 새 DB 로 복사합니다.", "The original stays as it is; the chosen positions are copied into new DBs.", "不改动原件，把选中的局面复制到新数据库。"),
            onClick = { actions.onMove(false) },
        )
        ChoiceRow(
            selected = ui.move,
            enabled = !ui.attached,
            title = tr("원본에서 빼기(옮기기)", "Take them out of the original (move)", "从原件中移出（移动）"),
            body = if (ui.attached) {
                tr("붙어 있는 DB 에서는 옮길 수 없습니다. 복사로 나누세요", "The attached DB cannot be split by moving — split by copying", "已连接的数据库不能移出拆分，请用复制拆分")
            } else {
                tr("원본에는 남는 ${LibraryFormat.count(rest)} 만 둡니다. 원본 사본은 휴지통에 백업됩니다.", "The original keeps only the rest, ${LibraryFormat.count(rest)}. A copy of it is backed up in the trash.", "原件只保留剩余的 ${LibraryFormat.count(rest)}。原件副本会备份到回收站。")
            },
            onClick = { actions.onMove(true) },
        )
    }
}

@Composable
private fun SplitPreview(ui: SplitUi, job: JobLine?, actions: SplitSheetActions) {
    val planned = SplitPlanner.planned(ui)
    val total = ui.counts?.total ?: 0L
    val sum = planned.sumOf { it.out.count }
    val rest = total - sum
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        planned.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DbCover(Covers.keyOf(p.cover, null), Modifier.size(40.dp), seed = p.cover.seed, corner = 8.dp)
                OutlinedTextField(
                    value = ui.names[i] ?: p.title,
                    onValueChange = { actions.onName(i, it) },
                    label = { Text(tr("이름", "Name", "名称")) },
                    singleLine = true,
                    enabled = !ui.running,
                    modifier = Modifier.weight(1f),
                )
                Text(LibraryFormat.count(p.out.count), style = MaterialTheme.typography.titleSmall)
            }
        }
        if (ui.move) CountLine(tr("«${ui.title}» 에 남는 것", "Left in «${ui.title}»", "«${ui.title}»中剩余"), LibraryFormat.count(rest))
    }
    val check = if (ui.move) {
        tr(
            "결과 ${LibraryFormat.count(sum)} + 남는 것 ${LibraryFormat.count(rest)} = 원본 ${LibraryFormat.count(total)} ${if (sum + rest == total) "✓" else "✗"}",
            "Results ${LibraryFormat.count(sum)} + rest ${LibraryFormat.count(rest)} = original ${LibraryFormat.count(total)} ${if (sum + rest == total) "✓" else "✗"}",
            "结果 ${LibraryFormat.count(sum)} + 剩余 ${LibraryFormat.count(rest)} = 原件 ${LibraryFormat.count(total)} ${if (sum + rest == total) "✓" else "✗"}",
        )
    } else {
        tr(
            "결과 ${LibraryFormat.count(sum)} · 원본 ${LibraryFormat.count(total)} 은 그대로",
            "Results ${LibraryFormat.count(sum)} · the original ${LibraryFormat.count(total)} stays as it is",
            "结果 ${LibraryFormat.count(sum)} · 原件 ${LibraryFormat.count(total)} 保持不变",
        )
    }
    Text(check, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (ui.attached) Hint(tr("붙어 있는 DB 는 먼저 저장하고 접은 뒤 나눕니다.", "The attached DB is saved and folded first.", "已连接的数据库会先保存并合并日志后再拆分。"))
    if (ui.running) RunningLine(job, tr("나누는 중", "Splitting", "拆分中"), actions.onCancelJob)
}
