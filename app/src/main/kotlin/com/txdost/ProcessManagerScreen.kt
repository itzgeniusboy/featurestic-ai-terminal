package com.txdost.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.ManagedProcess
import com.txdost.viewmodel.ChatViewModel
import com.txdost.viewmodel.DebugLogEntry
import com.txdost.viewmodel.DebugLogType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PBg      = Color(0xFF060D06)
private val PGreen   = Color(0xFF00FF41)
private val PGreenD  = Color(0xFF00C832)
private val PCyan    = Color(0xFF00E5FF)
private val PYellow  = Color(0xFFFFD700)
private val PRed     = Color(0xFFFF1744)
private val PBorder  = Color(0xFF003300)
private val PCardBg  = Color(0xFF0A120A)
private val PBlue    = Color(0xFF4FC3F7)

// ── Tabs ──────────────────────────────────────────────────────
private enum class PMTab { BOTS, PROCESSES, DEBUG_CONSOLE }

// ── Main Screen ───────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessManagerScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val processes = uiState.activeProcesses
    val debugLogs = uiState.debugLogs
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(PMTab.BOTS) }
    var showStopAllDialog by remember { mutableStateOf(false) }
    var selectedLogProcess by remember { mutableStateOf<ManagedProcess?>(null) }

    if (selectedLogProcess != null) {
        LiveLogScreen(
            process = selectedLogProcess!!,
            viewModel = viewModel,
            onClose = { selectedLogProcess = null }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(PBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── TopBar ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, PBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = PGreen, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("[ MULTI-BOT MANAGER ]", color = PGreen, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp)
                        Text(
                            if (processes.isEmpty()) "No active processes"
                            else "${processes.size} process(es) running",
                            color = PCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    if (activeTab == PMTab.PROCESSES) {
                        IconButton(onClick = { viewModel.checkProcessStatuses() },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                                tint = PGreenD, modifier = Modifier.size(18.dp))
                        }
                        if (processes.isNotEmpty()) {
                            IconButton(onClick = { showStopAllDialog = true },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop All",
                                    tint = PRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        // Debug console actions
                        if (debugLogs.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearDebugLogs() },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear",
                                    tint = PRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // ── Tabs ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000D00))
                    .border(BorderStroke(1.dp, PBorder))
                    .padding(horizontal = 12.dp, vertical = 0.dp)
            ) {
                PMTab.values().forEach { tab ->
                    val isActive = activeTab == tab
                    val label = when (tab) {
                        PMTab.BOTS -> "BOTS (${processes.size})"
                        PMTab.PROCESSES -> "ALL PROCS"
                        PMTab.DEBUG_CONSOLE -> "DEBUG (${debugLogs.size})"
                    }
                    val tabColor = when (tab) {
                        PMTab.BOTS -> PGreen
                        PMTab.PROCESSES -> PCyan
                        PMTab.DEBUG_CONSOLE -> PYellow
                    }
                    Box(
                        modifier = Modifier
                            .clickable { activeTab = tab }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .then(
                                if (isActive) Modifier.border(
                                    BorderStroke(0.dp, Color.Transparent)
                                ) else Modifier
                            )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = label,
                                color = if (isActive) tabColor else tabColor.copy(alpha = 0.35f),
                                fontSize = 10.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            if (isActive) {
                                Spacer(Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(60.dp)
                                        .height(2.dp)
                                        .background(tabColor, RoundedCornerShape(1.dp))
                                )
                            }
                        }
                    }
                }
            }

            // ── Content ───────────────────────────────────────
            when (activeTab) {
                PMTab.BOTS -> BotManagerTab(
                    processes = processes,
                    viewModel = viewModel,
                    onViewLog = { selectedLogProcess = it }
                )
                PMTab.PROCESSES -> ProcessesTab(
                    processes = processes,
                    onStop = { viewModel.stopProcess(it) },
                    onViewLog = { selectedLogProcess = it },
                    onToggleAutoRestart = { viewModel.toggleAutoRestart(it) }
                )
                PMTab.DEBUG_CONSOLE -> DebugConsoleTab(logs = debugLogs)
            }
        }
    }

    if (showStopAllDialog) {
        AlertDialog(
            onDismissRequest = { showStopAllDialog = false },
            containerColor = Color(0xFF0A0A0A),
            titleContentColor = PRed,
            title = { Text("[ STOP ALL? ]", fontFamily = FontFamily.Monospace) },
            text = {
                Text("Saare ${processes.size} process(es) band ho jayenge.",
                    color = PGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.stopAllProcesses()
                    showStopAllDialog = false
                }) { Text("[STOP ALL]", color = PRed, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { showStopAllDialog = false }) {
                    Text("[CANCEL]", color = PGreenD, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// ── Processes Tab ─────────────────────────────────────────────
@Composable
fun ProcessesTab(
    processes: List<ManagedProcess>,
    onStop: (String) -> Unit,
    onViewLog: (ManagedProcess) -> Unit,
    onToggleAutoRestart: (String) -> Unit
) {
    if (processes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("[ NO ACTIVE PROCESSES ]", color = PGreenD,
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("Koi bot/script background mein nahi chal raha",
                    color = PGreenD.copy(alpha = 0.4f), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("Chat mein bolo: \"telegram bot start karo\"",
                    color = PCyan.copy(alpha = 0.5f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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

// ── Debug Console Tab ─────────────────────────────────────────
@Composable
fun DebugConsoleTab(logs: List<DebugLogEntry>) {
    val listState = rememberLazyListState()
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    // Auto scroll to bottom when new log arrives
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("[ DEBUG CONSOLE ]", color = PYellow.copy(alpha = 0.4f),
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("AI aur Termux ke beech koi activity abhi nahi hui",
                    color = PGreenD.copy(alpha = 0.3f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("Chat mein kuch command do — yahan log dikhega",
                    color = PCyan.copy(alpha = 0.3f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        itemsIndexed(logs) { index, log ->
            DebugLogCard(
                log = log,
                isExpanded = expandedIndex == index,
                onClick = { expandedIndex = if (expandedIndex == index) null else index }
            )
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

// ── Debug Log Card ────────────────────────────────────────────
@Composable
fun DebugLogCard(log: DebugLogEntry, isExpanded: Boolean, onClick: () -> Unit) {
    val (icon, color) = when (log.type) {
        DebugLogType.COMMAND   -> "▶" to PGreen
        DebugLogType.OUTPUT    -> "✓" to PGreenD
        DebugLogType.ERROR     -> "✗" to PRed
        DebugLogType.AI_SEND   -> "→" to PBlue
        DebugLogType.AI_RECV   -> "←" to PCyan
        DebugLogType.INFO      -> "ℹ" to PYellow
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isExpanded) PCardBg else Color(0xFF060E06),
                RoundedCornerShape(4.dp)
            )
            .border(
                BorderStroke(1.dp,
                    if (isExpanded) color.copy(alpha = 0.5f) else PBorder
                ),
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(log.time, color = color.copy(alpha = 0.5f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                text = log.title,
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (log.detail.isNotEmpty()) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = color.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Expanded detail
        if (isExpanded && log.detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF020702), RoundedCornerShape(3.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = log.detail,
                    color = color.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ── Process Card ──────────────────────────────────────────────
@Composable
fun ProcessCard(
    process: ManagedProcess,
    onStop: () -> Unit,
    onViewLog: () -> Unit,
    onToggleAutoRestart: () -> Unit
) {
    val runningTime = remember(process.startedAt) {
        val diff = System.currentTimeMillis() - process.startedAt
        val mins = diff / 60000
        val hrs  = mins / 60
        val days = hrs / 24
        when {
            days > 0 -> "${days}d ${hrs % 24}h"
            hrs > 0  -> "${hrs}h ${mins % 60}m"
            else     -> "${mins}m"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PCardBg, RoundedCornerShape(8.dp))
            .border(
                BorderStroke(1.dp,
                    if (process.isRunning) PGreen.copy(alpha = 0.5f)
                    else PRed.copy(alpha = 0.4f)
                ),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Blinking status dot
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        if (process.isRunning) PGreen else PRed,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(process.name, color = PCyan, fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (process.isRunning) "● Running: $runningTime" else "● Stopped",
                        color = if (process.isRunning) PGreenD else PRed,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    if (process.pid > 0) {
                        Text("PID:${process.pid}", color = PYellow.copy(alpha = 0.6f),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (process.restartCount > 0) {
                        Text("↺${process.restartCount}", color = PYellow,
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Command display
        Text(
            text = "$ ${process.command.take(60)}${if (process.command.length > 60) "..." else ""}",
            color = PGreen.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF000800), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )

        Spacer(Modifier.height(4.dp))

        // Log path
        Text(
            "📄 ${process.logFile}",
            color = PYellow.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))

        // Auto-restart toggle row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Auto-restart:", color = PGreenD.copy(alpha = 0.7f),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = process.autoRestart,
                onCheckedChange = { onToggleAutoRestart() },
                modifier = Modifier.height(20.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PGreen,
                    checkedTrackColor = PGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = PRed.copy(alpha = 0.6f),
                    uncheckedTrackColor = PRed.copy(alpha = 0.2f)
                )
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (process.autoRestart) "ON — crash pe restart hoga" else "OFF",
                color = if (process.autoRestart) PGreenD else PRed.copy(alpha = 0.5f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(10.dp))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Live Log button
            OutlinedButton(
                onClick = onViewLog,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(1.dp, PCyan),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(vertical = 7.dp)
            ) {
                Icon(Icons.Default.Article, contentDescription = null,
                    tint = PCyan, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("[LIVE LOG]", color = PCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }

            // Stop button
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(1.dp, PRed),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(vertical = 7.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null,
                    tint = PRed, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("[STOP]", color = PRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}


// ── Bot Manager Tab ───────────────────────────────────────────
@Composable
fun BotManagerTab(
    processes: List<com.txdost.ManagedProcess>,
    viewModel: ChatViewModel,
    onViewLog: (com.txdost.ManagedProcess) -> Unit
) {
    val scope = rememberCoroutineScope()
    var showStartDialog by remember { mutableStateOf(false) }
    var newBotName by remember { mutableStateOf("") }
    var newBotCmd  by remember { mutableStateOf("") }
    var newBotLog  by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header stats ──────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BotStatCard("RUNNING",
                    processes.count { it.isRunning }.toString(), PGreen,
                    Modifier.weight(1f))
                BotStatCard("STOPPED",
                    processes.count { !it.isRunning }.toString(), PRed,
                    Modifier.weight(1f))
                BotStatCard("AUTO-RST",
                    processes.count { it.autoRestart }.toString(), PYellow,
                    Modifier.weight(1f))
            }
        }

        // ── Start new bot button ──────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PGreen.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .border(BorderStroke(1.dp, PGreen.copy(alpha = 0.3f)), RoundedCornerShape(6.dp))
                    .clickable { showStartDialog = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null,
                        tint = PGreen, modifier = Modifier.size(16.dp))
                    Text("START NEW BOT / PROCESS", color = PGreen,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }

        if (processes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🤖", fontSize = 32.sp)
                        Text("No bots running", color = PGreenD.copy(alpha = 0.4f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Start a bot from chat or use button above",
                            color = PGreenD.copy(alpha = 0.3f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        } else {
            items(processes) { proc ->
                BotCard(
                    process = proc,
                    onStop  = { viewModel.stopProcess(proc.id) },
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

        item { Spacer(Modifier.height(80.dp)) }
    }

    // ── Start Bot Dialog ──────────────────────────────────────
    if (showStartDialog) {
        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            containerColor = Color(0xFF0A120A),
            title = {
                Text("START NEW BOT", color = PGreen,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotDialogField("Bot Name", newBotName, "Telegram Bot") { newBotName = it }
                    BotDialogField("Command", newBotCmd, "python3 ~/bot.py") { newBotCmd = it }
                    BotDialogField("Log File", newBotLog, "~/bot.log") { newBotLog = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newBotName.isNotBlank() && newBotCmd.isNotBlank()) {
                        val log = newBotLog.ifBlank { "~/${newBotName.replace(" ", "_").lowercase()}.log" }
                        viewModel.sendMessage("[BG:$newBotName|$newBotCmd|$log]")
                        showStartDialog = false
                        newBotName = ""; newBotCmd = ""; newBotLog = ""
                    }
                }) {
                    Text("START", color = PGreen, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStartDialog = false
                    newBotName = ""; newBotCmd = ""; newBotLog = ""
                }) {
                    Text("CANCEL", color = PGreenD, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
fun BotStatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(PCardBg, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 22.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, color = color.copy(alpha = 0.6f), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

@Composable
fun BotCard(
    process: com.txdost.ManagedProcess,
    onStop: () -> Unit,
    onToggleAutoRestart: () -> Unit,
    onViewLog: () -> Unit,
    onRestart: () -> Unit
) {
    val uptime = remember(process.startedAt) {
        val diff = System.currentTimeMillis() - process.startedAt
        val h = diff / 3600000
        val m = (diff % 3600000) / 60000
        val s = (diff % 60000) / 1000
        when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PCardBg, RoundedCornerShape(8.dp))
            .border(
                BorderStroke(1.5.dp, if (process.isRunning) PGreen.copy(alpha = 0.4f)
                else PRed.copy(alpha = 0.3f)),
                RoundedCornerShape(8.dp)
            )
    ) {
        // ── Header ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF001400), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot — pulsing when running
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (process.isRunning) PGreen else PRed, RoundedCornerShape(50))
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(process.name, color = PGreen, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    if (process.isRunning) "RUNNING • ${uptime}"
                    else "STOPPED • ${process.restartCount} restarts",
                    color = if (process.isRunning) PGreenD else PRed.copy(alpha = 0.7f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
            }
            if (process.pid > 0) {
                Text("PID:${process.pid}", color = PCyan.copy(alpha = 0.5f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ── Command preview ───────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "$ ${process.command}",
                color = PGreenD.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ── Auto-restart toggle ───────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable { onToggleAutoRestart() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = process.autoRestart,
                onCheckedChange = { onToggleAutoRestart() },
                modifier = Modifier.size(36.dp, 20.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PGreen,
                    checkedTrackColor = PGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = PGreenD.copy(alpha = 0.4f),
                    uncheckedTrackColor = PBorder
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("Auto-restart on crash",
                color = if (process.autoRestart) PGreen else PGreenD.copy(alpha = 0.4f),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            if (process.restartCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("↺ ${process.restartCount}x", color = PYellow.copy(alpha = 0.6f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Action buttons ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BotActionBtn("📋 LOG", PCyan) { onViewLog() }
            if (process.isRunning) {
                BotActionBtn("⏹ STOP", PRed) { onStop() }
                BotActionBtn("↺ RESTART", PYellow) { onRestart() }
            } else {
                BotActionBtn("▶ START", PGreen) { onRestart() }
            }
        }
    }
}

@Composable
fun RowScope.BotActionBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BotDialogField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = PGreenD, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = PGreenD.copy(alpha = 0.3f),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PGreen,
                unfocusedTextColor = PGreen,
                focusedBorderColor = PGreen,
                unfocusedBorderColor = PBorder,
                focusedContainerColor = Color(0xFF020802),
                unfocusedContainerColor = Color(0xFF020802),
                cursorColor = PGreen
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
    }
}

// ── Live Log Screen ───────────────────────────────────────────
@Composable
fun LiveLogScreen(
    process: ManagedProcess,
    viewModel: ChatViewModel,
    onClose: () -> Unit
) {
    var logContent by remember { mutableStateOf("Loading...") }
    var lastFileSize by remember { mutableStateOf(0L) }
    var isLive by remember { mutableStateOf(true) }
    var lineCount by remember { mutableStateOf(50) }
    val listState = rememberLazyListState()
    val logLines = remember(logContent) { logContent.lines() }

    // Auto-refresh every 2 seconds when live
    LaunchedEffect(isLive, lineCount) {
        while (isActive && isLive) {
            val result = viewModel.fetchLiveLog(process.logFile, lineCount, lastFileSize)
            if (result.isNotEmpty()) {
                if (lastFileSize > 0) {
                    // Append new content only
                    logContent = logContent + result
                } else {
                    logContent = result
                }
                // Update file size for next incremental read
                lastFileSize = viewModel.getLogFileSize(process.logFile)
                // Auto-scroll to bottom
                val lineCount2 = logContent.lines().size
                if (lineCount2 > 1) {
                    listState.animateScrollToItem(lineCount2 - 1)
                }
            }
            delay(2000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020702))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, PBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close",
                                tint = PGreen, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("[ LIVE LOG ]", color = PGreen, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(process.name, color = PCyan, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        // Live indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isLive) PGreen.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { isLive = !isLive; lastFileSize = 0L }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (isLive) PGreen else PRed, RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isLive) "LIVE" else "PAUSED",
                                color = if (isLive) PGreen else PRed,
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Line count selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, start = 32.dp)
                    ) {
                        listOf(30, 50, 100, 200).forEach { n ->
                            Text(
                                "[$n]",
                                color = if (lineCount == n) PGreen else PGreenD.copy(alpha = 0.4f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clickable {
                                        lineCount = n
                                        lastFileSize = 0L
                                        logContent = "Loading..."
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Clear log button
                        Text(
                            "[CLEAR LOG]",
                            color = PRed.copy(alpha = 0.5f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable {
                                    viewModel.clearLogFile(process.logFile)
                                    logContent = "(log cleared)"
                                    lastFileSize = 0L
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Log content
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                itemsIndexed(logLines) { _, line ->
                    val lineColor = when {
                        line.contains("ERROR", ignoreCase = true) ||
                        line.contains("Traceback", ignoreCase = true) ||
                        line.contains("CRITICAL", ignoreCase = true) -> PRed
                        line.contains("WARNING", ignoreCase = true) ||
                        line.contains("WARN", ignoreCase = true) -> PYellow
                        line.contains("TXDOST WATCHDOG", ignoreCase = true) -> PCyan
                        line.startsWith("[") -> PGreenD
                        else -> PGreen.copy(alpha = 0.8f)
                    }
                    Text(
                        text = line,
                        color = lineColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // Scroll to bottom FAB
        FloatingActionButton(
            onClick = {
                lastFileSize = 0L
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(40.dp),
            containerColor = PGreen.copy(alpha = 0.2f),
            contentColor = PGreen
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll Down",
                modifier = Modifier.size(20.dp))
        }
    }
}
