package com.txdost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.ManagedProcess
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.ui.theme.MonospaceStyle
import com.txdost.viewmodel.ChatViewModel
import com.txdost.viewmodel.DebugLogEntry
import com.txdost.viewmodel.DebugLogType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class PMTab { BOTS, PROCESSES, DEBUG_CONSOLE }

@Composable
fun ProcessManagerScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val c = Apple.colors
    val uiState by viewModel.uiState.collectAsState()
    val processes = uiState.activeProcesses
    val debugLogs = uiState.debugLogs
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(PMTab.BOTS) }
    var showStopAllDialog by remember { mutableStateOf(false) }
    var selectedLogProcess by remember { mutableStateOf<ManagedProcess?>(null) }

    if (selectedLogProcess != null) {
        LiveLogScreen(process = selectedLogProcess!!, viewModel = viewModel, onClose = { selectedLogProcess = null })
        return
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(
            title = "Processes",
            subtitle = if (processes.isEmpty()) "No active processes" else "${processes.count { it.isRunning }} running",
            onBack = onBack,
            actions = {
                when (activeTab) {
                    PMTab.DEBUG_CONSOLE -> if (debugLogs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearDebugLogs() }) {
                            Icon(Icons.Rounded.Delete, "Clear", tint = c.red)
                        }
                    }
                    else -> if (processes.isNotEmpty()) {
                        IconButton(onClick = { showStopAllDialog = true }) {
                            Icon(Icons.Rounded.Stop, "Stop all", tint = c.red)
                        }
                    }
                }
            }
        )

        AppleSegmentedControl(
            options = listOf("Bots", "Processes", "Debug (${debugLogs.size})"),
            selectedIndex = activeTab.ordinal,
            onSelect = { activeTab = PMTab.values()[it] },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        when (activeTab) {
            PMTab.BOTS -> BotManagerTab(processes = processes, viewModel = viewModel, onViewLog = { selectedLogProcess = it })
            PMTab.PROCESSES -> ProcessesTab(
                processes = processes,
                onStop = { viewModel.stopProcess(it) },
                onViewLog = { selectedLogProcess = it },
                onToggleAutoRestart = { viewModel.toggleAutoRestart(it) }
            )
            PMTab.DEBUG_CONSOLE -> DebugConsoleTab(logs = debugLogs)
        }
    }

    if (showStopAllDialog) {
        AppleAlertDialog(
            title = "Stop all processes?",
            onDismiss = { showStopAllDialog = false },
            confirmText = "Stop all",
            destructive = true,
            onConfirm = { viewModel.stopAllProcesses(); showStopAllDialog = false }
        ) { Text("${processes.size} process(es) will be stopped.", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel) }
    }
}

// ── Empty state ───────────────────────────────────────────────
@Composable
private fun EmptyState(icon: String, title: String, subtitle: String) {
    val c = Apple.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(icon, fontSize = 34.sp)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.label)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
        }
    }
}

// ── Processes tab ─────────────────────────────────────────────
@Composable
fun ProcessesTab(
    processes: List<ManagedProcess>,
    onStop: (String) -> Unit,
    onViewLog: (ManagedProcess) -> Unit,
    onToggleAutoRestart: (String) -> Unit
) {
    val c = Apple.colors
    if (processes.isEmpty()) {
        EmptyState("🗂", "No active processes", "Start a bot from chat or the Bots tab")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(processes, key = { it.id }) { proc ->
                ProcessCard(
                    process = proc,
                    onStop = { onStop(proc.id) },
                    onViewLog = { onViewLog(proc) },
                    onToggleAutoRestart = { onToggleAutoRestart(proc.id) }
                )
            }
        }
    }
}

// ── Debug console tab ─────────────────────────────────────────
@Composable
fun DebugConsoleTab(logs: List<DebugLogEntry>) {
    val listState = rememberLazyListState()
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    if (logs.isEmpty()) {
        EmptyState("🖥", "Debug console", "No AI/Termux activity yet")
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(logs) { index, log ->
            DebugLogCard(log = log, isExpanded = expandedIndex == index, onClick = { expandedIndex = if (expandedIndex == index) null else index })
        }
    }
}

@Composable
fun DebugLogCard(log: DebugLogEntry, isExpanded: Boolean, onClick: () -> Unit) {
    val c = Apple.colors
    val col = when (log.type) {
        DebugLogType.COMMAND -> c.blue
        DebugLogType.OUTPUT -> c.green
        DebugLogType.AI_SEND -> c.indigo
        DebugLogType.AI_RECV -> c.teal
        DebugLogType.ERROR -> c.red
        DebugLogType.INFO -> c.orange
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondaryBg)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    log.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.label,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(log.time, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
            }
            if (log.detail.isNotEmpty()) {
                Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
            }
        }
        if (isExpanded && log.detail.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(10.dp)) {
                Text(log.detail, style = MonospaceStyle, color = c.secondaryLabel)
            }
        }
    }
}

