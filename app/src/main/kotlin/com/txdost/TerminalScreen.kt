package com.txdost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txdost.data.TermuxBridge
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import kotlinx.coroutines.launch

private data class TermLine(val text: String, val isCommand: Boolean, val isError: Boolean = false)

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val c = Apple.colors
    val context = LocalContext.current
    val bridge = remember { TermuxBridge(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var sessionId by remember { mutableStateOf<String?>(null) }
    var cwd by remember { mutableStateOf("~") }
    var online by remember { mutableStateOf(false) }
    var storageOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val transcript = remember { mutableStateListOf<TermLine>() }

    fun refreshEnv() {
        scope.launch {
            online = bridge.isConnected()
            storageOk = if (online) bridge.hasStorageAccess() else true
        }
    }

    fun run(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty() || busy) return
        input = ""
        transcript.add(TermLine(clean, true))
        busy = true
        scope.launch {
            var sid = sessionId
            if (sid == null) { sid = bridge.sessionNew(); sessionId = sid }
            if (sid == null) {
                transcript.add(TermLine("Not connected — start the Termux bridge (Settings → Connect).", false, true))
                busy = false
                return@launch
            }
            val res = bridge.sessionRun(sid, clean, 180)
            if (res.id.isNotBlank()) sessionId = res.id
            if (res.output.isBlank())
                transcript.add(TermLine("(exit ${res.exitCode})", false, !res.success))
            else
                transcript.add(TermLine(res.output, false, !res.success))
            val pwd = bridge.sessionRun(sid, "pwd", 5)
            if (pwd.success) cwd = pwd.output.trim().ifBlank { cwd }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshEnv() }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        AppleTopBar(title = "Terminal", subtitle = cwd, onBack = onBack)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppleBadge(if (online) "Termux online" else "Termux offline", active = online)
            Spacer(Modifier.width(8.dp))
            AppleBadge(if (sessionId != null) "session" else "no session", active = sessionId != null)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { transcript.clear(); sessionId = null; cwd = "~" }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.DeleteSweep, "Reset", tint = c.secondaryLabel, modifier = Modifier.size(20.dp))
            }
        }

        if (online && !storageOk) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                AppleCard {
                    Text("Phone files access", style = MaterialTheme.typography.titleSmall, color = c.label)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Give Termux storage permission so the terminal can read/write all your phone files (/sdcard, Downloads, DCIM…).",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.secondaryLabel
                    )
                    Spacer(Modifier.height(8.dp))
                    AppleButton("Grant file access", {
                        bridge.requestStorageSetup()
                    }, style = AppleButtonStyle.Tinted)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (transcript.isEmpty()) {
                item {
                    Text(
                        "Type a command and run it. The shell persists, so cd / export / functions stay between commands.",
                        fontFamily = FontFamily.Monospace,
                        color = c.secondaryLabel,
                        fontSize = 13.sp
                    )
                }
            }
            items(transcript) { line ->
                Text(
                    text = if (line.isCommand) "$ ${line.text}" else line.text,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        line.isCommand -> c.blue
                        line.isError -> c.red
                        else -> c.label
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                )
            }
            if (busy) {
                item {
                    Text("…", fontFamily = FontFamily.Monospace, color = c.secondaryLabel, fontSize = 13.sp)
                }
            }
        }

        LaunchedEffect(transcript.size, busy) {
            if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$", fontFamily = FontFamily.Monospace, color = c.green, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("command…", color = c.tertiaryLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { run(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.blue,
                    unfocusedBorderColor = c.separator,
                    focusedTextColor = c.label,
                    unfocusedTextColor = c.label
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { run(input) },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (busy) c.gray else c.blue)
            ) {
                Icon(Icons.Rounded.Send, "Run", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            listOf("pwd", "ls -la", "cd ~", "df -h", "pkg update", "python").forEach { quick ->
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.secondaryBg)
                        .clickable { input = quick }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(quick, style = MaterialTheme.typography.labelSmall, color = c.label)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
