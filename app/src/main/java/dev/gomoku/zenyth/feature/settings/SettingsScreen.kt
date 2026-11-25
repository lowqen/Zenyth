package dev.gomoku.zenyth.feature.settings

import dev.gomoku.zenyth.core.designsystem.component.ZenythFilterChip
import dev.gomoku.zenyth.core.designsystem.component.ZenythSwitch
import dev.gomoku.zenyth.core.designsystem.component.QuietSwitch
import androidx.activity.compose.BackHandler
import dev.gomoku.zenyth.core.designsystem.theme.RowNoteStyle
import dev.gomoku.zenyth.core.designsystem.theme.RowTitleStyle
import dev.gomoku.zenyth.core.designsystem.theme.LocalProMode
import dev.gomoku.zenyth.core.designsystem.theme.ZenythTheme
import dev.gomoku.zenyth.core.designsystem.component.rememberCalmFling
import dev.gomoku.zenyth.core.designsystem.component.staggerIn
import dev.gomoku.zenyth.core.designsystem.component.rememberStagger
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import dev.gomoku.zenyth.core.designsystem.component.Button
import androidx.compose.material3.MaterialTheme
import dev.gomoku.zenyth.core.designsystem.component.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gomoku.zenyth.core.designsystem.component.LocalSnackbarHostState
import dev.gomoku.zenyth.core.designsystem.component.ZenythCollapsingTopBar
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.designsystem.component.ConnectedGroup
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LocalUiPrefs
import dev.gomoku.zenyth.core.designsystem.theme.themeFollowsSystem
import dev.gomoku.zenyth.core.model.LocalEngineProfile
import dev.gomoku.zenyth.core.model.SettingEditor
import dev.gomoku.zenyth.core.model.SettingSpec
import dev.gomoku.zenyth.feature.data.RifBuildDialog
import dev.gomoku.zenyth.feature.onboarding.LocalOpenTour

/**
 * Settings, as a list of pages (UI overhaul 2, M3): the first page names the
 * kinds of setting, each opens one level down with a round back button, and
 * the desktop's own entries — generated from
 * [dev.gomoku.zenyth.core.model.DesktopSettings], so they cannot drift from
 * the file layout — are one more page in expert mode. The pages are this
 * screen's own state, not navigation routes: the tab bar stays where it is and
 * the system back goes up a page before it leaves the tab.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> viewModel.onExport(uri) }
    val prefs = LocalUiPrefs.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // A PC settings file is imported so the app looks like the PC: its
        // line 27 decides light or dark again, not the phone (decision D4).
        if (uri != null) prefs?.themeFollowsSystem?.set(false)
        viewModel.onImport(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onImportAppearance(uri) }
    val debugLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) viewModel.onExportDebugLog(uri) }
    // rememberSaveable, not remember: a rotation — or the language change,
    // which recreates the activity — is exactly when losing the open page or
    // dialog is most annoying.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    var showRifBuild by rememberSaveable { mutableStateOf(false) }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.Root.name) }
    val page = SettingsPage.valueOf(pageName)
    val open: (SettingsPage) -> Unit = { pageName = it.name }
    BackHandler(enabled = page != SettingsPage.Root) { open(SettingsPage.Root) }

    // One snackbar for the whole app (see [LocalSnackbarHostState]).
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // «고급 사용자 모드» also shows the PC settings; the list follows the saved
    // mode whenever the screen opens, and its page closes if the mode goes off.
    val pro = LocalProMode.current
    LaunchedEffect(pro) { if (ui.advanced != pro) viewModel.onAdvanced(pro) }
    LaunchedEffect(pro, page) { if (!pro && page == SettingsPage.Desktop) open(SettingsPage.Root) }

    val actions = SettingsActions(
        onOpen = open,
        onQuery = viewModel::onQuery,
        onValue = viewModel::onValue,
        onDark = { viewModel.onValue("darkMode", if (it) "1" else "0") },
        onPro = { on ->
            prefs?.proMode?.set(on)
            viewModel.onAdvanced(on)
        },
        onThreads = viewModel::onLocalThreads,
        onHash = viewModel::onLocalHash,
        onDatabase = viewModel::onLocalDatabase,
        onServerEnabled = viewModel::onServerEnabled,
        onLocalMode = viewModel::onLocalMode,
        onEndpoint = viewModel::onEndpoint,
        onImport = { file ->
            viewModel.prepare(file)
            importLauncher.launch(arrayOf("*/*"))
        },
        onExport = { file ->
            viewModel.prepare(file)
            exportLauncher.launch(file.fileName)
        },
        onFolder = { folderLauncher.launch(null) },
        onResetAppearance = viewModel::onResetAppearance,
        onExportDebugLog = { debugLogLauncher.launch("zenyth-debug.log") },
        onClearDebugLog = viewModel::onClearDebugLog,
        onReset = viewModel::onReset,
        onRifBuild = { showRifBuild = true },
        onAbout = { showAbout = true },
        onLicenses = { showLicenses = true },
        onTour = LocalOpenTour.current,
    )

    QuietSwitch(page, modifier.fillMaxSize()) { shown ->
        SettingsPageFrame(
            title = shown.title,
            onBack = if (shown == SettingsPage.Root) null else ({ open(SettingsPage.Root) }),
            sections = settingsSections(shown, ui, actions, pro, prefs),
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onShowLicenses = { showAbout = false; showLicenses = true },
            onShowWhatsNew = { showAbout = false; showWhatsNew = true },
        )
    }
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    if (showWhatsNew) {
        val context = LocalContext.current
        val notes = remember { WhatsNew.load(context) }
        WhatsNewDialog(
            if (notes.isEmpty) ReleaseNotes(intro = tr("이 버전의 안내가 없습니다.", "No notes for this version.", "此版本没有说明。")) else notes,
            onDismiss = { showWhatsNew = false },
        )
    }
    if (showRifBuild) {
        RifBuildDialog(onDismiss = { showRifBuild = false })
    }
}