// ── Process card ──────────────────────────────────────────────
@Composable
fun ProcessCard(
    process: ManagedProcess,
    onStop: () -> Unit,
    onViewLog: () -> Unit,
    onToggleAutoRestart: () -> Unit
) {
    val c = Apple.colors
    val runningTime = remember(process.startedAt) {
        val diff = System.currentTimeMillis() - process.startedAt
        val mins = diff / 60000; val hrs = mins / 60; val days = hrs / 24
        when {
            days > 0 -> "${days}d ${hrs % 24}h"
            hrs > 0 -> "${hrs}h ${mins % 60}m"
            else -> "${mins}m"
        }
    }

    AppleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (process.isRunning) c.green else c.red))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(process.name, style = MaterialTheme.typography.titleMedium, color = c.label)
                Text(
                    if (process.isRunning) "Running · $runningTime" else "Stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (process.isRunning) c.green else c.secondaryLabel
                )
            }
            if (process.pid > 0) Text("PID ${process.pid}", style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "$ ${process.command.take(80)}${if (process.command.length > 80) "…" else ""}",
            style = MonospaceStyle.copy(fontSize = 12.sp),
            color = c.secondaryLabel,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        AppleSwitchRow(
            title = "Auto-restart",
            subtitle = "Restart automatically on crash",
            checked = process.autoRestart,
            onCheckedChange = { onToggleAutoRestart() },
            showDivider = false
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppleButton("Live log", onViewLog, Modifier.weight(1f), style = AppleButtonStyle.Tinted, icon = Icons.Rounded.Article)
            AppleButton("Stop", onStop, Modifier.weight(1f), style = AppleButtonStyle.Destructive, icon = Icons.Rounded.Stop)
        }
    }
}

// ── Bot manager tab ───────────────────────────────────────────
@Composable
fun BotManagerTab(
    processes: List<ManagedProcess>,
    viewModel: ChatViewModel,
    onViewLog: (ManagedProcess) -> Unit
) {
    val c = Apple.colors
    val scope = rememberCoroutineScope()
    var showStartDialog by remember { mutableStateOf(false) }
    var newBotName by remember { mutableStateOf("") }
    var newBotCmd by remember { mutableStateOf("") }
    var newBotLog by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotStatCard("Running", processes.count { it.isRunning }.toString(), c.green, Modifier.weight(1f))
                BotStatCard("Stopped", processes.count { !it.isRunning }.toString(), c.red, Modifier.weight(1f))
                BotStatCard("Auto", processes.count { it.autoRestart }.toString(), c.orange, Modifier.weight(1f))
            }
        }
        item { AppleButton("Start new bot", { showStartDialog = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) }
        if (processes.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🤖", fontSize = 34.sp)
                        Text("No bots running", style = MaterialTheme.typography.headlineSmall, color = c.label)
                        Text("Start one from chat or the button above", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
                    }
                }
            }
        } else {
            items(processes, key = { it.id }) { proc ->
                BotCard(
                    process = proc,
                    onStop = { viewModel.stopProcess(proc.id) },
                    onToggleAutoRestart = { viewModel.toggleAutoRestart(proc.id) },
                    onViewLog = { onViewLog(proc) },
                    onRestart = {
                        viewModel.stopProcess(proc.id)
                        scope.launch {
                            delay(1500)
                            viewModel.sendMessage("[BG:${proc.name}|${proc.command}|${proc.logFile}]")
                        }
                    }
                )
            }
        }
    }

    if (showStartDialog) {
        AppleAlertDialog(
            title = "Start new bot",
            onDismiss = { showStartDialog = false; newBotName = ""; newBotCmd = ""; newBotLog = "" },
            confirmText = "Start",
            onConfirm = {
                if (newBotName.isNotBlank() && newBotCmd.isNotBlank()) {
                    val log = newBotLog.ifBlank { "~/txdost/${newBotName.lowercase().replace(' ', '_')}.log" }
                    viewModel.sendMessage("[BG:$newBotName|$newBotCmd|$log]")
                }
                showStartDialog = false; newBotName = ""; newBotCmd = ""; newBotLog = ""
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppleTextField(newBotName, { newBotName = it }, placeholder = "telegram-bot", label = "Name")
                AppleTextField(newBotCmd, { newBotCmd = it }, placeholder = "python bot.py", label = "Command")
                AppleTextField(newBotLog, { newBotLog = it }, placeholder = "~/txdost/bot.log", label = "Log file (optional)")
            }
        }
    }
}

