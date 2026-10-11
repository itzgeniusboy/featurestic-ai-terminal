package com.txdost.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TermuxBridge(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val SERVER_URL = "http://127.0.0.1:8080"
        const val TERMUX_BIN = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    // Standard client — most requests
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Long running commands ke liye
    private val longClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(620, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Ping — fast timeout
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) { false }
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url("$SERVER_URL/ping").get().build()
                val res = pingClient.newCall(req).execute()
                if (res.isSuccessful) return@withContext true
            } catch (_: Exception) {
                if (attempt < 2) delay(800)
            }
        }
        false
    }

    // ── Single command — DIRECT /run endpoint ───────────────────
    // Purana live output wala broken tha — yeh seedha run karta hai
    // aur onOutput callback se simulated progress deta hai
    suspend fun runCommandWithLiveOutput(
        command: String,
        timeoutSec: Int = 120,
        onOutput: (newLines: List<String>) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        // Progress simulation — user ko pata chale kuch ho raha hai
        onOutput(listOf("Running: $command"))

        try {
            val body = JSONObject()
                .put("command", command)
                .put("timeout", timeoutSec.coerceAtMost(600))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url("$SERVER_URL/run").post(body).build()
            val res = longClient.newCall(req).execute()
            val json = JSONObject(res.body?.string() ?: "{}")

            val output   = json.optString("output", "(no output)")
            val exitCode = json.optInt("exit_code", -1)
            val success  = json.optBoolean("success", exitCode == 0)

            // Output lines callback mein bhejo
            val lines = output.lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) onOutput(lines)

            CommandResult(output = output, exitCode = exitCode, success = success)
        } catch (e: Exception) {
            val errMsg = "Connection error: ${e.message}"
            onOutput(listOf(errMsg))
            CommandResult(output = errMsg, exitCode = -1, success = false)
        }
    }

    // Single command — direct
    suspend fun runCommandAndGetOutput(command: String, timeoutSec: Int = 30): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("command", command)
                    .put("timeout", timeoutSec.coerceAtMost(600))
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$SERVER_URL/run").post(body).build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                CommandResult(
                    output   = json.optString("output", "(no output)"),
                    exitCode = json.optInt("exit_code", -1),
                    success  = json.optBoolean("success", false)
                )
            } catch (e: Exception) {
                CommandResult("Connection error: ${e.message}", -1, false)
            }
        }

    // Multiple commands sequentially
    suspend fun runMultipleCommands(commands: List<String>): List<CommandResult> =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray().apply { commands.forEach { put(it) } }
                val body = JSONObject().put("commands", arr).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$SERVER_URL/run_multi").post(body).build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                val results = json.optJSONArray("results") ?: return@withContext emptyList()
                (0 until results.length()).map { i ->
                    val r = results.getJSONObject(i)
                    CommandResult(
                        output   = r.optString("output", "(no output)"),
                        exitCode = if (r.optBoolean("success")) 0 else 1,
                        success  = r.optBoolean("success")
                    )
                }
            } catch (e: Exception) {
                listOf(CommandResult("Connection error: ${e.message}", -1, false))
            }
        }

    // ── Persistent shell sessions (Termux-like state) ────────
    // Create a new persistent bash session; returns its id (or null on failure).
    suspend fun sessionNew(): String? = withContext(Dispatchers.IO) {
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL/session/new").post(body).build()
            val res = client.newCall(req).execute()
            val json = JSONObject(res.body?.string() ?: "{}")
            json.optString("id").ifBlank { null }
        } catch (e: Exception) { null }
    }

    // Run a command inside a persistent session. If id is null/blank the server
    // creates a new session and returns its id in the result.
    suspend fun sessionRun(id: String, command: String, timeoutSec: Int = 120): SessionResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("id", id)
                    .put("command", command)
                    .put("timeout", timeoutSec.coerceAtMost(600))
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$SERVER_URL/session/run").post(body).build()
                val res = longClient.newCall(req).execute()
                val json = JSONObject(res.body?.string() ?: "{}")
                SessionResult(
                    id       = json.optString("id", id),
                    output   = json.optString("output", ""),
                    exitCode = json.optInt("exit_code", -1),
                    success  = json.optBoolean("success", false)
                )
            } catch (e: Exception) {
                SessionResult(id, "Connection error: ${e.message}", -1, false)
            }
        }

    suspend fun sessionKill(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("id", id).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL/session/kill").post(body).build()
            client.newCall(req).execute().isSuccessful
        } catch (e: Exception) { false }
    }

    // ── Storage access (phone files, Termux-like) ────────────
    // Ask Termux to run termux-setup-storage (grants ~/storage symlinks to /sdcard).
    fun requestStorageSetup() {
        runCommandViaTermux("termux-setup-storage", background = false)
    }

    suspend fun hasStorageAccess(): Boolean = withContext(Dispatchers.IO) {
        val r = runCommandAndGetOutput(
            "test -d /sdcard/Download && (ls /sdcard >/dev/null 2>&1 && echo FS_OK || echo FS_NO) || echo FS_NO",
            10
        )
        r.output.contains("FS_OK")
    }

    // Kya ye path phone/shared storage ka hai? (/sdcard == /storage/emulated/0 == ~/storage/shared)
    fun isSharedStoragePath(path: String): Boolean {
        val p = path.trim()
        return p.startsWith("/sdcard") || p.startsWith("/storage") || p.startsWith("~/storage")
    }

    // Shared storage pakka karo — zarurat pade to Termux se permission maango, phir wait karo.
    // false = permission nahi mili (user ne deny kiya ya Termux install nahi).
    suspend fun ensureStorageAccess(): Boolean {
        if (hasStorageAccess()) return true
        requestStorageSetup()
        repeat(6) {
            delay(1500)
            if (hasStorageAccess()) return true
        }
        return false
    }

    // File Termux mein likho
    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("path", path).put("content", content).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL/write").post(body).build()
            val res = client.newCall(req).execute()
            JSONObject(res.body?.string() ?: "{}").optBoolean("success")
        } catch (e: Exception) { false }
    }

    // Package install — silent mode, large packages supported (ffmpeg, nodejs, etc.)
    // Output is trimmed to ~5 lines so AI context is not flooded
    suspend fun installPackage(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            // Safety: sirf valid package names allow karo (command injection rok)
            if (!packageName.matches(Regex("[A-Za-z0-9._+\\-]+")))
                return@withContext CommandResult("Invalid package name: $packageName", -1, false)
            // -y = auto confirm; pipe to log file; only tail last 5 lines for progress
            // At end, emit a clear success/failure marker so we can detect it reliably
            val silentCmd = "DEBIAN_FRONTEND=noninteractive pkg install -y $packageName " +
                "> /tmp/pkg_install_${packageName}.log 2>&1; " +
                "EXIT_CODE=\$?; " +
                "if [ \$EXIT_CODE -eq 0 ]; then " +
                "  echo 'PKG_OK:$packageName'; " +
                "else " +
                "  echo 'PKG_FAIL:$packageName'; " +
                "  grep -i 'error\\|unable\\|E:' /tmp/pkg_install_${packageName}.log 2>/dev/null | tail -4; " +
                "fi"

            val body = JSONObject()
                .put("command", silentCmd)
                .put("timeout", 600)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url("$SERVER_URL/run").post(body).build()
            val res = longClient.newCall(req).execute()
            val json = JSONObject(res.body?.string() ?: "{}")
            val rawOutput = json.optString("output", "")
            val httpSuccess = json.optBoolean("success", false)

            val success = rawOutput.contains("PKG_OK:") ||
                          (httpSuccess && !rawOutput.contains("PKG_FAIL:"))

            // Trim to a compact summary — AI gets max 6 lines, not 500
            val trimmedOutput = if (success) {
                "$packageName installed successfully"
            } else {
                val errorLines = rawOutput.lines()
                    .filter { it.contains("error", ignoreCase = true) || it.contains("unable", ignoreCase = true) || it.startsWith("E:") }
                    .take(4)
                    .joinToString("\n")
                "$packageName install failed\n${errorLines.ifEmpty { "Check: cat /tmp/pkg_install_${packageName}.log" }}"
            }

            CommandResult(output = trimmedOutput, exitCode = if (success) 0 else 1, success = success)
        } catch (e: Exception) {
            CommandResult("Install error: ${e.message}", -1, false)
        }
    }

    // Device info
    suspend fun getDeviceInfo(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$SERVER_URL/info").get().build()
            val res = client.newCall(req).execute()
            JSONObject(res.body?.string() ?: "{}")
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    fun openTermux() {
        context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
    }

    // Run a shell command inside Termux via the RUN_COMMAND service (1-tap setup).
    // Requires Termux's allow-external-apps=true (one-time) + our manifest permission.
    fun runCommandViaTermux(command: String, background: Boolean = true): Boolean {
        return try {
            val intent = Intent()
            intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            context.startService(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun postJson(endpoint: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        try {
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL$endpoint").post(body).build()
            val res = client.newCall(req).execute()
            res.body?.string() ?: "{}"
        } catch (e: Exception) { "{}" }
    }

    // Live log fetch
    suspend fun fetchLiveLog(logFile: String, lines: Int, sinceByteOffset: Long = 0): String =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("logfile", logFile)
                    .put("lines", lines)
                    .put("since_byte", sinceByteOffset)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$SERVER_URL/live_log").post(body).build()
                val res = client.newCall(req).execute()
                JSONObject(res.body?.string() ?: "{}").optString("content", "")
            } catch (e: Exception) { "" }
        }

    // Log file size
    suspend fun getLogFileSize(logFile: String): Long = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("logfile", logFile)
                .put("lines", 1)
                .put("since_byte", 0)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL/live_log").post(body).build()
            val res = client.newCall(req).execute()
            JSONObject(res.body?.string() ?: "{}").optLong("file_size", 0)
        } catch (e: Exception) { 0L }
    }

    // Clear log file
    suspend fun clearLogFile(logFile: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val expanded = if (logFile.startsWith("~")) TERMUX_HOME + logFile.drop(1) else logFile
            val body = JSONObject()
                .put("command", "> '$expanded' && echo ok")
                .put("timeout", 5)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$SERVER_URL/run").post(body).build()
            val res = client.newCall(req).execute()
            JSONObject(res.body?.string() ?: "{}").optBoolean("success", false)
        } catch (e: Exception) { false }
    }
}

data class CommandResult(
    val output: String,
    val exitCode: Int,
    val success: Boolean
)

data class SessionResult(
    val id: String,
    val output: String,
    val exitCode: Int,
    val success: Boolean
)
