package dev.gomoku.zenyth.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.designsystem.component.Button
import dev.gomoku.zenyth.core.designsystem.component.CalmCard
import dev.gomoku.zenyth.core.designsystem.component.CardTitle
import dev.gomoku.zenyth.core.designsystem.component.DisclosureCard
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LabeledProgress
import dev.gomoku.zenyth.core.designsystem.component.MiniBoard
import dev.gomoku.zenyth.core.designsystem.component.OutlinedButton
import dev.gomoku.zenyth.core.designsystem.component.PillBadge
import dev.gomoku.zenyth.core.designsystem.component.RoundBackButton
import dev.gomoku.zenyth.core.designsystem.component.rememberCalmFling
import dev.gomoku.zenyth.core.designsystem.cover.CoverPalette
import dev.gomoku.zenyth.core.designsystem.cover.CoverTheme
import dev.gomoku.zenyth.core.designsystem.cover.DbCover
import dev.gomoku.zenyth.core.designsystem.cover.coverDark
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.LibraryPositions
import dev.gomoku.zenyth.domain.library.StartMode

/** What the detail page can ask for. */
class LibraryDetailActions(
    val onOpenStart: () -> Unit = {},
    val onOpenLine: (List<Move>) -> Unit = {},
    val onOpenEditor: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onCover: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onTransfer: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
)

private const val ROOTS_SHOWN = 8

/**
 * A library entry's page (web `LibraryDetail`, design §6.3): the page in the
 * cover's colours ([CoverTheme]), a head — the big cover (→ cover sheet), the
 * title (→ rename sheet), chips, one filled [이 DB 열기] — then the openings and
 * the roots, [빈 판에서 열기] [편집기로] (always: no stats are needed to open),
 * the records, and the entry's own management.
 *
 * Unclassified positions outweighing classified ones put the roots first and
 * fold the distribution. Without the index one line says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryDetail(
    ui: LibraryDetailUi,
    actions: LibraryDetailActions,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
) {
    CoverTheme(ui.coverKey) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState(), flingBehavior = rememberCalmFling()) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onBack != null) RoundBackButton(onBack, Modifier.padding(bottom = 4.dp))
                DetailHead(ui, actions, busy)
                StartCard(ui, actions, busy)
                ScanLineCard(ui, actions)
                if (ui.rootsFirst) {
                    RootsSection(ui, actions, busy)
                    OpeningsSection(ui, actions, busy)
                } else {
                    OpeningsSection(ui, actions, busy)
                    RootsSection(ui, actions, busy)
                }
                CalmCard {
                    CardTitle(tr("열기", "Open", "打开"))
                    Hint(tr("통계가 없어도 늘 열 수 있습니다.", "Opens with or without stats.", "没有统计也随时可以打开。"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.onOpenLine(emptyList()) }, enabled = !busy) { Text(tr("빈 판에서 열기", "Open on an empty board", "在空棋盘打开")) }
                        OutlinedButton(onClick = actions.onOpenEditor, enabled = !busy) { Text(tr("편집기로", "To the editor", "到编辑器")) }
                    }
                }
                ui.records?.let { RecordsSection(it) }
                ManageSection(ui, actions)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHead(ui: LibraryDetailUi, actions: LibraryDetailActions, busy: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = if (maxWidth >= 520.dp) 176.dp else 148.dp
        val cover = @Composable {
            DbCover(
                ui.coverKey,
                Modifier
                    .size(side)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button, onClickLabel = tr("커버 바꾸기", "Change cover", "更换封面"), onClick = actions.onCover),
                seed = ui.cover.seed,
                corner = 20.dp,
                label = ui.title,
            )
        }
        val text = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    ui.title,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = tr("이름 바꾸기", "Rename", "重命名"), onClick = actions.onRename),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ui.attached) PillBadge(tr("붙음", "Attached", "已连接"), accent = true)
                    ui.chips.forEach { PillBadge(it) }
                }
                Button(onClick = actions.onOpenStart, enabled = !busy) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("이 DB 열기", "Open this DB", "打开此数据库"))
                }
            }
        }
        if (maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                cover()
                text(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cover()
                text(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * «기록 시작점» (L5-6 P3): the position «이 DB 열기» goes to, as a small board —
 * by default where the records branch, past the empty lookup records the
 * engine leaves on its way in.
 */
