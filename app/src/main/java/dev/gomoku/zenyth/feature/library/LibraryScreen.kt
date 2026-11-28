package dev.gomoku.zenyth.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gomoku.zenyth.core.designsystem.component.CalmCard
import dev.gomoku.zenyth.core.designsystem.component.ChipRow
import dev.gomoku.zenyth.core.designsystem.component.DisclosureCard
import dev.gomoku.zenyth.core.designsystem.component.EmptyState
import dev.gomoku.zenyth.core.designsystem.component.FilledTonalButton
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LabeledProgress
import dev.gomoku.zenyth.core.designsystem.component.LocalSnackbarHostState
import dev.gomoku.zenyth.core.designsystem.component.PillBadge
import dev.gomoku.zenyth.core.designsystem.component.RailLayoutMin
import dev.gomoku.zenyth.core.designsystem.component.ZenythCollapsingTopBar
import dev.gomoku.zenyth.core.designsystem.component.ZenythFilterChip
import dev.gomoku.zenyth.core.designsystem.component.ZenythSegmented
import dev.gomoku.zenyth.core.designsystem.cover.DbCover
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.data.library.OpenAt

/** What the first page can ask for. */
class LibraryHomeActions(
    val onSource: (String) -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onRegrant: (String) -> Unit = {},
    val onRemoveFolder: (String) -> Unit = {},
    val onCard: (String) -> Unit = {},
    val onSort: (LibrarySort) -> Unit = {},
    val onList: (Boolean) -> Unit = {},
    val onImport: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onRestore: (String) -> Unit = {},
    val onPurge: (String) -> Unit = {},
    val onCancelJob: () -> Unit = {},
)

/**
 * The library tab's first page (design §6.2, web `LibraryScreen`): the
 * chosen source's DBs as covers, «지금 붙은 DB» on top (the music app's «now
 * playing»), the trash under them, [+ 추가] for importing a file or making an
 * empty DB. 600dp and wider: the grid | the chosen card's page, side by side.
 *
 * Folder chips (L4): [이 기기] [폴더: …] [+ 폴더]. A folder whose grant is gone
 * keeps its chip; the line under the chips offers «폴더 다시 허용».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDetail: (String) -> Unit,
    onBoard: () -> Unit,
    onEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pickers = rememberLibraryPickers(viewModel)
    val snackbar = LocalSnackbarHostState.current

    LibraryEffects(viewModel, onBoard = onBoard, onEditor = onEditor, onBack = { viewModel.select(null) })
    LaunchedEffect(viewModel) {
        viewModel.undo.collect { u ->
            if (u == null) return@collect
            val r = snackbar.showSnackbar(
                message = tr("«${u.title}» 을(를) 휴지통으로 옮겼습니다", "Moved «${u.title}» to the trash", "已将«${u.title}»移入回收站"),
                actionLabel = tr("되돌리기", "Undo", "撤销"),
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) viewModel.restore(u.ids) else viewModel.dismissUndo()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= RailLayoutMin
        val actions = LibraryHomeActions(
            onSource = viewModel::selectSource,
            onAddFolder = { pickers.folder(null) },
            onRegrant = { id -> pickers.regrant(viewModel.reopenUri(id)) },
            onRemoveFolder = { viewModel.removeFolder(it) },
            onCard = { id -> if (wide) viewModel.select(id) else onOpenDetail(id) },
            onSort = viewModel::setSort,
            onList = viewModel::setList,
            onImport = { pickers.importFile() },
            onCreate = { viewModel.create { id -> if (wide) viewModel.select(id) else onOpenDetail(id) } },
            onRestore = { viewModel.restore(listOf(it)) },
            onPurge = { viewModel.purge(it) },
            onCancelJob = viewModel::cancelJob,
        )
        if (!wide) {
            LibraryHome(ui, actions)
        } else {
            Row(Modifier.fillMaxSize()) {
                LibraryHome(ui, actions, Modifier.weight(1f))
                val shownId = ui.selectedId ?: ui.now?.id
                Box(Modifier.weight(1f).fillMaxSize()) {
                    if (shownId != null) {
                        LibraryDetailPane(viewModel, shownId, busy, pickers, onBack = ui.selectedId?.let { { viewModel.select(null) } })
                    } else {
                        Hint(tr("왼쪽에서 DB 를 고르세요.", "Choose a DB on the left.", "请在左侧选择数据库。"), Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

/** The narrow screen's detail page — its own route (`library/db/{id}`). */
@Composable
fun LibraryDetailScreen(
    viewModel: LibraryViewModel,
    id: String,
    onBack: () -> Unit,
    onBoard: () -> Unit,
    onEditor: () -> Unit,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pickers = rememberLibraryPickers(viewModel)
    LibraryEffects(viewModel, onBoard = onBoard, onEditor = onEditor, onBack = onBack)
    LibraryDetailPane(viewModel, id, busy, pickers, onBack = onBack, missing = onBack)
}