/**
 * One page: the tall title that folds (M2), with the round back button below
 * the first page, over its blocks. Each page its own header, so a page opens
 * with its title tall whatever the last one was scrolled to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageFrame(
    title: String,
    onBack: (() -> Unit)?,
    sections: List<SettingsSection>,
) {
    val header = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(header.nestedScrollConnection)) {
        ZenythCollapsingTopBar(title = title, scrollBehavior = header, onBack = onBack)
        // The blocks rise in one after another as a page opens (P5).
        val stagger = rememberStagger()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(ZenythTheme.tokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(ZenythTheme.tokens.listGap),
            flingBehavior = rememberCalmFling(),
        ) {
            sections.forEachIndexed { i, section ->
                item(key = section.key) { Box(Modifier.staggerIn(stagger, i)) { section.content() } }
            }
        }
    }
}

/**
 * The on-device engine's own settings.
 *
 * Its own card rather than three more rows in the list, because they are not the
 * same kind of thing: every row below comes from a numbered line of
 * `settings.txt`/`settings_dev.txt` and is sent to whichever engine is
 * connected. These three exist only on the phone, are stored outside
 * `AppSettings` so the file codec keeps its 47+20 shape, and **replace** the
 * desktop's threads/hash/database while the on-device engine is the chosen one.
 */
