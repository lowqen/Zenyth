package dev.gomoku.zenyth.feature.connection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gomoku.zenyth.core.designsystem.component.Button
import dev.gomoku.zenyth.core.designsystem.component.CalmCard
import dev.gomoku.zenyth.core.designsystem.component.Disclosure
import dev.gomoku.zenyth.core.designsystem.component.FlatCard
import dev.gomoku.zenyth.core.designsystem.component.Hint
import dev.gomoku.zenyth.core.designsystem.component.LocalUiPrefs
import dev.gomoku.zenyth.core.designsystem.component.OutlinedButton
import dev.gomoku.zenyth.core.designsystem.component.StatusDot
import dev.gomoku.zenyth.core.designsystem.component.engineWord
import dev.gomoku.zenyth.core.designsystem.component.rememberCalmFling
import dev.gomoku.zenyth.core.designsystem.theme.LocalProMode
import dev.gomoku.zenyth.core.designsystem.theme.MOTION_QUICK
import dev.gomoku.zenyth.core.designsystem.theme.MonoStyle
import dev.gomoku.zenyth.core.designsystem.theme.ZenythTheme
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.ConsoleLine
import dev.gomoku.zenyth.core.model.LinkHealth
import dev.gomoku.zenyth.feature.tools.ToolsScreen

/**
 * The board's «로그» pane (L5-5, Zenyth Web's U2 `EngineLogPane`): the engine's
 * state on one line with start/stop and «해시 비우기», the figures folded away,
 * then the console with the whole height that is left.
 *
 * It is where the engine tab was. That tab was the window opened when something
 * looked wrong, and what looks wrong is always seen on the board — so the pane
 * sits beside the board (the sheet upright, the right column on its side) and
 * the console is read without leaving it.
 *
 * Reading the log is for everyone; writing to the engine is «고급 사용자 모드»:
 * the console's command line, and the engine tools (folded here, «도구»), whose
 * list takes the console's place while it is open.
 */
@Composable
fun EngineLogPane(viewModel: ConnectionViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    EngineLogContent(
        ui = ui,
        modifier = modifier,
        onConnect = viewModel::onConnect,
        onDisconnect = viewModel::onDisconnect,
        onHashClear = viewModel::onHashClear,
        onRetryNow = viewModel::onRetryNow,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::onSend,
        onClear = viewModel::onClearConsole,
        tools = { mod -> ToolsScreen(mod) },
    )
}

/** [EngineLogPane] as it looks — plain values and callbacks, for the screenshot tests. */
@Composable
fun EngineLogContent(
    ui: ConnectionUiState,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onHashClear: () -> Unit = {},
    onRetryNow: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onClear: () -> Unit = {},
    tools: @Composable (Modifier) -> Unit = {},
) {
    val pro = LocalProMode.current
    val prefs = LocalUiPrefs.current
    var toolsOpen by rememberSaveable { mutableStateOf(prefs?.isOpen(TOOLS_FOLD, false) ?: false) }
    val showTools = pro && toolsOpen
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EngineStatusCard(ui, onConnect, onDisconnect, onHashClear, onRetryNow)
        if (pro) {
            ToolsFoldRow(open = toolsOpen) {
                toolsOpen = !toolsOpen
                prefs?.setOpen(TOOLS_FOLD, toolsOpen)
            }
        }
        if (showTools) {
            tools(Modifier.weight(1f).fillMaxWidth())
        } else {
            ConsolePane(ui, onClear, Modifier.weight(1f))
            // Sending a raw line to the engine is a dangerous write (design §6.1).
            if (pro) CommandLine(ui, onDraftChange, onSend)
        }
    }
}

