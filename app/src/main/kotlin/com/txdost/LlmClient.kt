package com.txdost.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageBase64: String? = null,
    val imageMime: String? = null,
    val fileName: String? = null
)

// ─────────────────────────────────────────────────────────────
//  LlmClient — one client for every provider.
//  OpenAI-compatible providers use SSE chat/completions.
//  Ollama Cloud uses its native NDJSON API.
//  Optional Termux proxy: route requests through 127.0.0.1:8080.
// ─────────────────────────────────────────────────────────────
class LlmClient {

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    // Active provider set by the ViewModel as prefs change.
    var provider: ProviderSpec = ProviderRegistry.default

    // Termux proxy toggle (settings)
    var proxyEnabled: Boolean = false
    var proxyBaseUrl: String = "http://127.0.0.1:8080/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeCall: okhttp3.Call? = null

    fun cancelActiveCall() {
        activeCall?.cancel()
        activeCall = null
    }

    // ── Effective URL: proxy rewrites the base, real base sent as header ──
    private fun effectiveBase(spec: ProviderSpec): String =
        if (proxyEnabled && !spec.nativeOllama) proxyBaseUrl.trimEnd('/') else spec.baseUrl.trimEnd('/')

    // ── PUBLIC: streaming chat ────────────────────────────────
    fun chatStream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        fileContext: String = ""
    ): Flow<String> {
        val spec = provider
        return if (spec.nativeOllama) {
            ollamaChatStream(spec, apiKey, model, messages, systemPrompt, fileContext)
        } else {
            openAiChatStream(spec, apiKey, model, messages, systemPrompt, fileContext)
        }
    }

    // ── OpenAI-compatible SSE ─────────────────────────────────
    private fun openAiChatStream(
        spec: ProviderSpec,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        fileContext: String
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            val arr = JSONArray()
            val sys = buildSystem(systemPrompt, fileContext)
            if (sys.isNotBlank()) {
                arr.put(JSONObject().put("role", "system").put("content", sys))
            }
            messages.forEach { m ->
                if (m.imageBase64.isNullOrBlank()) {
                    arr.put(JSONObject().put("role", m.role).put("content", m.content))
                } else {
                    val parts = JSONArray()
                    if (m.content.isNotBlank())
                        parts.put(JSONObject().put("type", "text").put("text", m.content))
                    val dataUrl = "data:${m.imageMime ?: "image/jpeg"};base64,${m.imageBase64}"
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl))
                    )
                    arr.put(JSONObject().put("role", m.role).put("content", parts))
                }
            }
            put("messages", arr)
        }.toString()

        val reqBuilder = Request.Builder()
            .url(effectiveBase(spec) + spec.chatPath)
            .post(payload.toRequestBody(jsonMedia))
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        if (proxyEnabled) reqBuilder.addHeader("X-Provider-Base", spec.baseUrl)
        if (spec.id == "openrouter") {
            reqBuilder.addHeader("HTTP-Referer", "https://featurestic.ai")
            reqBuilder.addHeader("X-Title", "Featurestic AI Terminal")
        }

        val call = client.newCall(reqBuilder.build())
        activeCall = call
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "No error body"
                if (err.contains("FreeTierError", true) ||
                    err.contains("free tier can only be used", true)) {
                    emit("[ERROR] OpenCode Zen ke free models sirf official OpenCode app ke andar chalte hain — kisi doosre app se allowed nahi. Koi doosra free provider (OpenRouter/Groq/Gemini) chuno, ya apni OpenCode Zen key se PAID model use karo.")
                } else {
                    emit("[ERROR] HTTP ${response.code}: ${err.take(400)}")
                }
                return@flow
            }
            val reader = response.body?.source()?.inputStream()?.bufferedReader()
                ?: run { emit("[ERROR] Empty response body"); return@flow }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (call.isCanceled()) break
                val current = line ?: continue
                val trimmed = current.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    val obj = JSONObject(data)
                    obj.optJSONObject("error")?.let {
                        emit("[ERROR] " + (it.optString("message").ifBlank { it.toString() }))
                        return@flow
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta")
                    val content = delta?.optString("content").orEmpty()
                        .ifBlank { choice.optJSONObject("message")?.optString("content").orEmpty() }
                    if (content.isNotEmpty()) emit(content)
                } catch (_: Exception) { }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) return@flow
            emit("[ERROR] Network error: ${e.message}")
        } finally {
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    // ── Ollama native NDJSON ──────────────────────────────────
    private fun ollamaChatStream(
        spec: ProviderSpec,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        fileContext: String
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            val arr = JSONArray()
            val sys = buildSystem(systemPrompt, fileContext)
            if (sys.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", sys))
            messages.forEach { m ->
                val o = JSONObject().put("role", m.role).put("content", m.content)
                if (!m.imageBase64.isNullOrBlank())
                    o.put("images", JSONArray().put(m.imageBase64))
                arr.put(o)
            }
            put("messages", arr)
        }.toString()

        val reqBuilder = Request.Builder()
            .url(spec.baseUrl.trimEnd('/') + spec.chatPath)
            .post(payload.toRequestBody(jsonMedia))
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")

        val call = client.newCall(reqBuilder.build())
        activeCall = call
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "No error body"
                emit("[ERROR] HTTP ${response.code}: ${err.take(400)}")
                return@flow
            }
            val reader = response.body?.source()?.inputStream()?.bufferedReader()
                ?: run { emit("[ERROR] Empty response body"); return@flow }
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (call.isCanceled()) break
                val current = line?.trim() ?: continue
                if (current.isEmpty()) continue
                try {
                    val obj = JSONObject(current)
                    obj.optString("error").takeIf { it.isNotBlank() }?.let {
                        emit("[ERROR] $it"); return@flow
                    }
                    val token = obj.optJSONObject("message")?.optString("content").orEmpty()
                    if (token.isNotEmpty()) emit(token)
                    if (obj.optBoolean("done", false)) break
                } catch (_: Exception) { }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) return@flow
            emit("[ERROR] Network error: ${e.message}")
        } finally {
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    private fun buildSystem(systemPrompt: String, fileContext: String): String {
        val sb = StringBuilder(systemPrompt)
        if (fileContext.isNotEmpty()) {
            sb.append("\n\n--- FILE CONTEXT ---\n").append(fileContext).append("\n--- END FILE ---")
        }
        return sb.toString()
    }

    // ── PUBLIC: instance ping (uses current provider) ─────────
    suspend fun ping(apiKey: String): Boolean = ping(provider, apiKey)

    suspend fun ping(providerSpec: ProviderSpec, apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            fetchModels(providerSpec, apiKey).isSuccess
        }

    // ── PUBLIC: models for a provider (free only) ─────────────
    suspend fun getModels(providerSpec: ProviderSpec, apiKey: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) { fetchModels(providerSpec, apiKey) }

    private fun fetchModels(providerSpec: ProviderSpec, apiKey: String): Result<List<ModelInfo>> {
        return try {
            val url = providerSpec.baseUrl.trimEnd('/') + providerSpec.modelsPath
            val reqBuilder = Request.Builder().url(url).get()
            if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return Result.failure(IOException("HTTP ${response.code}"))
            }
            val body = response.body?.string() ?: ""
            val root = JSONObject(body)
            val models = if (providerSpec.nativeOllama) {
                parseOllamaModels(root)
            } else {
                parseOpenAiModels(root, providerSpec.freeFilter)
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseOllamaModels(root: JSONObject): List<ModelInfo> {
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("model") }
            if (name.isNotBlank()) out.add(ModelInfo(id = name, name = name))
        }
        return out
    }

    private fun parseOpenAiModels(root: JSONObject, filter: FreeFilter): List<ModelInfo> {
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            if (isNonChat(id)) continue
            val free = when (filter) {
                FreeFilter.ALL -> true
                FreeFilter.ID_FREE -> id.contains("free", ignoreCase = true) ||
                    id.contains("big-pickle", ignoreCase = true)
                FreeFilter.PRICING_ZERO -> {
                    if (id.endsWith(":free")) true else {
                        val p = o.optJSONObject("pricing")
                        val prompt = p?.optString("prompt")
                        val completion = p?.optString("completion")
                        prompt == "0" && (completion == null || completion == "0")
                    }
                }
            }
            if (!free) continue
            val display = o.optString("name").ifBlank { id }
            out.add(ModelInfo(id = id, name = display, free = true))
        }
        return out.distinctBy { it.id }
    }

    private fun isNonChat(id: String): Boolean {
        val l = id.lowercase()
        val blocked = listOf(
            "embed", "embedding", "whisper", "tts", "audio", "speech",
            "moderation", "rerank", "dall-e", "stable-diffusion", "flux",
            "upscale", "guard", "riva", "parakeet", "canary", "bge", "gte"
        )
        return blocked.any { l.contains(it) }
    }
}
