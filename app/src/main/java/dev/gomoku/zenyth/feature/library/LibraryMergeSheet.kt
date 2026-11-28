package dev.gomoku.zenyth.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.designsystem.component.Button
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LabeledProgress
import dev.gomoku.zenyth.core.designsystem.component.PillBadge
import dev.gomoku.zenyth.core.designsystem.component.StepDots
import dev.gomoku.zenyth.core.designsystem.component.ZenythSheet
import dev.gomoku.zenyth.core.designsystem.component.ZenythSwitch
import dev.gomoku.zenyth.core.designsystem.cover.DbCover
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy

/** What the merge sheet asks for. */
class MergeSheetActions(
    val onMove: (index: Int, by: Int) -> Unit = { _, _ -> },
    val onPolicy: (MergePolicy) -> Unit = {},
    val onJoin: (Boolean) -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onTrash: (Boolean) -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onCommit: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onCancelJob: () -> Unit = {},
)

/**
 * «합치기» (design §6.5, web `MergeSheet`) — three steps: ① the order, the top
 * one the base (▲▼ buttons, not dragging — they read out and need no
 * precision) → ② what wins when a position is in more than one → ③ the
 * preview, counted before a byte is written, with the name and «원본을
 * 휴지통으로». The result is always a new DB. Closing while it works stops it.
 */
@Composable
fun LibraryMergeSheet(ui: MergeUi, job: JobLine?, actions: MergeSheetActions) {
    ZenythSheet(
        title = tr("합치기", "Merge", "合并"),
        onDismiss = actions.onClose,
        dismiss = {
            if (ui.step == 1) {
                TextButton(onClick = actions.onClose) { Text(tr("닫기", "Close", "关闭")) }
            } else {
                TextButton(onClick = actions.onBack, enabled = !ui.running) { Text(tr("이전", "Back", "上一步")) }
            }
        },
        confirm = {
            when (ui.step) {
                1 -> Button(onClick = actions.onNext) { Text(tr("다음", "Next", "下一步")) }
                2 -> Button(onClick = actions.onNext) { Text(tr("미리 보기", "Preview", "预览")) }
                else -> Button(
                    onClick = actions.onCommit,
                    enabled = ui.counts != null && !ui.busy && ui.title.isNotBlank(),
                ) { Text(tr("합치기", "Merge", "合并")) }
            }
        },
    ) {
        MergeSheetBody(ui, job, actions)
    }
}

/** The sheet's body as the state says it — stateless, for the sheet and its screenshots. */
@Composable
fun MergeSheetBody(ui: MergeUi, job: JobLine?, actions: MergeSheetActions) {
    StepDots(ui.step, listOf(tr("순서", "Order", "顺序"), tr("겹칠 때", "Overlaps", "重叠时"), tr("미리 보기", "Preview", "预览")))
    when (ui.step) {
        1 -> MergeOrder(ui, actions)
        2 -> MergePolicyStep(ui, actions)
        else -> MergePreview(ui, job, actions)
    }
    ui.error?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun MergeOrder(ui: MergeUi, actions: MergeSheetActions) {
    Hint(tr("맨 위가 기준입니다. 겹치는 국면을 정할 때 앞섭니다.", "The top one is the base: it leads when positions overlap.", "最上面的是基准，处理重叠局面时优先。"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.rows.forEachIndexed { i, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DbCover(row.cover, Modifier.size(44.dp), seed = row.seed, corner = 8.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (i == 0) PillBadge(tr("기준", "Base", "基准"), accent = true)
                    }
                    Text(row.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { actions.onMove(i, -1) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = tr("«${row.title}» 위로", "Move «${row.title}» up", "将«${row.title}»上移"))
                }
                IconButton(onClick = { actions.onMove(i, 1) }, enabled = i < ui.rows.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = tr("«${row.title}» 아래로", "Move «${row.title}» down", "将«${row.title}»下移"))
                }
            }
        }
    }
    if (ui.mixedRules) {
        Hint(tr("규칙 · 판이 섞여 있습니다. 국면 키에 규칙이 있어 합칠 수는 있습니다.", "Rules or boards differ. They can still merge — each key holds its rule.", "规则或棋盘不同。由于局面键包含规则，仍可合并。"))
    }
}

