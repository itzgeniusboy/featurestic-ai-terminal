package com.txdost.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONObject

// ── Colors ────────────────────────────────────────────────────
private val DBg     = Color(0xFF060D06)
private val DGreen  = Color(0xFF00FF41)
private val DGreenD = Color(0xFF00C832)
private val DCyan   = Color(0xFF00E5FF)
private val DYellow = Color(0xFFFFD700)
private val DRed    = Color(0xFFFF1744)
private val DOrange = Color(0xFFFF6600)
private val DBorder = Color(0xFF003300)
private val DCardBg = Color(0xFF0A120A)

data class DashboardStats(
    val battery: Int = 0,
    val batteryStatus: String = "Unknown",
    val ramTotal: Int = 0,
    val ramUsed: Int = 0,
    val ramFree: Int = 0,
    val storageTotal: String = "N/A",
    val storageUsed: String = "N/A",
    val storageFree: String = "N/A",
    val cpuLoad: Float = 0f,
    val uptime: String = "N/A",
    val isLoaded: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var stats by remember { mutableStateOf(DashboardStats()) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf("Never") }

    // ── History for mini-graphs (last 20 readings) ─────────────
    val ramHistory    = remember { mutableStateListOf<Float>() }
    val battHistory   = remember { mutableStateListOf<Float>() }
    val cpuHistory    = remember { mutableStateListOf<Float>() }

    suspend fun fetchStats(vm: ChatViewModel) {
        refreshing = true
        try {
            // Device info from /info endpoint
            val infoJson = vm.fetchDeviceInfo()
            val battery      = infoJson.optInt("battery_val", 0)
            val battStatus   = infoJson.optString("battery_status", "Unknown")
            val ramTotal     = infoJson.optInt("ram_total_mb", 0)
            val ramFree      = infoJson.optInt("ram_free_mb", 0)
            val ramUsed      = infoJson.optInt("ram_used_mb", 0)
            val stTotal      = infoJson.optString("storage_total", "N/A")
            val stUsed       = infoJson.optString("storage_used", "N/A")
            val stFree       = infoJson.optString("storage_free", "N/A")

            // CPU load — /proc/stat se calculate
            val cpuResult = vm.runDirect("cat /proc/loadavg | awk '{print \$1}'")
            val cpuLoad = cpuResult.trim().toFloatOrNull() ?: 0f

            // Uptime
            val uptimeResult = vm.runDirect("uptime -p 2>/dev/null || uptime")
            val uptime = uptimeResult.trim().take(40)

            val ramPct = if (ramTotal > 0) (ramUsed.toFloat() / ramTotal * 100f) else 0f

            stats = DashboardStats(
                battery = battery,
                batteryStatus = battStatus,
                ramTotal = ramTotal,
                ramUsed = ramUsed,
                ramFree = ramFree,
                storageTotal = stTotal,
                storageUsed = stUsed,
                storageFree = stFree,
                cpuLoad = cpuLoad,
                uptime = uptime,
                isLoaded = true
            )

            // Graphs history update
            if (ramHistory.size >= 20) ramHistory.removeAt(0)
            if (battHistory.size >= 20) battHistory.removeAt(0)
            if (cpuHistory.size >= 20) cpuHistory.removeAt(0)
            ramHistory.add(ramPct)
            battHistory.add(battery.toFloat())
            cpuHistory.add((cpuLoad * 10f).coerceAtMost(100f))

            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            lastUpdated = sdf.format(java.util.Date())
        } catch (e: Exception) { /* ignore */ }
        refreshing = false
    }

    // Auto refresh every 5 seconds
    LaunchedEffect(autoRefresh, uiState.termuxConnected) {
        if (!uiState.termuxConnected) return@LaunchedEffect
        while (isActive) {
            fetchStats(viewModel)
            if (!autoRefresh) break
            delay(5000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(DBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── TopBar ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, DBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = DGreen, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("[ LIVE DASHBOARD ]", color = DGreen, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp)
                        Text("Updated: $lastUpdated", color = DCyan, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    // Auto refresh toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (autoRefresh) DGreen.copy(alpha = 0.15f) else Color.Transparent)
                            .border(BorderStroke(1.dp, if (autoRefresh) DGreen.copy(alpha = 0.5f) else DBorder),
                                RoundedCornerShape(3.dp))
                            .clickable { autoRefresh = !autoRefresh }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (autoRefresh) "AUTO" else "PAUSED",
                            color = if (autoRefresh) DGreen else DGreenD.copy(alpha = 0.4f),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { scope.launch { fetchStats(viewModel) } },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                            tint = if (refreshing) DYellow else DGreenD,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!uiState.termuxConnected) {
                // Offline state
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠", color = DYellow, fontSize = 32.sp, fontFamily = FontFamily.Monospace)
                        Text("TERMUX OFFLINE", color = DYellow, fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp)
                        Text("Server se connect karo", color = DGreenD, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Battery Card ─────────────────────────────
                item {
                    val battColor = when {
                        stats.battery >= 60 -> DGreen
                        stats.battery >= 30 -> DYellow
                        else -> DRed
                    }
                    DashCard(title = "BATTERY") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${stats.battery}%",
                                color = battColor,
                                fontSize = 36.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(stats.batteryStatus, color = DCyan,
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { stats.battery / 100f },
                                    modifier = Modifier.width(120.dp).height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = battColor,
                                    trackColor = DBorder
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // Mini graph
                            MiniGraph(values = battHistory, color = battColor,
                                modifier = Modifier.size(80.dp, 40.dp))
                        }
                    }
                }

                // ── RAM Card ─────────────────────────────────
                item {
                    val ramPct = if (stats.ramTotal > 0)
                        stats.ramUsed.toFloat() / stats.ramTotal else 0f
                    val ramColor = when {
                        ramPct < 0.6f -> DGreen
                        ramPct < 0.8f -> DYellow
                        else -> DRed
                    }
                    DashCard(title = "RAM") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("${stats.ramUsed}", color = ramColor,
                                        fontSize = 28.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold)
                                    Text(" MB", color = ramColor.copy(alpha = 0.6f),
                                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                }
                                Text("/ ${stats.ramTotal} MB total | ${stats.ramFree} MB free",
                                    color = DGreenD, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { ramPct },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = ramColor,
                                    trackColor = DBorder
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("${(ramPct * 100).toInt()}% used",
                                    color = ramColor.copy(alpha = 0.7f),
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.width(12.dp))
                            MiniGraph(values = ramHistory, color = ramColor,
                                modifier = Modifier.size(80.dp, 40.dp))
                        }
                    }
                }

                // ── CPU Load Card ─────────────────────────────
                item {
                    val cpuColor = when {
                        stats.cpuLoad < 1f  -> DGreen
                        stats.cpuLoad < 2f  -> DYellow
                        else -> DRed
                    }
                    DashCard(title = "CPU LOAD") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(String.format("%.2f", stats.cpuLoad),
                                        color = cpuColor, fontSize = 28.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text(" avg", color = cpuColor.copy(alpha = 0.6f),
                                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                }
                                Text("1-min load average", color = DGreenD,
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(4.dp))
                                Text("Uptime: ${stats.uptime}", color = DCyan,
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.width(12.dp))
                            MiniGraph(values = cpuHistory, color = cpuColor,
                                modifier = Modifier.size(80.dp, 40.dp))
                        }
                    }
                }

                // ── Storage Card ──────────────────────────────
                item {
                    DashCard(title = "STORAGE (/sdcard)") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StorageStat("TOTAL", stats.storageTotal, DGreen)
                            StorageStat("USED",  stats.storageUsed,  DYellow)
                            StorageStat("FREE",  stats.storageFree,  DCyan)
                        }
                    }
                }

                // ── Active Bots ───────────────────────────────
                item {
                    val procs = uiState.activeProcesses
                    DashCard(title = "ACTIVE BOTS / PROCESSES (${procs.size})") {
                        if (procs.isEmpty()) {
                            Text("No active processes", color = DGreenD.copy(alpha = 0.5f),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                procs.forEach { proc ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF060F06), RoundedCornerShape(4.dp))
                                            .border(BorderStroke(1.dp, if (proc.isRunning)
                                                DGreen.copy(alpha = 0.3f) else DRed.copy(alpha = 0.3f)),
                                                RoundedCornerShape(4.dp))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier
                                            .size(7.dp)
                                            .background(if (proc.isRunning) DGreen else DRed,
                                                RoundedCornerShape(50)))
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(proc.name, color = DGreen, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                            Text(if (proc.pid > 0) "PID: ${proc.pid}" else "PID: unknown",
                                                color = DCyan.copy(alpha = 0.6f), fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace)
                                        }
                                        Text(if (proc.isRunning) "RUNNING" else "STOPPED",
                                            color = if (proc.isRunning) DGreen else DRed,
                                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ── Helper: DashCard container ────────────────────────────────
@Composable
fun DashCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DCardBg, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, DBorder), RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF001400), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("// $title", color = DCyan, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

// ── Helper: Storage stat item ─────────────────────────────────
@Composable
fun StorageStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, color = color.copy(alpha = 0.6f), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

// ── Mini sparkline graph ───────────────────────────────────────
@Composable
fun MiniGraph(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) {
        Box(modifier = modifier.background(Color(0xFF020802), RoundedCornerShape(3.dp)))
        return
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .background(Color(0xFF020802), RoundedCornerShape(3.dp))
            .padding(4.dp)
    ) {
        val max = values.max().coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            androidx.compose.ui.geometry.Offset(
                x = i * step,
                y = size.height - (v / max * size.height)
            )
        }
        for (i in 0 until pts.size - 1) {
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = pts[i],
                end = pts[i + 1],
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        // Last dot
        drawCircle(color = color, radius = 3f, center = pts.last())
    }
}

// Extension on ChatViewModel for dashboard
suspend fun ChatViewModel.fetchDeviceInfo(): JSONObject {
    return try {
        this.getDeviceInfoJson()
    } catch (e: Exception) {
        JSONObject()
    }
}

suspend fun ChatViewModel.runDirect(cmd: String): String {
    return try {
        this.runCommandForDashboard(cmd)
    } catch (e: Exception) { "" }
}