@Composable
private fun StartCard(ui: LibraryDetailUi, actions: LibraryDetailActions, busy: Boolean) {
    val s = ui.start ?: return
    CalmCard {
        CardTitle(tr("기록 시작점", "Where the records start", "记录起点"))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !busy, role = Role.Button, onClick = actions.onOpenStart)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniBoard(s.moves, Modifier.size(72.dp), showNumbers = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (s.moves.isEmpty()) tr("빈 판에서 엽니다", "Opens on an empty board", "从空棋盘打开")
                    else tr("${s.moves.size}수 국면에서 엽니다", "Opens at a ${s.moves.size}-move position", "从 ${s.moves.size} 手局面打开"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    when (s.mode) {
                        StartMode.BRANCH ->
                            if (s.branched) tr("기록이 갈라지는 곳 — 그 앞은 엔진이 지나며 남긴 빈 기록입니다", "Where the records branch — before it, only the empty records the engine left on its way", "记录分叉处——之前只是引擎经过时留下的空记录")
                            else tr("기록이 갈라지는 곳", "Where the records branch", "记录分叉处")
                        StartMode.ROOT -> tr("가장 큰 뿌리 국면", "The largest root position", "最大的根局面")
                        StartMode.EMPTY -> tr("늘 빈 판에서 (설정 ▸ 데이터)", "Always an empty board (Settings ▸ Data)", "总是空棋盘 (设置 ▸ 数据)")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScanLineCard(ui: LibraryDetailUi, actions: LibraryDetailActions) {
    val line = ui.scanLine
    if (line == ScanLine.NONE) return
    CalmCard {
        when (line) {
            ScanLine.COUNTING -> {
                Hint(tr("통계를 세는 중…", "Counting the stats…", "正在统计…"))
                LabeledProgress(ui.scanFraction)
                TextButton(onClick = actions.onStopScan) { Text(tr("그만 세기", "Stop counting", "停止统计")) }
            }
            ScanLine.EMPTY -> Hint(tr("아직 비어 있습니다. 붙여서 쓰면 국면이 쌓입니다.", "Empty for now — attach it and positions collect.", "目前为空，连接使用后会积累局面。"))
            ScanLine.WAITING -> Hint(tr("통계를 셀 차례를 기다리는 중…", "Waiting its turn to be counted…", "等待统计…"))
            ScanLine.ERROR, ScanLine.STOPPED -> {
                Hint(
                    if (line == ScanLine.ERROR) tr("이 파일의 통계를 세지 못했습니다.", "This file's stats could not be counted.", "无法统计此文件。")
                    else tr("통계 세기를 멈췄습니다.", "Counting was stopped.", "已停止统计。"),
                )
                TextButton(onClick = actions.onRescan) { Text(tr("다시 세기", "Count again", "重新统计")) }
            }
            ScanLine.MEMORY -> {
                Hint(tr("주형 · 시작점 통계를 생략했습니다(메모리 부족 또는 시스템 중단).", "Openings and starting points were skipped (low memory, or the system stopped the count).", "已省略开局与起点统计（内存不足或被系统中断）。"))
                TextButton(onClick = actions.onRescan) { Text(tr("다시 세기", "Count again", "重新统计")) }
            }
            ScanLine.UNORDERED -> Hint(tr("이 파일은 국면 순서가 엔진과 달라 주형을 정하지 않았습니다.", "This file's positions are not in the engine's order, so openings were not settled.", "此文件的局面顺序与引擎不同，未判定开局。"))
            ScanLine.NONE -> Unit
        }
    }
}

@Composable
private fun OpeningsSection(ui: LibraryDetailUi, actions: LibraryDetailActions, busy: Boolean) {
    if (ui.openings.isEmpty() || ui.classified == 0L) return
    val max = maxOf(1L, ui.openings.maxOf { it.count }, ui.unclassified)
    DisclosureCard(
        id = "lib-openings",
        title = tr("주형 분포", "Openings", "开局分布"),
        summary = tr(
            "분류 ${LibraryFormat.count(ui.classified)} · 미분류 ${LibraryFormat.count(ui.unclassified)}",
            "${LibraryFormat.count(ui.classified)} classified · ${LibraryFormat.count(ui.unclassified)} not",
            "已分类 ${LibraryFormat.count(ui.classified)} · 未分类 ${LibraryFormat.count(ui.unclassified)}",
        ),
        defaultOpen = !ui.rootsFirst,
    ) {
        val dark = coverDark
        ui.openings.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = tr("대표 국면을 판에서 열기", "Open its position on the board", "在棋盘上打开代表局面")) {
                        actions.onOpenLine(LibraryPositions.openingMoves(r.index))
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DbCover(CoverKey(r.index), Modifier.size(36.dp), corner = 8.dp)
                Text(
                    "${r.abbr} ${r.name}",
                    Modifier.width(92.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Bar(r.count.toFloat() / max, CoverPalette.coverArt(CoverKey(r.index), dark).line, Modifier.weight(1f))
                CountText(r.count)
            }
        }
        if (ui.unclassified > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.size(36.dp))
                Text(tr("미분류", "Unclassified", "未分类"), Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium)
                Bar(ui.unclassified.toFloat() / max, MaterialTheme.colorScheme.outline, Modifier.weight(1f))
                CountText(ui.unclassified)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

@Composable
private fun CountText(n: Long) {
    Text(
        LibraryFormat.count(n),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(56.dp),
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
private fun RootsSection(ui: LibraryDetailUi, actions: LibraryDetailActions, busy: Boolean) {
    if (ui.roots.isEmpty()) return
    var all by rememberSaveable(ui.id) { mutableStateOf(false) }
    CalmCard {
        CardTitle(tr("뿌리 국면", "Root positions", "根局面"))
        Hint(tr("부모가 DB 에 없는 국면입니다. 아래로 이어진 국면이 많은 순서입니다.", "Positions whose parent is not in the DB, the most positions below first.", "父局面不在数据库中的局面，按其下局面数排序。"))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (if (all) ui.roots else ui.roots.take(ROOTS_SHOWN)).forEach { r -> RootRowView(r, actions, busy) }
        }
        if (ui.roots.size > ROOTS_SHOWN) {
            TextButton(onClick = { all = !all }) {
                Text(if (all) tr("줄이기", "Show fewer", "收起") else tr("모두 보기 (${ui.roots.size})", "Show all (${ui.roots.size})", "全部显示 (${ui.roots.size})"))
            }
        }
    }
}

@Composable
private fun RootRowView(r: RootRow, actions: LibraryDetailActions, busy: Boolean) {
    val moves = r.moves
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy && moves != null, role = Role.Button) { moves?.let(actions.onOpenLine) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (moves != null) {
            MiniBoard(moves, Modifier.size(56.dp), showNumbers = false)
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    moves == null -> tr("이 판 크기에서는 열 수 없음", "Not on this board size", "此棋盘尺寸无法打开")
                    moves.isEmpty() -> tr("빈 판", "Empty board", "空棋盘")
                    else -> tr("${moves.size}수 국면", "${moves.size}-move position", "${moves.size} 手局面")
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                tr("아래 ${LibraryFormat.positions(r.subtree)}", "${LibraryFormat.positions(r.subtree)} below", "其下 ${LibraryFormat.positions(r.subtree)}"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (moves != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordsSection(r: RecordsUi) {
    CalmCard {
        CardTitle(tr("기록", "Records", "记录"))
        Row(Modifier.fillMaxWidth()) {
            Record("W", r.w)
            Record("L", r.l)
            Record("D", r.d)
            Record(tr("주석", "Notes", "注释"), r.comments)
        }
        r.rules?.let { Hint(it) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Record(label: String, n: Long) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(LibraryFormat.count(n), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ManageSection(ui: LibraryDetailUi, actions: LibraryDetailActions) {
    CalmCard {
        CardTitle(tr("관리", "Manage", "管理"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onCover) { Text(tr("커버 바꾸기", "Change cover", "更换封面")) }
            OutlinedButton(onClick = actions.onRename, enabled = !ui.working) { Text(tr("이름 바꾸기", "Rename", "重命名")) }
            OutlinedButton(onClick = actions.onExport, enabled = ui.size > 0) { Text(tr(".db 내보내기", "Export .db", "导出 .db")) }
            if (ui.targets.isNotEmpty()) {
                OutlinedButton(onClick = actions.onTransfer, enabled = ui.size > 0 && !ui.working) { Text(tr("다른 곳으로", "To another place", "移到别处")) }
            }
            TextButton(onClick = actions.onTrash, enabled = !ui.attached && !ui.working) {
                Text(tr("지우기", "Delete", "删除"), color = if (!ui.attached && !ui.working) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ui.attached) Hint(tr("붙어 있는 DB 는 지울 수 없습니다. 다른 DB 를 먼저 여세요.", "The attached DB cannot be deleted — open another one first.", "已连接的数据库不能删除，请先打开其他数据库。"))
    }
}