@Composable
fun BotStatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val c = Apple.colors
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.secondaryBg).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
    }
}

@Composable
fun BotCard(
    process: ManagedProcess,
    onStop: () -> Unit,
    onToggleAutoRestart: () -> Unit,
    onViewLog: () -> Unit,
    onRestart: () -> Unit
) {
    val c = Apple.colors
    val uptime = remember(process.startedAt) {
        val diff = System.currentTimeMillis() - process.startedAt
        val h = diff / 3600000; val m = (diff % 3600000) / 60000; val s = (diff % 60000) / 1000
        when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    AppleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (process.isRunning) c.green else c.red))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(process.name, style = MaterialTheme.typography.titleMedium, color = c.label)
                Text(
                    if (process.isRunning) "Running · $uptime" else "Stopped · ${process.restartCount} restarts",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (process.isRunning) c.green else c.secondaryLabel
                )
            }
            if (process.pid > 0) Text("PID ${process.pid}", style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "$ ${process.command}",
            style = MonospaceStyle.copy(fontSize = 12.sp),
            color = c.secondaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
        AppleSwitchRow(
            title = "Auto-restart",
            subtitle = if (process.restartCount > 0) "${process.restartCount} restarts so far" else "Restart automatically on crash",
            checked = process.autoRestart,
            onCheckedChange = { onToggleAutoRestart() },
            showDivider = false
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppleButton("Log", onViewLog, Modifier.weight(1f), style = AppleButtonStyle.Tinted, icon = Icons.Rounded.Article)
            if (process.isRunning) {
                AppleButton("Stop", onStop, Modifier.weight(1f), style = AppleButtonStyle.Destructive, icon = Icons.Rounded.Stop)
                AppleButton("Restart", onRestart, Modifier.weight(1f), style = AppleButtonStyle.Gray, icon = Icons.Rounded.Refresh)
            } else {
                AppleButton("Start", onRestart, Modifier.weight(1f), style = AppleButtonStyle.Filled, icon = Icons.Rounded.PlayArrow)
            }
        }
    }
}

// ── Live log ──────────────────────────────────────────────────
@Composable
fun LiveLogScreen(
    process: ManagedProcess,
    viewModel: ChatViewModel,
    onClose: () -> Unit
) {
    val c = Apple.colors
    var logContent by remember { mutableStateOf("Loading…") }
    var lastFileSize by remember { mutableStateOf(0L) }
    var isLive by remember { mutableStateOf(true) }
    var lineCount by remember { mutableStateOf(50) }
    val listState = rememberLazyListState()
    val logLines = remember(logContent) { logContent.lines() }

    LaunchedEffect(isLive, lineCount) {
        while (isActive && isLive) {
            val result = viewModel.fetchLiveLog(process.logFile, lineCount, lastFileSize)
            if (result.isNotEmpty()) {
                logContent = if (lastFileSize > 0) logContent + result else result
                lastFileSize = viewModel.getLogFileSize(process.logFile)
                val count = logContent.lines().size
                if (count > 1) listState.animateScrollToItem(count - 1)
            }
            delay(2000)
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            AppleTopBar(
                title = process.name,
                subtitle = if (isLive) "Live" else "Paused",
                onBack = onClose,
                actions = {
                    AppleButton(if (isLive) "Pause" else "Resume", {
                        isLive = !isLive; lastFileSize = 0L
                    }, style = AppleButtonStyle.Tinted)
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(30, 50, 100, 200).forEach { n ->
                    Text(
                        "$n",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lineCount == n) Color.White else c.blue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (lineCount == n) c.blue else c.fill.copy(alpha = 0.4f))
                            .clickable { lineCount = n; lastFileSize = 0L; logContent = "Loading…" }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.red,
                    modifier = Modifier.clickable {
                        viewModel.clearLogFile(process.logFile); logContent = "(log cleared)"; lastFileSize = 0L
                    }
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                itemsIndexed(logLines) { _, line ->
                    val lineColor = when {
                        line.contains("ERROR", true) || line.contains("Traceback", true) || line.contains("CRITICAL", true) -> c.red
                        line.contains("WARNING", true) || line.contains("WARN", true) -> c.orange
                        line.contains("TXDOST WATCHDOG", true) -> c.teal
                        line.startsWith("[") -> c.secondaryLabel
                        else -> c.label
                    }
                    Text(line, style = MonospaceStyle, color = lineColor, modifier = Modifier.fillMaxWidth())
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        FloatingActionButton(
            onClick = { lastFileSize = 0L },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = c.blue,
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Scroll to bottom")
        }
    }
}
