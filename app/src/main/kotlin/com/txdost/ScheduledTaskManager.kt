package com.txdost.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Data Model ───────────────────────────────────────────────

data class ScheduledTask(
    val id: String,
    val convId: String,         // Jis conversation mein result jaayega
    val topic: String,          // Research topic
    val triggerAtMs: Long,      // Epoch ms — kab run karna hai
    val repeat: RepeatMode,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val isDone: Boolean = false  // One-time tasks ke liye
)

enum class RepeatMode { ONCE, DAILY, WEEKLY }

// ── BroadcastReceiver — AlarmManager trigger karta hai ──────

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        // Service ko batao ki yeh task run karna hai
        val serviceIntent = Intent(context, com.txdost.TxDostService::class.java).apply {
            action = com.txdost.TxDostService.ACTION_RUN_SCHEDULED_TASK
            putExtra("task_id", taskId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(serviceIntent)
        else
            context.startService(serviceIntent)
    }
}

// ── ScheduledTaskManager ─────────────────────────────────────
// Tasks JSON file: /files/txdost_scheduled/tasks.json

class ScheduledTaskManager(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "txdost_scheduled").also { it.mkdirs() }
    }
    private val tasksFile: File get() = File(dir, "tasks.json")

    // ── CRUD ─────────────────────────────────────────────────

    fun getAllTasks(): List<ScheduledTask> {
        if (!tasksFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(tasksFile.readText())
            (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun getTask(id: String): ScheduledTask? = getAllTasks().find { it.id == id }

    fun saveTasks(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(taskToJson(it)) }
        tasksFile.writeText(arr.toString())
    }

    fun addTask(task: ScheduledTask) {
        val list = getAllTasks().toMutableList()
        list.removeAll { it.id == task.id }
        list.add(task)
        saveTasks(list)
        scheduleAlarm(task)
    }

    fun deleteTask(id: String) {
        cancelAlarm(id)
        val list = getAllTasks().filter { it.id != id }
        saveTasks(list)
    }

    fun markTaskRun(id: String) {
        val list = getAllTasks().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val task = list[idx]
        when (task.repeat) {
            RepeatMode.ONCE -> {
                list[idx] = task.copy(isDone = true, lastRunAt = System.currentTimeMillis())
            }
            RepeatMode.DAILY -> {
                val next = task.triggerAtMs + 24 * 60 * 60 * 1000L
                list[idx] = task.copy(triggerAtMs = next, lastRunAt = System.currentTimeMillis())
                scheduleAlarm(list[idx])
            }
            RepeatMode.WEEKLY -> {
                val next = task.triggerAtMs + 7 * 24 * 60 * 60 * 1000L
                list[idx] = task.copy(triggerAtMs = next, lastRunAt = System.currentTimeMillis())
                scheduleAlarm(list[idx])
            }
        }
        saveTasks(list)
    }

    fun getPendingTasks(): List<ScheduledTask> =
        getAllTasks().filter { !it.isDone }

    // ── AlarmManager ─────────────────────────────────────────

    fun scheduleAlarm(task: ScheduledTask) {
        val now = System.currentTimeMillis()

        // Agar time already past hai ya 10 sec ke andar hai — seedha fire karo
        if (task.triggerAtMs <= now + 10_000L) {
            val fireIntent = Intent(context, ScheduledTaskReceiver::class.java).apply {
                putExtra("task_id", task.id)
            }
            context.sendBroadcast(fireIntent)
            return
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            putExtra("task_id", task.id)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // Exact-alarm permission nahi hai (Android 12+) — inexact fallback, warna crash
            am.set(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
                }
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
            }
        }
    }

    fun cancelAlarm(taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) ?: return
        am.cancel(pi)
    }

    // Boot ke baad saare pending tasks re-schedule karo
    fun rescheduleAllPending() {
        getPendingTasks().forEach { scheduleAlarm(it) }
    }

    // ── Helpers ──────────────────────────────────────────────

    fun formatTriggerTime(ms: Long): String {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    fun parseDateTime(dateStr: String, timeStr: String, amPm: String): Long? {
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
            sdf.parse("$dateStr $timeStr $amPm")?.time
        } catch (e: Exception) { null }
    }

    private fun parseTask(obj: JSONObject) = ScheduledTask(
        id          = obj.getString("id"),
        convId      = obj.getString("convId"),
        topic       = obj.getString("topic"),
        triggerAtMs = obj.getLong("triggerAtMs"),
        repeat      = RepeatMode.valueOf(obj.optString("repeat", "ONCE")),
        createdAt   = obj.optLong("createdAt", System.currentTimeMillis()),
        lastRunAt   = obj.optLong("lastRunAt", 0L),
        isDone      = obj.optBoolean("isDone", false)
    )

    private fun taskToJson(t: ScheduledTask) = JSONObject().apply {
        put("id",          t.id)
        put("convId",      t.convId)
        put("topic",       t.topic)
        put("triggerAtMs", t.triggerAtMs)
        put("repeat",      t.repeat.name)
        put("createdAt",   t.createdAt)
        put("lastRunAt",   t.lastRunAt)
        put("isDone",      t.isDone)
    }
}

// ── Boot Receiver — device restart pe re-schedule ────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScheduledTaskManager(context).rescheduleAllPending()
            // Service bhi start karo
            com.txdost.TxDostService.start(context)
        }
    }
}
