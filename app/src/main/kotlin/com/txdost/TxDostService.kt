package com.txdost

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.txdost.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ── Process tracking ─────────────────────────────────────────
data class ManagedProcess(
    val id: String,
    val name: String,
    val command: String,
    val logFile: String,
    val pid: Int = -1,
    val startedAt: Long = System.currentTimeMillis(),
    val isRunning: Boolean = true,
    val autoRestart: Boolean = false,
    val restartCount: Int = 0
)

// ── Service ──────────────────────────────────────────────────
class TxDostService : Service() {

    companion object {
        const val CHANNEL_ID       = "txdost_channel"
        const val CHANNEL_BG_ID    = "txdost_bg_channel"
        const val NOTIF_ONGOING    = 1001
        const val ACTION_STOP_TASK = "com.txdost.STOP_TASK"
        const val ACTION_RUN_SCHEDULED_TASK = "com.txdost.RUN_SCHEDULED_TASK"
        const val EXTRA_TASK_ID    = "task_id"
        const val EXTRA_CONV_ID    = "conv_id"

        fun start(context: Context) {
            val intent = Intent(context, TxDostService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TxDostService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()
    private lateinit var scheduledTaskManager: ScheduledTaskManager
    private lateinit var prefs: AppPreferences

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    val processes: StateFlow<List<ManagedProcess>> = _processes.asStateFlow()

    private var currentAiJob: Job? = null
    private var _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): TxDostService = this@TxDostService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ONGOING, buildOngoingNotification("Featurestic AI Active", "Ready"))
        scheduledTaskManager = ScheduledTaskManager(applicationContext)
        prefs = AppPreferences(applicationContext)

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "TxDost::AiTaskLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) stopProcess(taskId)
                else stopCurrentAiTask()
            }
            ACTION_RUN_SCHEDULED_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                runScheduledTask(taskId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    fun stopCurrentAiTask() {
        currentAiJob?.cancel()
        currentAiJob = null
        _isGenerating.value = false
        sendNotification("Task Stopped", "AI task rok diya gaya")
    }

    fun setCurrentAiJob(job: Job) { currentAiJob = job }
    fun setGenerating(v: Boolean) {
        _isGenerating.value = v
        if (v) acquireWakeLock() else releaseWakeLock()
    }

    // ── Process Management ───────────────────────────────────

    /**
     * Register a background process.
     * pid = actual Linux PID (from `echo $!` after nohup).
     * Pass pid = -1 agar pata nahi — fallback pkill use hoga.
     */
    fun registerProcess(id: String, name: String, command: String, logFile: String, pid: Int = -1) {
        val p = ManagedProcess(id = id, name = name, command = command, logFile = logFile, pid = pid)
        _processes.update { it + p }
        updateOngoingNotification()
        sendNotification("Started: $name", "Background mein chal raha hai")
    }

    fun stopProcess(id: String) {
        val proc = _processes.value.find { it.id == id } ?: return
        serviceScope.launch {
            val termux = TermuxBridge(applicationContext)

            if (proc.pid > 0) {
                // ✅ RELIABLE: PID se directly kill karo (whole process group)
                termux.runCommandAndGetOutput("kill -TERM -${proc.pid} 2>/dev/null; kill -TERM ${proc.pid} 2>/dev/null; sleep 1; kill -KILL ${proc.pid} 2>/dev/null; echo done", 10)
            } else {
                // Fallback: command name se pkill (kam reliable)
                val cmdPattern = proc.command.trim().take(40).replace("'", "")
                termux.runCommandAndGetOutput("pkill -f '$cmdPattern' 2>/dev/null; sleep 1; pkill -9 -f '$cmdPattern' 2>/dev/null; echo done", 10)
            }

            _processes.update { list -> list.filter { it.id != id } }
            sendNotification("Stopped: ${proc.name}", "Process band kar diya")
            updateOngoingNotification()
        }
    }

    fun stopAllProcesses() {
        // Copy list first — stopProcess modifies it
        val toStop = _processes.value.map { it.id }
        toStop.forEach { stopProcess(it) }
    }

    fun isProcessRunning(id: String): Boolean = _processes.value.any { it.id == id }

    fun getProcessByName(name: String): ManagedProcess? =
        _processes.value.find { it.name.trim().equals(name.trim(), ignoreCase = true) }

    fun toggleAutoRestart(id: String) {
        _processes.update { list ->
            list.map { proc ->
                if (proc.id == id) proc.copy(autoRestart = !proc.autoRestart) else proc
            }
        }
        // Sync with server watchdog
        val proc = _processes.value.find { it.id == id } ?: return
        serviceScope.launch {
            val termux = TermuxBridge(applicationContext)
            val endpoint = if (proc.autoRestart) "/watchdog_register" else "/watchdog_remove"
            val body = if (proc.autoRestart) {
                org.json.JSONObject()
                    .put("proc_id", proc.id)
                    .put("command", proc.command)
                    .put("logfile", proc.logFile)
                    .put("pid", proc.pid)
                    .put("auto_restart", true)
                    .toString()
            } else {
                org.json.JSONObject().put("proc_id", proc.id).toString()
            }
            termux.postJson(endpoint, body)
        }
    }

    fun checkProcessStatuses() {
        serviceScope.launch {
            val termux = TermuxBridge(applicationContext)
            _processes.value.forEach { proc ->
                val checkCmd = if (proc.pid > 0) {
                    "kill -0 ${proc.pid} 2>/dev/null && echo RUNNING || echo STOPPED"
                } else {
                    "pgrep -f '${proc.command.take(20)}' && echo RUNNING || echo STOPPED"
                }
                val result = termux.runCommandAndGetOutput(checkCmd, 5)
                val nowRunning = result.output.contains("RUNNING")
                val wasRunning = proc.isRunning
                _processes.update { list ->
                    list.map { if (it.id == proc.id) it.copy(isRunning = nowRunning) else it }
                }
                if (wasRunning && !nowRunning) {
                    sendNotification("Stopped: ${proc.name}", "Process ruk gaya. Log check karo.")
                }
            }
        }
    }

    // ── Scheduled Task Runner ────────────────────────────────
    fun scheduleResearchTask(task: com.txdost.data.ScheduledTask) {
        scheduledTaskManager.addTask(task)
        val timeStr = scheduledTaskManager.formatTriggerTime(task.triggerAtMs)
        sendNotification(
            "📅 Task Scheduled",
            "${task.topic.take(40)} - $timeStr",
            convId = task.convId
        )
    }

    fun getScheduledTasks() = scheduledTaskManager.getAllTasks()

    fun deleteScheduledTask(id: String) {
        scheduledTaskManager.deleteTask(id)
    }

    private fun runScheduledTask(taskId: String) {
        val task = scheduledTaskManager.getTask(taskId) ?: run {
            android.util.Log.e("TxDost", "Task not found: $taskId")
            return
        }

        android.util.Log.d("TxDost", "Running scheduled task: ${task.topic}")
        acquireWakeLock()
        updateOngoingNotification("\uD83D\uDD0D Research chal raha hai...", task.topic.take(40))

        serviceScope.launch {
            try {
                val providerId = prefs.providerId.first()
                val apiKey = prefs.apiKeyFor(providerId).first()
                val model  = prefs.modelFor(providerId).first()

                android.util.Log.d("TxDost", "Provider: $providerId, Model: $model, apiKey empty: ${apiKey.isEmpty()}")

                if (apiKey.isEmpty()) throw Exception("API key missing! Settings mein set karo.")

                val llm = LlmClient()
                llm.provider = ProviderRegistry.byId(providerId)
                llm.proxyEnabled = prefs.useTermuxProxy.first()
                llm.proxyBaseUrl = prefs.termuxProxyUrl.first()
                val prompt = "Is topic par detail mein research karo: ${task.topic}"
                val sb = StringBuilder()

                llm.chatStream(
                    apiKey = apiKey,
                    model  = model,
                    messages = listOf(ChatMessage("user", prompt)),
                    systemPrompt = "You are Featurestic AI Terminal. Scheduled research task hai. Comprehensive, structured Hinglish mein jawab do.",
                    fileContext = ""
                ).collect { token ->
                    if (!token.startsWith("[ERROR]")) sb.append(token)
                }

                val result = if (sb.isNotEmpty()) sb.toString() else "Koi response nahi mila."

                val historyManager = ChatHistoryManager(applicationContext)
                val researchMsg = ChatMessage(
                    role = "assistant",
                    content = "**Scheduled Research Complete!**\n\n**Topic:** ${task.topic}\n\n---\n\n$result"
                )
                val existing = historyManager.loadMessages(task.convId)
                historyManager.saveMessages(task.convId, existing + researchMsg)
                historyManager.touchConversation(task.convId)

                sendNotification(
                    title  = "Research Ready!",
                    message = "${task.topic.take(35)} — Tap karke dekho",
                    convId = task.convId
                )
                scheduledTaskManager.markTaskRun(taskId)

            } catch (e: Exception) {
                android.util.Log.e("TxDost", "Task failed: ${e.message}", e)
                try {
                    val hm = ChatHistoryManager(applicationContext)
                    val errMsg = ChatMessage("assistant",
                        "**Research Failed**\n\nTopic: ${task.topic}\nError: ${e.message}")
                    hm.saveMessages(task.convId, hm.loadMessages(task.convId) + errMsg)
                } catch (_: Exception) {}
                sendNotification("Research Failed",
                    "${task.topic.take(30)}: ${e.message?.take(40) ?: "error"}",
                    convId = task.convId)
            } finally {
                releaseWakeLock()
                updateOngoingNotification("Featurestic AI Active", "Ready")
            }
        }
    }

    private fun updateOngoingNotification(title: String = "Featurestic AI Active", text: String = "Ready") {
        val count = _processes.value.size
        val displayText = if (count > 0) "$text | $count process(es)" else text
        val notif = buildOngoingNotification(title, displayText)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ONGOING, notif)
    }

    fun sendNotification(title: String, message: String, taskId: String? = null, convId: String? = null) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (convId != null) putExtra(EXTRA_CONV_ID, convId)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_BG_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, (convId ?: "default").hashCode(),
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        if (taskId != null) {
            val stopIntent = Intent(this, TxDostService::class.java).apply {
                action = ACTION_STOP_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            builder.addAction(
                android.R.drawable.ic_delete,
                "Stop",
                PendingIntent.getService(this, taskId.hashCode(), stopIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        try {
            NotificationManagerCompat.from(this).notify((title + "|" + message).hashCode(), builder.build())
        } catch (e: SecurityException) { /* notification permission nahi hai */ }
    }

    private fun buildOngoingNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, TxDostService::class.java).apply {
            action = ACTION_STOP_TASK
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop AI", stopPending)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            NotificationChannel(CHANNEL_ID, "Featurestic AI Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Featurestic AI background service"
                nm.createNotificationChannel(this)
            }

            NotificationChannel(CHANNEL_BG_ID, "Featurestic AI Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Task completion aur alerts"
                nm.createNotificationChannel(this)
            }
        }
    }
}