/** The state line, where the engine is, what went wrong with the link, and the folded figures. */
@Composable
private fun EngineStatusCard(
    ui: ConnectionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onHashClear: () -> Unit,
    onRetryNow: () -> Unit,
) {
    CalmCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (word, color) = engineWord(ui.state)
            StatusDot(color)
            // Spoken by the top bar's engine chip already — the same change is not read
            // twice. Weighted, so on a narrow phone the word gives way, not the buttons.
            Text(
                word,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A one-off, so beside the engine rather than in a list of settings;
            // only while it waits — mid-search it would empty the table being read.
            TextButton(onClick = onHashClear, enabled = ui.canClearHash) {
                Text(tr("해시 비우기", "Clear hash", "清空哈希"), maxLines = 1)
            }
            if (ui.canConnect) {
                Button(onClick = onConnect, enabled = ui.canStart) { Text(tr("엔진 시작", "Start engine", "启动引擎"), maxLines = 1) }
            } else {
                OutlinedButton(onClick = onDisconnect) { Text(tr("엔진 끄기", "Stop engine", "关闭引擎"), maxLines = 1) }
            }
        }
        Hint(
            when {
                ui.needsAddress -> tr("서버 주소가 없습니다 — 설정 ▸ 엔진", "No server address — Settings ▸ Engine", "没有服务器地址 — 设置 ▸ 引擎")
                ui.serverChosen -> tr(
                    "서버 ${ui.endpoint.host}:${ui.endpoint.port} · 설정 ▸ 엔진에서 바꿉니다",
                    "Server ${ui.endpoint.host}:${ui.endpoint.port} · change it in Settings ▸ Engine",
                    "服务器 ${ui.endpoint.host}:${ui.endpoint.port} · 在 设置 ▸ 引擎 中更改",
                )
                else -> tr("이 기기 안에서 돌아갑니다 · 인터넷 불필요", "Runs on this device · no internet needed", "在本设备运行 · 无需联网")
            },
            color = if (ui.needsAddress) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        LinkHealthRow(ui.health, onRetryNow)
        Disclosure("engine-log-detail", tr("세부 수치", "Details", "详细数据"), summary = detailSummary(ui)) {
            DetailRows(ui)
        }
    }
}

/** The fold's closed line: where the engine runs and on how many threads. */
private fun detailSummary(ui: ConnectionUiState): String =
    if (ui.serverChosen) {
        tr("서버", "Server", "服务器")
    } else {
        val threads = ui.thermalThreads?.coerceAtMost(ui.profile.threads) ?: ui.profile.threads
        tr("기기 안 · ${threads}스레드", "On device · $threads threads", "本机 · ${threads} 线程")
    }

@Composable
private fun DetailRows(ui: ConnectionUiState) {
    val server = ui.serverChosen
    val serverSide = tr("서버 설정", "The server's", "服务器设置")
    DetailRow(
        tr("연결", "Link", "连接"),
        if (server) "${ui.endpoint.host}:${ui.endpoint.port}" else tr("기기 안 엔진", "On-device engine", "本机引擎"),
    )
    DetailRow(
        tr("스레드", "Threads", "线程"),
        when {
            server -> serverSide
            ui.thermalThreads != null && ui.thermalThreads < ui.profile.threads ->
                tr("${ui.thermalThreads} (발열 · 절전으로, 설정 ${ui.profile.threads})", "${ui.thermalThreads} (heat or battery saver; set to ${ui.profile.threads})", "${ui.thermalThreads} (因发热 · 省电，设置为 ${ui.profile.threads})")
            else -> "${ui.profile.threads}"
        },
    )
    DetailRow(tr("해시", "Hash", "哈希"), if (server) serverSide else "${ui.profile.hashMb} MB")
    DetailRow(
        "DB",
        when {
            server -> serverSide
            !ui.profile.useDatabase -> tr("끔", "Off", "关闭")
            else -> ui.dbTitle ?: tr("켬", "On", "开启")
        },
    )
    val caps = ui.capabilities
    if (caps.maxThreadNum != null || caps.maxHashSizeMb != null) {
        DetailRow(
            tr("엔진 한도", "Engine limits", "引擎上限"),
            listOfNotNull(
                caps.maxThreadNum?.let { tr("스레드 ≤ $it", "threads ≤ $it", "线程 ≤ $it") },
                caps.maxHashSizeMb?.let { tr("해시 ≤ $it MB", "hash ≤ $it MB", "哈希 ≤ $it MB") },
            ).joinToString(" · "),
        )
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            key,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** «도구 ▸» — the engine tools, expert only; open, their list takes the console's place. */
@Composable
private fun ToolsFoldRow(open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(MOTION_QUICK), label = "toolsChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("도구", "Tools", "工具"), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(
            if (open) tr("콘솔로 돌아가려면 접기", "Fold to see the console", "收起即可回到控制台")
            else tr("해시 · 차단 · 국면 슬롯 · 스크립트", "Hash, blocks, slots, scripts", "哈希 · 排除 · 局面存储 · 脚本"),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = if (open) tr("접기", "Collapse", "收起") else tr("펼치기", "Expand", "展开"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(turn),
        )
    }
}

/** The console with its header; settings.txt line 13 ("show log") hides it, line 37 scales it. */
@Composable
private fun ConsolePane(ui: ConnectionUiState, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (ui.showLog) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("piskvork 콘솔", "piskvork console", "piskvork 控制台"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete, contentDescription = tr("콘솔 지우기", "Clear the console", "清空控制台"))
                }
            }
            Console(lines = ui.console, scale = ui.logScale, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Hint(tr("로그 표시가 꺼져 있습니다", "The log is turned off", "日志显示已关闭"))
        }
    }
}