/** A detail page with its sheets, from the view model. */
@Composable
private fun LibraryDetailPane(
    viewModel: LibraryViewModel,
    id: String,
    busy: Boolean,
    pickers: LibraryPickers,
    onBack: (() -> Unit)?,
    missing: (() -> Unit)? = null,
) {
    val flow = remember(viewModel, id) { viewModel.detail(id) }
    val detail by flow.collectAsStateWithLifecycle(initialValue = null)
    val ui = detail
    var sheet by remember(id) { mutableStateOf<DetailSheet?>(null) }
    // Gone (trashed, moved elsewhere) after it had been shown: back to the grid.
    var seen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(ui == null, seen) {
        if (ui != null) seen = true else if (seen) missing?.invoke()
    }
    if (ui == null) return
    LibraryDetail(
        ui = ui,
        busy = busy,
        onBack = onBack,
        actions = LibraryDetailActions(
            onOpenStart = { viewModel.open(id, OpenAt.Start) },
            onOpenLine = { viewModel.open(id, OpenAt.Line(it)) },
            onOpenEditor = { viewModel.open(id, OpenAt.Keep) },
            onRename = { sheet = DetailSheet.RENAME },
            onCover = { sheet = DetailSheet.COVER },
            onExport = { pickers.export(id, viewModel.exportName(ui.title)) },
            onTransfer = { sheet = DetailSheet.TRANSFER },
            onTrash = { viewModel.trash(id) },
            onRescan = { viewModel.rescan(id) },
            onStopScan = viewModel::stopScan,
        ),
    )
    when (sheet) {
        DetailSheet.RENAME -> RenameSheet(ui, onDismiss = { sheet = null }) { title, alsoFile ->
            sheet = null
            viewModel.rename(id, title, alsoFile)
        }
        DetailSheet.COVER -> CoverSheet(ui, onDismiss = { sheet = null }) { cover ->
            sheet = null
            viewModel.setCover(id, cover)
        }
        DetailSheet.TRANSFER -> TransferSheet(ui, onDismiss = { sheet = null }) { to, move ->
            sheet = null
            viewModel.transfer(id, to, move)
        }
        null -> Unit
    }
}

private enum class DetailSheet { RENAME, COVER, TRANSFER }

/** Notices to the snackbar; the view model's moves to the shell. */
@Composable
private fun LibraryEffects(viewModel: LibraryViewModel, onBoard: () -> Unit, onEditor: () -> Unit, onBack: () -> Unit) {
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                LibraryEvent.ToBoard -> onBoard()
                LibraryEvent.ToEditor -> onEditor()
                LibraryEvent.Back -> onBack()
            }
        }
    }
}

/** The system pickers the library uses — SAF folders, a file to import, a file to export to. */
class LibraryPickers(
    val folder: (android.net.Uri?) -> Unit,
    val regrant: (android.net.Uri?) -> Unit,
    val importFile: () -> Unit,
    val export: (id: String, name: String) -> Unit,
)

@Composable
private fun rememberLibraryPickers(viewModel: LibraryViewModel): LibraryPickers {
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onFolderPicked(it) }
    }
    val regrant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onFolderRegranted(it) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFile(it) }
    }
    var exporting by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) viewModel.export(id, uri)
    }
    return remember(folder, regrant, importer, export) {
        LibraryPickers(
            folder = { folder.launch(it) },
            regrant = { regrant.launch(it) },
            importFile = { importer.launch(arrayOf("*/*")) },
            export = { id, name ->
                exporting = id
                export.launch(name)
            },
        )
    }
}