@Composable
private fun MergePolicyStep(ui: MergeUi, actions: MergeSheetActions) {
    Text(tr("겹칠 때", "When positions overlap", "局面重叠时"), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceRow(
            selected = ui.policy == MergePolicy.DEEPER,
            title = tr("더 깊은 기록(권장)", "The deeper record (recommended)", "更深的记录（推荐）"),
            body = tr("깊이가 큰 쪽, 같으면 승패가 정해진 쪽, 그래도 같으면 기준.", "The greater depth; if equal, the solved one; if still equal, the base.", "深度更大者；相同时取已定胜负者；仍相同时取基准。"),
            onClick = { actions.onPolicy(MergePolicy.DEEPER) },
        )
        ChoiceRow(
            selected = ui.policy == MergePolicy.BASE,
            title = tr("기준 우선", "Base first", "基准优先"),
            body = tr("맨 위 DB 의 기록을 씁니다. 거기 없는 국면만 다른 DB 에서.", "The top DB's record; other DBs only for positions it lacks.", "使用最上面数据库的记录，仅其中没有的局面取自其他数据库。"),
            onClick = { actions.onPolicy(MergePolicy.BASE) },
        )
    }
    SwitchRow(
        checked = ui.joinComments,
        title = tr("주석 이어 붙이기", "Join comments", "拼接注释"),
        body = tr("같은 글은 하나로 둡니다.", "The same text is kept once.", "相同的文字只保留一份。"),
        onChange = actions.onJoin,
    )
}

@Composable
private fun MergePreview(ui: MergeUi, job: JobLine?, actions: MergeSheetActions) {
    val c = ui.counts
    if (ui.counting || c == null) {
        if (ui.error == null) {
            Text(tr("세는 중…", "Counting…", "统计中…"), style = MaterialTheme.typography.bodyMedium)
            LabeledProgress(job?.fraction ?: 0f)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CountLine(tr("합계", "Total", "合计"), LibraryFormat.positions(c.positions))
        CountLine(tr("겹침", "Overlaps", "重叠"), LibraryFormat.count(c.overlaps))
        CountLine(tr("값이 다른 것", "Differing", "数值不同"), LibraryFormat.count(c.differing))
    }
    OutlinedTextField(
        value = ui.title,
        onValueChange = actions.onTitle,
        label = { Text(tr("이름", "Name", "名称")) },
        singleLine = true,
        enabled = !ui.running,
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(
        checked = ui.trashOriginals,
        title = tr("원본을 휴지통으로", "Move the originals to the trash", "将原件移入回收站"),
        body = if (ui.hasAttached) tr("붙어 있는 DB 는 남깁니다.", "The attached DB stays.", "已连接的数据库会保留。")
        else tr("휴지통에서 30일 안에 되돌릴 수 있습니다.", "They can be restored from the trash within 30 days.", "30 天内可从回收站恢复。"),
        enabled = !ui.running,
        onChange = actions.onTrash,
    )
    if (ui.hasAttached) {
        Hint(tr("붙어 있는 DB 는 먼저 저장하고 접은 뒤 합칩니다.", "The attached DB is saved and folded first.", "已连接的数据库会先保存并合并日志后再合并。"))
    }
    if (ui.running) RunningLine(job, tr("합치는 중", "Merging", "合并中"), actions.onCancelJob)
}

// ── shared bits of the two op sheets ───────────────────────────────────

/** A radio choice with a line under it. */
@Composable
internal fun ChoiceRow(selected: Boolean, title: String, body: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A switch with its name and a line under it, the whole row the target. */
@Composable
internal fun SwitchRow(checked: Boolean, title: String, body: String, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ZenythSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun CountLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** The job at work in the sheet: what, how far, [취소]. */
@Composable
internal fun RunningLine(job: JobLine?, label: String, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job?.label ?: label, style = MaterialTheme.typography.labelLarge)
            LabeledProgress(job?.fraction ?: 0f)
        }
        TextButton(onClick = onCancel, enabled = job?.cancelling != true) { Text(tr("취소", "Cancel", "取消")) }
    }
}
