package com.txdost.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Data Models ─────────────────────────────────────────────

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val pinned: Boolean = false
)

// ── ChatHistoryManager ───────────────────────────────────────
// Saari conversations JSON files mein save hoti hain:
// /files/txdost_history/conv_{id}.json
// Index file: /files/txdost_history/index.json

class ChatHistoryManager(private val context: Context) {

    private val ioLock = Any()

    private val historyDir: File by lazy {
        File(context.filesDir, "txdost_history").also { it.mkdirs() }
    }

    private val indexFile: File get() = File(historyDir, "index.json")

    // Atomic write: temp file likh ke rename — crash pe adhoora/kharaab file nahi
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    // ── Index read/write ─────────────────────────────────────

    fun getAllConversations(): List<Conversation> = synchronized(ioLock) {
        if (!indexFile.exists()) return@synchronized emptyList()
        try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Conversation(
                    id        = obj.getString("id"),
                    title     = obj.getString("title"),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt"),
                    pinned    = obj.optBoolean("pinned", false)
                )
            }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
        } catch (e: Exception) {
            // Index kharaab — conv files se dobara banao, wipe mat karo
            rebuildIndexFromFiles()
        }
    }

    private fun rebuildIndexFromFiles(): List<Conversation> {
        val convs = historyDir.listFiles { f ->
            f.name.startsWith("conv_") && f.name.endsWith(".json")
        }?.mapNotNull { f ->
            val id = f.name.removePrefix("conv_").removeSuffix(".json")
            try {
                val msgs = loadMessages(id)
                Conversation(
                    id        = id,
                    title     = msgs.firstOrNull { it.role == "user" }?.content?.take(40) ?: "Recovered chat",
                    createdAt = f.lastModified(),
                    updatedAt = f.lastModified()
                )
            } catch (e: Exception) { null }
        } ?: emptyList()
        val sorted = convs.sortedByDescending { it.updatedAt }
        saveIndex(sorted)
        return sorted
    }

    private fun saveIndex(conversations: List<Conversation>) = synchronized(ioLock) {
        val arr = JSONArray()
        conversations.forEach { conv ->
            arr.put(JSONObject().apply {
                put("id",        conv.id)
                put("title",     conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)
                put("pinned",    conv.pinned)
            })
        }
        try { atomicWrite(indexFile, arr.toString()) } catch (e: Exception) { }
    }

    // ── Conversation CRUD ────────────────────────────────────

    fun createConversation(title: String = "New Chat"): Conversation {
        val now  = System.currentTimeMillis()
        val conv = Conversation(
            id        = UUID.randomUUID().toString().take(8),
            title     = title,
            createdAt = now,
            updatedAt = now,
            messages  = emptyList()
        )
        // Index update
        val list = getAllConversations().toMutableList()
        list.add(0, conv)
        saveIndex(list)
        // Empty conv file banao
        saveMessages(conv.id, emptyList())
        return conv
    }

    fun loadMessages(convId: String): List<ChatMessage> = synchronized(ioLock) {
        val file = File(historyDir, "conv_$convId.json")
        if (!file.exists()) return@synchronized emptyList()
        try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    role        = obj.getString("role"),
                    content     = obj.optString("content", ""),
                    timestamp   = obj.optLong("timestamp", System.currentTimeMillis()),
                    imageBase64 = obj.optString("imageBase64").ifBlank { null },
                    imageMime   = obj.optString("imageMime").ifBlank { null },
                    fileName    = obj.optString("fileName").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            // Corrupt file — khaali do, par file delete/mat likho (recovery ke liye rahe)
            emptyList()
        }
    }

    fun saveMessages(convId: String, messages: List<ChatMessage>) = synchronized(ioLock) {
        val arr = JSONArray()
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("role",      msg.role)
                put("content",   msg.content)
                put("timestamp", msg.timestamp)
                msg.imageBase64?.let { put("imageBase64", it) }
                msg.imageMime?.let { put("imageMime", it) }
                msg.fileName?.let { put("fileName", it) }
            })
        }
        try { atomicWrite(File(historyDir, "conv_$convId.json"), arr.toString()) } catch (e: Exception) { }
    }

    fun updateConversationTitle(convId: String, newTitle: String) {
        val list = getAllConversations().toMutableList()
        val idx  = list.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(title = newTitle, updatedAt = System.currentTimeMillis())
            saveIndex(list)
        }
    }

    fun touchConversation(convId: String) {
        val list = getAllConversations().toMutableList()
        val idx  = list.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(updatedAt = System.currentTimeMillis())
            // Top pe le aao
            val updated = list.removeAt(idx)
            list.add(0, updated)
            saveIndex(list)
        }
    }

    fun setPinned(convId: String, pinned: Boolean) {
        val list = getAllConversations().toMutableList()
        val idx  = list.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(pinned = pinned)
            saveIndex(list)
        }
    }

    fun deleteConversation(convId: String) {
        // Index se hatao
        val list = getAllConversations().toMutableList()
        list.removeAll { it.id == convId }
        saveIndex(list)
        // File delete karo
        File(historyDir, "conv_$convId.json").delete()
    }

    // Auto title — pehle user message se banao
    fun generateTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == "user" }?.content ?: return "New Chat"
        return if (firstUser.length > 40) firstUser.take(40) + "…" else firstUser
    }

    // Readable timestamp
    fun formatDate(timestamp: Long): String {
        val now      = System.currentTimeMillis()
        val diffMs   = now - timestamp
        val diffMins = diffMs / 60000
        val diffHrs  = diffMins / 60
        val diffDays = diffHrs / 24
        return when {
            diffMins < 1   -> "Abhi"
            diffMins < 60  -> "${diffMins}m pehle"
            diffHrs  < 24  -> "${diffHrs}h pehle"
            diffDays < 7   -> "${diffDays}d pehle"
            else           -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