/** The first page as the state says it — stateless, for the screen and its screenshots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHome(
    ui: LibraryUiState,
    actions: LibraryHomeActions,
    modifier: Modifier = Modifier,
    header: TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
) {
    var adding by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().nestedScroll(header.nestedScrollConnection)) {
            ZenythCollapsingTopBar(title = tr("라이브러리", "Library", "资料库"), scrollBehavior = header)
            LazyVerticalGrid(
                columns = if (ui.list) GridCells.Fixed(1) else GridCells.Adaptive(148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (ui.list) 8.dp else 16.dp),
            ) {
                full("sources") { SourceChips(ui, actions) }
                ui.folder?.let { f -> full("folder") { FolderLine(f, actions) } }
                ui.now?.let { now -> full("now") { NowCard(now, onClick = { actions.onCard(now.id) }) } }
                full("tools") { ToolsRow(ui, actions) }
                if (ui.loaded && ui.cards.isEmpty()) {
                    full("empty") {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = tr("아직 DB 가 없습니다", "No DBs yet", "还没有数据库"),
                            body = tr("가져오거나 새로 만드세요.", "Import one or make a new one.", "请导入或新建。"),
                        )
                    }
                }
                items(ui.cards, key = { it.id }) { card ->
                    if (ui.list) {
                        ListRow(card, card.id == ui.selectedId, onClick = { actions.onCard(card.id) })
                    } else {
                        CoverCard(card, card.id == ui.selectedId, onClick = { actions.onCard(card.id) })
                    }
                }
                if (ui.trash.isNotEmpty()) full("trash") { TrashFold(ui.trash, actions) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.job?.let { JobBar(it, actions.onCancelJob) }
            Box {
                ExtendedFloatingActionButton(
                    onClick = { if (!ui.blocked) adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(tr("추가", "Add", "添加")) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("가져오기 (.db)", "Import (.db)", "导入 (.db)")) },
                        onClick = {
                            adding = false
                            actions.onImport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("새 DB", "New DB", "新数据库")) },
                        onClick = {
                            adding = false
                            actions.onCreate()
                        },
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SourceChips(ui: LibraryUiState, actions: LibraryHomeActions) {
    ChipRow {
        ui.sources.forEach { s ->
            ZenythFilterChip(
                selected = s.id == ui.sourceId,
                onClick = { actions.onSource(s.id) },
                label = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        ZenythFilterChip(selected = false, onClick = actions.onAddFolder, label = { Text(tr("+ 폴더", "+ Folder", "+ 文件夹")) })
    }
}

@Composable
private fun FolderLine(f: SourceChip, actions: LibraryHomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Hint(
            if (f.lost) tr("이 폴더를 다시 쓰려면 허용이 필요합니다.", "This folder needs access again.", "需要重新授权此文件夹。")
            else tr("폴더의 .db 파일이 보입니다. 이름 · 커버는 폴더의 zenyth-library.json 에 함께 둡니다.", "The folder's .db files show here. Names and covers live beside them in zenyth-library.json.", "显示文件夹中的 .db 文件。名称与封面保存在同一文件夹的 zenyth-library.json。"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (f.lost) TextButton(onClick = { actions.onRegrant(f.id) }) { Text(tr("폴더 다시 허용", "Allow the folder again", "重新授权文件夹")) }
            TextButton(onClick = { actions.onRemoveFolder(f.id) }) { Text(tr("라이브러리에서 빼기", "Remove from the library", "从资料库移除")) }
        }
    }
}

@Composable
private fun NowCard(now: LibraryCard, onClick: () -> Unit) {
    CalmCard {
        Text(
            tr("지금 붙은 DB", "Attached now", "当前连接的数据库"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DbCover(now.cover, Modifier.size(96.dp), seed = now.seed, corner = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(now.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(now.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onClick) { Text(tr("기보 보기", "View", "查看")) }
            }
        }
    }
}

@Composable
private fun ToolsRow(ui: LibraryUiState, actions: LibraryHomeActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZenythSegmented(
            options = listOf(
                LibrarySort.RECENT to tr("최근", "Recent", "最近"),
                LibrarySort.NAME to tr("이름", "Name", "名称"),
                LibrarySort.SIZE to tr("크기", "Size", "大小"),
            ),
            selection = ui.sort,
            onSelect = actions.onSort,
        )
        Spacer(Modifier.weight(1f))
        ZenythSegmented(
            options = listOf(false to tr("격자", "Grid", "网格"), true to tr("목록", "List", "列表")),
            selection = ui.list,
            onSelect = actions.onList,
        )
    }
}

@Composable
private fun CoverCard(card: LibraryCard, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            DbCover(card.cover, Modifier.fillMaxWidth().aspectRatio(1f), seed = card.seed, corner = 12.dp)
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (card.attached) PillBadge(tr("붙음", "Attached", "已连接"), accent = true)
                if (card.working) PillBadge(tr("작업 중", "Working", "处理中"))
            }
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(card: LibraryCard, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DbCover(card.cover, Modifier.size(56.dp), seed = card.seed, corner = 10.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (card.attached) PillBadge(tr("붙음", "Attached", "已连接"), accent = true)
            if (card.working) PillBadge(tr("작업 중", "Working", "处理中"))
        }
    }
}

@Composable
private fun TrashFold(rows: List<TrashRow>, actions: LibraryHomeActions) {
    DisclosureCard(id = "lib-trash", title = tr("휴지통 (${rows.size})", "Trash (${rows.size})", "回收站 (${rows.size})")) {
        Hint(tr("30일이 지나면 저절로 지워집니다.", "Deleted for good after 30 days.", "30 天后自动永久删除。"))
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { actions.onRestore(r.id) }) { Text(tr("되돌리기", "Restore", "恢复")) }
                TextButton(onClick = { actions.onPurge(r.id) }) { Text(tr("아주 지우기", "Delete", "彻底删除"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** A copy or import at work (design §6.2 «작업 줄»): label, progress, [취소]. */
@Composable
private fun JobBar(job: JobLine, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Row(
            Modifier.width(280.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(job.label, style = MaterialTheme.typography.labelLarge)
                LabeledProgress(job.fraction)
            }
            TextButton(onClick = onCancel, enabled = !job.cancelling) { Text(tr("취소", "Cancel", "取消")) }
        }
    }
}
