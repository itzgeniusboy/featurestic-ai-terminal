package com.txdost

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Step 1: Yeh class Application mein install karo ───────
class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val log = buildCrashLog(thread, throwable)

            // SharedPreferences mein save karo
            context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash", log)
                .putLong("crash_time", System.currentTimeMillis())
                .apply()

            // CrashActivity kholo
            val intent = Intent(context, CrashActivity::class.java).apply {
                putExtra("crash_log", log)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

        } catch (e: Exception) {
            // Agar crash handler khud crash kare toh default handler ko de do
            defaultHandler?.uncaughtException(thread, throwable)
        } finally {
            Process.killProcess(Process.myPid())
        }
    }

    private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
        return buildString {
            appendLine("=== Featurestic AI Terminal Crash Report ===")
            appendLine("Time: ${java.util.Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== Exception ===")
            appendLine(throwable.toString())
            appendLine()
            appendLine("=== Stack Trace ===")
            throwable.stackTrace.forEach { appendLine("  at $it") }

            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine()
                appendLine("=== Caused By ===")
                appendLine(cause.toString())
                cause.stackTrace.take(15).forEach { appendLine("  at $it") }
                cause = cause.cause
                depth++
            }
        }
    }
}

// ─── Step 2: Yeh Activity crash hone par khulti hai ────────
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashLog = intent.getStringExtra("crash_log")
            ?: getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                .getString("last_crash", "No crash log found") ?: "No crash log found"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CrashScreen(
                    crashLog = crashLog,
                    onCopy = { copyToClipboard(crashLog) },
                    onRestart = { restartApp() }
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Crash Log", text))
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}

// ─── Step 3: Crash screen UI ───────────────────────────────
@Composable
fun CrashScreen(crashLog: String, onCopy: () -> Unit, onRestart: () -> Unit) {
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("App Crash Report") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A0000)
                )
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "App crash ho gaya. Niche error copy karo aur Claude ko bhejo.",
                color = Color(0xFFFF6B6B),
                fontSize = 14.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onCopy(); copied = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text(if (copied) "Copied!" else "Error Copy Karo")
                }
                OutlinedButton(onClick = onRestart) {
                    Text("Restart", color = Color.White)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1A1A1A),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = crashLog,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF00FF88),
                    lineHeight = 16.sp
                )
            }
        }
    }
}
