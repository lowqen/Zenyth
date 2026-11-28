package dev.gomoku.zenyth.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.designsystem.component.Button
import dev.gomoku.zenyth.core.designsystem.component.ChipRow
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.ZenythFilterChip
import dev.gomoku.zenyth.core.designsystem.component.ZenythSheet
import dev.gomoku.zenyth.core.designsystem.component.ZenythSwitch
import dev.gomoku.zenyth.core.designsystem.cover.DbCover
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.Covers
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryFileNames

/**
 * «이름» (design §6.5): the title, and for a folder entry «파일 이름도 같이
 * 바꾸기» (on by default) with the filtered name it would get. App storage
 * files are `<uuid>.db` — there the title is all there is.
 */
@Composable
fun RenameSheet(ui: LibraryDetailUi, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var title by rememberSaveable(ui.id) { mutableStateOf(ui.title) }
    var alsoFile by rememberSaveable(ui.id) { mutableStateOf(true) }
    ZenythSheet(
        title = tr("이름 바꾸기", "Rename", "重命名"),
        onDismiss = onDismiss,
        dismiss = { TextButton(onClick = onDismiss) { Text(tr("닫기", "Close", "关闭")) } },
        confirm = {
            Button(onClick = { onConfirm(title, alsoFile) }, enabled = title.isNotBlank()) { Text(tr("확인", "OK", "确定")) }
        },
    ) {
        RenameSheetBody(ui, title, { title = it.take(120) }, alsoFile, { alsoFile = it })
    }
}

@Composable
fun RenameSheetBody(ui: LibraryDetailUi, title: String, onTitle: (String) -> Unit, alsoFile: Boolean, onAlsoFile: (Boolean) -> Unit) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitle,
        label = { Text(tr("이름", "Name", "名称")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (ui.inFolder) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("파일 이름도 같이 바꾸기", "Rename the file too", "同时重命名文件"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            ZenythSwitch(alsoFile, onAlsoFile)
        }
        val preview = remember(title, ui.takenNames) { LibraryFileNames.fileNameFor(title.trim().ifEmpty { ui.title }, ui.takenNames) }
        Hint(
            if (alsoFile) tr("파일: ${ui.file} → $preview", "File: ${ui.file} → $preview", "文件: ${ui.file} → $preview")
            else tr("파일: ${ui.file} (그대로)", "File: ${ui.file} (unchanged)", "文件: ${ui.file} (不变)"),
        )
    } else {
        Hint(tr("이 기기의 DB 는 이름만 바뀝니다. 파일 이름은 따로 없습니다.", "A DB on this device has a name only — no file name to change.", "本机数据库只改名称，没有单独的文件名。"))
    }
}

/**
 * «커버» (web `CoverSheet`, design §6.5): a live preview, [자동] [일식] — the
 * eclipse pressed again turns its pattern — and the 26 covers, 直 13 and 間 13,
 * seven to a row with a check on the chosen one. Written on [확인] only.
 */
@Composable
fun CoverSheet(ui: LibraryDetailUi, onDismiss: () -> Unit, onSave: (LibraryCover) -> Unit) {
    var draft by remember(ui.id) { mutableStateOf(ui.cover) }
    ZenythSheet(
        title = tr("커버", "Cover", "封面"),
        onDismiss = onDismiss,
        dismiss = { TextButton(onClick = onDismiss) { Text(tr("닫기", "Close", "关闭")) } },
        confirm = { Button(onClick = { onSave(draft) }) { Text(tr("확인", "OK", "确定")) } },
    ) {
        CoverSheetBody(ui.title, draft, ui.autoOpening) { draft = it }
    }
}

/** The key [draft] draws — «자동» from the entry's own counts. */
fun coverKeyFor(draft: LibraryCover, autoOpening: Int?): CoverKey =
    if (draft.mode == LibraryCover.AUTO && autoOpening != null) CoverKey(autoOpening)
    else Covers.keyOf(draft.copy(mode = if (draft.mode == LibraryCover.AUTO) LibraryCover.ECLIPSE else draft.mode), null)

