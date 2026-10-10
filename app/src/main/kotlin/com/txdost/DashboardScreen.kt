package com.txdost.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.ui.components.AppleBadge
import com.txdost.ui.components.AppleTopBar
import com.txdost.ui.theme.Apple
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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

@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val c = Apple.colors
    val uiState by viewModel.uiState.collectAsState()
    var stats by remember { mutableStateOf(DashboardStats()) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf("Never") }

    val ramHistory = remember { mutableStateListOf<Float>() }
    val battHistory = remember { mutableStateListOf<Float>() }
    val cpuHistory = remember { mutableStateListOf<Float>() }

    suspend fun fetchStats(vm: ChatViewModel) {
        refreshing = true
        try {
            val infoJson = vm.fetchDeviceInfo()
            val battery = infoJson.optInt("battery_val", 0)
            val battStatus = infoJson.optString("battery_status", "Unknown")
            val ramTotal = infoJson.optInt("ram_total_mb", 0)
            val ramFree = infoJson.optInt("ram_free_mb", 0)
            val ramUsed = infoJson.optInt("ram_used_mb", 0)
            val stTotal = infoJson.optString("storage_total", "N/A")
            val stUsed = infoJson.optString("storage_used", "N/A")
            val stFree = infoJson.optString("storage_free", "N/A")

            val cpuLoad = vm.runDirect("cat /proc/loadavg | awk '{print \$1}'").trim().toFloatOrNull() ?: 0f
            val uptime = vm.runDirect("uptime -p 2>/dev/null || uptime").trim().take(40)
            val ramPct = if (ramTotal > 0) (ramUsed.toFloat() / ramTotal * 100f) else 0f

            stats = DashboardStats(
                battery = battery, batteryStatus = battStatus,
                ramTotal = ramTotal, ramUsed = ramUsed, ramFree = ramFree,
                storageTotal = stTotal, storageUsed = stUsed, storageFree = stFree,
                cpuLoad = cpuLoad, uptime = uptime, isLoaded = true
            )

            if (ramHistory.size >= 20) ramHistory.removeAt(0)
            if (battHistory.size >= 20) battHistory.removeAt(0)
            if (cpuHistory.size >= 20) cpuHistory.removeAt(0)
            ramHistory.add(ramPct)
            battHistory.add(battery.toFloat())
            cpuHistory.add((cpuLoad * 10f).coerceAtMost(100f))

            lastUpdated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        } catch (_: Exception) {}
        refreshing = false
    }

    LaunchedEffect(autoRefresh, uiState.termuxConnected) {
        if (!uiState.termuxConnected) return@LaunchedEffect
        while (isActive) {
            fetchStats(viewModel)
            if (!autoRefresh) break
            delay(5000)
        }
    }

    val c2 = Apple.colors
    Column(Modifier.fillMaxSize().background(c2.bg)) {
        AppleTopBar(
            title = "Dashboard",
            subtitle = if (lastUpdated == "Never") null else "Updated $lastUpdated",
            onBack = onBack,
            actions = {
                Text(
                    if (autoRefresh) "Live" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (autoRefresh) c2.blue else c2.secondaryLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { autoRefresh = !autoRefresh }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                IconButton(onClick = { fetchStats(viewModel) }) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = c2.blue)
                }
            }
        )

        if (!uiState.termuxConnected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Termux offline", style = MaterialTheme.typography.titleMedium, color = c2.label)
                    Spacer(Modifier.height(6.dp))
                    Text("Connect the server to see live stats.", style = MaterialTheme.typography.bodyMedium, color = c2.secondaryLabel)
                }
            }
        } else {

        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                val battColor = when {
                    stats.battery >= 60 -> c2.green
                    stats.battery >= 30 -> c2.orange
                    else -> c2.red
                }
                StatCard(title = "Battery", badge = stats.batteryStatus) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${stats.battery}%", style = MaterialTheme.typography.displaySmall, color = battColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        MiniGraph(battHistory, battColor, Modifier.size(90.dp, 44.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(stats.battery / 100f, battColor)
                }
            }

            item {
                val ramPct = if (stats.ramTotal > 0) stats.ramUsed.toFloat() / stats.ramTotal else 0f
                val ramColor = when {
                    ramPct < 0.6f -> c2.green
                    ramPct < 0.8f -> c2.orange
                    else -> c2.red
                }
                StatCard(title = "Memory") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${stats.ramUsed} MB", style = MaterialTheme.typography.titleLarge, color = ramColor, fontWeight = FontWeight.Bold)
                            Text("of ${stats.ramTotal} MB • ${stats.ramFree} MB free", style = MaterialTheme.typography.bodySmall, color = c2.secondaryLabel)
                        }
                        MiniGraph(ramHistory, ramColor, Modifier.size(90.dp, 44.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(ramPct, ramColor)
                    Spacer(Modifier.height(6.dp))
                    Text("${(ramPct * 100).toInt()}% used", style = MaterialTheme.typography.labelSmall, color = c2.secondaryLabel)
                }
            }

            item {
                val cpuColor = when {
                    stats.cpuLoad < 1f -> c2.green
                    stats.cpuLoad < 2f -> c2.orange
                    else -> c2.red
                }
                StatCard(title = "CPU load") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(String.format("%.2f", stats.cpuLoad), style = MaterialTheme.typography.titleLarge, color = cpuColor, fontWeight = FontWeight.Bold)
                            Text("1-min average", style = MaterialTheme.typography.bodySmall, color = c2.secondaryLabel)
                            Spacer(Modifier.height(4.dp))
                            Text("Uptime: ${stats.uptime}", style = MaterialTheme.typography.labelSmall, color = c2.secondaryLabel)
                        }
                        MiniGraph(cpuHistory, cpuColor, Modifier.size(90.dp, 44.dp))
                    }
                }
            }

            item {
                StatCard(title = "Storage") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StorageStat("Total", stats.storageTotal, c2.label)
                        StorageStat("Used", stats.storageUsed, c2.orange)
                        StorageStat("Free", stats.storageFree, c2.blue)
                    }
                }
            }

            item {
                val procs = uiState.activeProcesses
                StatCard(title = "Processes", badge = "${procs.size}") {
                    if (procs.isEmpty()) {
                        Text("No active processes", style = MaterialTheme.typography.bodyMedium, color = c2.secondaryLabel)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            procs.forEach { proc ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp).clip(RoundedCornerShape(50))
                                            .background(if (proc.isRunning) c2.green else c2.red)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(proc.name, style = MaterialTheme.typography.bodyMedium, color = c2.label, fontWeight = FontWeight.SemiBold)
                                        Text(if (proc.pid > 0) "PID ${proc.pid}" else "PID unknown", style = MaterialTheme.typography.labelSmall, color = c2.secondaryLabel)
                                    }
                                    Text(if (proc.isRunning) "Running" else "Stopped", style = MaterialTheme.typography.labelSmall, color = if (proc.isRunning) c2.green else c2.red)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

@Composable
private fun StatCard(title: String, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Apple.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.secondaryBg).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.secondaryLabel)
            Spacer(Modifier.weight(1f))
            if (badge != null) AppleBadge(badge)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    val c = Apple.colors
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)
    ) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = Color.Transparent
        )
    }
}

@Composable
private fun StorageStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Apple.colors.secondaryLabel)
    }
}

@Composable
private fun MiniGraph(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val c = Apple.colors
    if (values.size < 2) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.fill.copy(alpha = 0.4f)))
        return
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(c.fill.copy(alpha = 0.4f)).padding(6.dp)) {
        val max = values.max().coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Offset(x = i * step, y = size.height - (v / max * size.height))
        }
        for (i in 0 until pts.size - 1) {
            drawLine(color = color, start = pts[i], end = pts[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
        }
        drawCircle(color = color, radius = 3.5f, center = pts.last())
    }
}

suspend fun ChatViewModel.fetchDeviceInfo(): org.json.JSONObject = try {
    this.getDeviceInfoJson()
} catch (e: Exception) { org.json.JSONObject() }

suspend fun ChatViewModel.runDirect(cmd: String): String = try {
    this.runCommandForDashboard(cmd)
} catch (e: Exception) { "" }