@Composable
internal fun LocalEngineCard(
    ui: SettingsUiState,
    onHash: (Int) -> Unit,
    onDatabase: (Boolean) -> Unit,
    onServerEnabled: (Boolean) -> Unit,
) {
    ConnectedGroup(title = tr("기기 내 엔진", "On-device engine", "本机引擎")) {
        row {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("해시 크기 (MB)", "Hash size (MB)", "哈希表大小 (MB)"), style = RowTitleStyle)
                    Hint(tr("길게 읽을수록 도움 · 그만큼 메모리", "Helps long searches · costs that much memory", "长时间搜索更有利 · 占用同等内存"))
                }
                CommitField(
                    value = ui.localProfile.hashMb.toString(),
                    onCommit = { raw -> raw.toIntOrNull()?.let(onHash) },
                    keyboard = KeyboardType.Number,
                    modifier = Modifier.width(96.dp),
                    supporting = "${LocalEngineProfile.MIN_HASH_MB}~${LocalEngineProfile.MAX_HASH_MB}",
                )
            }
            // The phone's own ceiling after a kill for memory (T3) — said here, where the
            // number it overrides is, and lifted by picking a size again.
            val cap = ui.memoryCapMb
            if (cap != null && cap < ui.localProfile.hashMb) {
                Hint(
                    tr(
                        "메모리 부족으로 ${cap} MB 로 줄여 씀 · 다시 고르면 풀림",
                        "Cut to $cap MB after running out of memory · pick again to lift",
                        "因内存不足降为 $cap MB · 重新选择即可解除",
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        row {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("DB 사용", "Use the database", "使用数据库"), style = RowTitleStyle)
                    // The library's attached entry (L5-4) — chosen in the library.
                    Hint(ui.localDbTitle ?: tr("라이브러리에서 고른 DB", "The one picked in the library", "在资料库中选择的数据库"))
                }
                ZenythSwitch(checked = ui.localProfile.useDatabase, onCheckedChange = onDatabase)
            }
            // yixindb is read whole into the engine's memory, on top of the table (T3).
            val dbBytes = ui.localDbBytes
            if (ui.localProfile.useDatabase && dbBytes != null) {
                val mb = (dbBytes + (1 shl 20) - 1) shr 20
                if (dbBytes >= ui.localDbWarnBytes) {
                    Hint(
                        tr(
                            "${mb} MB · 엔진이 그만큼 메모리를 더 씀",
                            "$mb MB · the engine holds it all in memory",
                            "$mb MB · 引擎会多占这么多内存",
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Hint("$mb MB")
                }
            }
        }
        row {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("서버 엔진 사용", "Use a server engine", "使用服务器引擎"), style = RowTitleStyle)
                    Hint(tr("직접 운영하는 Rapfi 서버 (Tailscale)", "A Rapfi server you run (Tailscale)", "你自己运行的 Rapfi 服务器 (Tailscale)"))
                }
                ZenythSwitch(checked = ui.serverEnabled, onCheckedChange = onServerEnabled)
            }
            if (!ui.localMode) Hint(tr("지금은 서버 엔진을 씁니다", "The server engine is in use", "当前正在使用服务器引擎"))
        }
    }
}

/**
 * Which engine, and where the server is (L5-5: the engine tab's top half). The
 * two are not the same tool — on-device is always there but holds a small
 * hash, the server is the deep one — and choosing is only allowed while nothing
 * is connected: swapping engines mid-session would leave the board talking to
 * the wrong one. The board's «로그» pane starts and stops whichever is chosen.
 */