/** The line typed into the engine as it is — a command is written while its answer is read. */
@Composable
private fun CommandLine(ui: ConnectionUiState, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = ui.commandDraft,
            onValueChange = onDraftChange,
            label = { Text(tr("명령 (예: ABOUT, START 15, TURN 7,7)", "Command (e.g. ABOUT, START 15, TURN 7,7)", "命令 (例: ABOUT, START 15, TURN 7,7)")) },
            singleLine = true,
            textStyle = MonoStyle,
            enabled = ui.state.isLive,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = ui.canSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("전송", "Send", "发送"))
        }
    }
}

/**
 * What happened to the link and what is being done about it. Hidden while the
 * link is healthy — a status line that is always there is a status line nobody
 * reads — and it says the attempt number and the countdown, because "재연결
 * 중…" with no numbers is indistinguishable from a hang.
 */
@Composable
private fun LinkHealthRow(health: LinkHealth, onRetryNow: () -> Unit) {
    if (health.idle) return
    Surface(
        color = if (health.reconnecting) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        health.reconnecting && health.retryInSeconds > 0 ->
                            tr("연결이 끊겼습니다 — ${health.retryInSeconds}초 후 재시도 ", "Connection lost — retrying in ${health.retryInSeconds}s", "连接已断开，${health.retryInSeconds} 秒后重试") +
                                tr("(${health.attempt}회째)", "(attempt ${health.attempt})", "(第 ${health.attempt} 次)")
                        health.reconnecting -> tr("재연결하는 중… (${health.attempt}회째)", "Reconnecting… (attempt ${health.attempt})", "正在重新连接… (第 ${health.attempt} 次)")
                        else -> tr("연결이 끊겼습니다", "Connection lost", "连接已断开")
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                health.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (health.recovered > 0) {
                    Text(
                        tr("이 세션에서 ${health.recovered}회 복구됨", "Recovered ${health.recovered} times this session", "本次会话已恢复 ${health.recovered} 次"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (health.reconnecting) {
                TextButton(onClick = onRetryNow) { Text(tr("지금 재시도", "Retry now", "立即重试")) }
            }
        }
    }
}

@Composable
private fun Console(lines: List<ConsoleLine>, scale: Float, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    // The calm card face, like every other card: on the page colour it had no
    // edge at all, and the console read as loose text on the screen.
    FlatCard(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            flingBehavior = rememberCalmFling(),
        ) {
            items(lines) { line -> ConsoleRow(line, scale) }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleLine, scale: Float) {
    val color = when {
        line.outbound -> MaterialTheme.colorScheme.secondary
        line.text.startsWith("ERROR", ignoreCase = true) -> MaterialTheme.colorScheme.error
        isCoordinate(line.text) -> ZenythTheme.colors.positive
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = if (line.outbound) "» " else "  "
    Text(
        text = prefix + line.text,
        style = MonoStyle.copy(
            fontSize = MonoStyle.fontSize * scale,
            lineHeight = MonoStyle.lineHeight * scale,
        ),
        color = color,
    )
}

/** The tools fold's remembered state (`UiPrefs.isOpen`). */
private const val TOOLS_FOLD = "engine-log-tools"

/**
 * Hoisted out of [isCoordinate]: `Regex(…)` compiles when it is constructed, and
 * this is asked about every console line on every recomposition — a search fills
 * the console faster than that pattern is worth rebuilding.
 */
private val coordinateLine = Regex("""^\s*\d+\s*,\s*\d+\s*$""")

private fun isCoordinate(text: String): Boolean = coordinateLine.matches(text)