@Composable
fun CoverSheetBody(title: String, draft: LibraryCover, autoOpening: Int?, onDraft: (LibraryCover) -> Unit) {
    val key = coverKeyFor(draft, autoOpening)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DbCover(key, Modifier.size(132.dp), seed = draft.seed, corner = 20.dp, label = title)
    }
    ChipRow {
        ZenythFilterChip(
            selected = draft.mode == LibraryCover.AUTO,
            onClick = { onDraft(draft.copy(mode = LibraryCover.AUTO)) },
            label = { Text(tr("자동", "Auto", "自动")) },
        )
        ZenythFilterChip(
            selected = draft.mode == LibraryCover.ECLIPSE,
            onClick = {
                onDraft(
                    if (draft.mode == LibraryCover.ECLIPSE) draft.copy(seed = Math.floorMod(draft.seed + 1, Covers.SEEDS))
                    else draft.copy(mode = LibraryCover.ECLIPSE),
                )
            },
            label = { Text(tr("일식", "Eclipse", "日食")) },
        )
    }
    Hint(
        when (draft.mode) {
            LibraryCover.AUTO -> tr("가장 많은 주형의 색입니다. 분류된 국면이 없으면 일식입니다.", "The colour of the most frequent opening — an eclipse when nothing is classified.", "最多的开局的颜色；没有已分类的局面时为日食。")
            LibraryCover.ECLIPSE -> tr("다시 누르면 무늬가 바뀝니다", "Press again for another pattern", "再按一次换图案")
            else -> draft.opening?.takeIf { it in 0 until Opening26.COUNT }?.let { "${Opening26.abbr[it]} ${Opening26.name(it)}" }.orEmpty()
        },
    )
    Text(tr("직접", "Direct", "直接"), style = MaterialTheme.typography.titleSmall)
    CoverGrid(0, draft) { onDraft(draft.copy(mode = LibraryCover.OPENING, opening = it)) }
    Text(tr("간접", "Indirect", "间接"), style = MaterialTheme.typography.titleSmall)
    CoverGrid(13, draft) { onDraft(draft.copy(mode = LibraryCover.OPENING, opening = it)) }
}

@Composable
private fun CoverGrid(from: Int, draft: LibraryCover, onPick: (Int) -> Unit) {
    val cells = (from until from + 13).toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { i ->
                    val on = draft.mode == LibraryCover.OPENING && draft.opening == i
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) { onPick(i) }
                            .semantics {
                                selected = on
                                contentDescription = "${Opening26.abbr[i]} ${Opening26.name(i)}"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            DbCover(
                                CoverKey(i),
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
                                corner = 8.dp,
                            )
                            if (on) {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                        Text(
                            Opening26.name(i),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // Keep the last row's cells the size of the others.
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** «다른 곳으로» (design §9-8): which source, and copy or move. */
@Composable
fun TransferSheet(ui: LibraryDetailUi, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var target by remember(ui.id) { mutableStateOf(ui.targets.firstOrNull()?.id) }
    var move by remember(ui.id) { mutableStateOf(false) }
    ZenythSheet(
        title = tr("다른 곳으로", "To another place", "移到别处"),
        onDismiss = onDismiss,
        dismiss = { TextButton(onClick = onDismiss) { Text(tr("닫기", "Close", "关闭")) } },
        confirm = {
            Button(onClick = { target?.let { onConfirm(it, move) } }, enabled = target != null) {
                Text(if (move) tr("옮기기", "Move", "移动") else tr("복사", "Copy", "复制"))
            }
        },
    ) {
        ChipRow {
            ui.targets.forEach { t ->
                ZenythFilterChip(selected = target == t.id, onClick = { target = t.id }, label = { Text(t.label) })
            }
        }
        ChipRow {
            ZenythFilterChip(selected = !move, onClick = { move = false }, label = { Text(tr("복사", "Copy", "复制")) })
            ZenythFilterChip(
                selected = move,
                onClick = { move = true },
                enabled = !ui.attached,
                label = { Text(tr("옮기기", "Move", "移动")) },
            )
        }
        Hint(
            if (move) tr("원본은 이곳의 휴지통으로 갑니다(30일).", "The original goes to this place's trash (30 days).", "原件移入此处的回收站（30 天）。")
            else tr("이름 · 커버 · 통계가 함께 갑니다.", "The name, cover and stats go with it.", "名称、封面和统计一并复制。"),
        )
    }
}