@Composable
internal fun ServerEngineCard(
    ui: SettingsUiState,
    onLocalMode: (Boolean) -> Unit,
    onEndpoint: (host: String, port: String) -> Unit,
) {
    ConnectedGroup(title = tr("연결할 엔진", "Engine to use", "要连接的引擎")) {
        row {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ZenythFilterChip(
                    selected = ui.localMode,
                    onClick = { onLocalMode(true) },
                    enabled = !ui.connected,
                    label = { Text(tr("기기 내 엔진", "On-device", "本机引擎")) },
                )
                ZenythFilterChip(
                    selected = !ui.localMode,
                    onClick = { onLocalMode(false) },
                    enabled = !ui.connected,
                    label = { Text(tr("서버", "Server", "服务器")) },
                )
            }
            if (ui.connected) {
                Hint(tr("엔진을 끈 뒤 바꿉니다 · 보드 ▸ 로그", "Stop the engine to change it · Board ▸ Log", "关闭引擎后才能更改 · 棋盘 ▸ 日志"))
            }
        }
        row {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                CommitField(
                    value = ui.endpoint.host,
                    onCommit = { host -> onEndpoint(host, ui.endpoint.port.toString()) },
                    keyboard = KeyboardType.Uri,
                    modifier = Modifier.weight(1f),
                    // There is no default address — the server is the user's own.
                    supporting = tr("서버 (Tailscale) · 예: 100.x.y.z", "Server (Tailscale) · e.g. 100.x.y.z", "服务器 (Tailscale) · 例: 100.x.y.z"),
                )
                CommitField(
                    value = ui.endpoint.port.toString(),
                    onCommit = { port -> onEndpoint(ui.endpoint.host, port) },
                    keyboard = KeyboardType.Number,
                    modifier = Modifier.width(96.dp),
                    supporting = tr("포트", "Port", "端口"),
                )
            }
            // The server link is plain TCP (piskvork has no encryption); only
            // Tailscale's WireGuard tunnel protects it. Read from the text alone,
            // no lookup (EndpointReach).
            if (ui.plaintextWarning) {
                Hint(
                    tr(
                        "Tailscale 이나 사설망 주소가 아닌 것 같습니다. 서버 엔진 연결은 암호화되지 않아, 오가는 국면과 명령이 경로 위에서 그대로 보입니다.",
                        "This does not look like a Tailscale or private-network address. The server engine link is not encrypted: positions and commands cross the network in the clear.",
                        "这似乎不是 Tailscale 或局域网地址。服务器引擎连接不加密，局面和命令会以明文在网络上传输。",
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (ui.serverChosen && !ui.endpoint.isConfigured) {
                Hint(tr("주소를 넣어야 서버 엔진을 시작합니다", "The server engine starts once it has an address", "填写地址后才能启动服务器引擎"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun SettingRow(
    spec: SettingSpec,
    state: SettingsUiState,
    onValue: (String) -> Unit,
) {
    val value = spec.read(state.settings)
    val editor = state.editorFor(spec)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, style = RowTitleStyle, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Box(contentAlignment = Alignment.CenterEnd) {
                when (editor) {
                    is SettingEditor.Toggle -> ZenythSwitch(
                        checked = value != "0",
                        onCheckedChange = { onValue(if (it) "1" else "0") },
                    )
                    is SettingEditor.Number -> NumberEditor(value, editor, onValue)
                    is SettingEditor.Choice -> ChoiceEditor(spec.label, value, editor, onValue)
                    is SettingEditor.Text -> Unit // full-width field below
                    is SettingEditor.Fixed -> Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (editor is SettingEditor.Text) {
            CommitField(
                value = value,
                onCommit = onValue,
                keyboard = KeyboardType.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        // What the phone actually uses instead, when it differs: without this
        // the screen would read 8192 MB while the engine ran on 128.
        state.overrideFor(spec)?.let { Hint(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun ResetRow(onReset: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    if (!asking) {
        TextButton(onClick = { asking = true }) { Text(tr("PC 기본값으로 되돌리기", "Back to the PC defaults", "恢复 PC 默认值")) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onReset()
                asking = false
            }) { Text(tr("되돌리기", "Reset", "恢复")) }
            TextButton(onClick = { asking = false }) { Text(tr("취소", "Cancel", "取消")) }
        }
    }
}

@Composable
private fun NumberEditor(
    value: String,
    editor: SettingEditor.Number,
    onValue: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CommitField(
            value = value,
            onCommit = onValue,
            keyboard = KeyboardType.Number,
            modifier = Modifier.width(if (editor.max > 100_000) 132.dp else 96.dp),
            supporting = "${editor.min}~${editor.max}",
        )
        if (editor.unit.isNotEmpty()) {
            Text(
                " ${editor.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A text field that commits on IME action or focus loss, never mid-typing: the
 * spec clamps on write, so committing every keystroke would fight the user
 * (typing "8" into a field with min 1000 would snap to 1000).
 */
@Composable
private fun CommitField(
    value: String,
    onCommit: (String) -> Unit,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.onFocusChanged { focus ->
            if (!focus.isFocused && text != value) onCommit(text)
        },
    )
}

@Composable
private fun ChoiceEditor(
    title: String,
    value: String,
    editor: SettingEditor.Choice,
    onValue: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = editor.options.firstOrNull { it.value.toString() == value }
    OutlinedButton(onClick = { open = true }) {
        Text(current?.label ?: value, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(editor.options) { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.value.toString() == value,
                                onClick = {
                                    onValue(option.value.toString())
                                    open = false
                                },
                            )
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { open = false }) { Text(tr("닫기", "Close", "关闭")) }
            },
        )
    }
}


/** A setting that is on or off: its name, a line under it if it needs one, the switch. */
@Composable
internal fun SwitchRow(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = RowTitleStyle)
            note?.let { Text(it, style = RowNoteStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ZenythSwitch(checked = checked, onCheckedChange = onChange)
    }
}
