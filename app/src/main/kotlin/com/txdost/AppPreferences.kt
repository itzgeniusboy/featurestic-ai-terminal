package com.txdost.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "featurestic_settings")

class AppPreferences(private val context: Context) {

    companion object {
        val PROVIDER_ID        = stringPreferencesKey("provider_id")
        val SYSTEM_PROMPT      = stringPreferencesKey("system_prompt")
        val ACTIVE_CONV_ID     = stringPreferencesKey("active_conv_id")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val TAVILY_API_KEY     = stringPreferencesKey("tavily_api_key")
        val FIRECRAWL_API_KEY  = stringPreferencesKey("firecrawl_api_key")
        // ── Termux proxy ─────────────────────────────────────
        val USE_TERMUX_PROXY   = booleanPreferencesKey("use_termux_proxy")
        val TERMUX_PROXY_URL   = stringPreferencesKey("termux_proxy_url")
        val GLOBAL_SYSTEM_PROMPT = stringPreferencesKey("global_system_prompt")
        val MODEL_CATALOG      = stringPreferencesKey("model_catalog")
        val AGENT_MODE         = stringPreferencesKey("agent_mode")
        val SKILLS_JSON        = stringPreferencesKey("skills_json")
        val THEME_MODE         = stringPreferencesKey("theme_mode")
        val SHOW_COMMANDS      = booleanPreferencesKey("show_commands")
        val AUTO_ROTATE_KEYS   = booleanPreferencesKey("auto_rotate_keys")
        val BODY_TEXT_SIZE     = stringPreferencesKey("body_text_size")

        private fun apiKeyKey(providerId: String) = stringPreferencesKey("api_key_$providerId")
        private fun apiKeysKey(providerId: String) = stringPreferencesKey("api_keys_$providerId")
        private fun modelKey(providerId: String) = stringPreferencesKey("model_$providerId")
    }

    // ── Provider ─────────────────────────────────────────────
    val providerId: Flow<String> = context.dataStore.data.map { it[PROVIDER_ID] ?: ProviderRegistry.default.id }

    fun apiKeyFor(providerId: String): Flow<String> =
        context.dataStore.data.map { parseKeys(it[apiKeysKey(providerId)], it[apiKeyKey(providerId)]).firstOrNull() ?: "" }

    /** All API keys for a provider (a provider can have multiple keys for load-spreading). */
    fun apiKeysFor(providerId: String): Flow<List<String>> =
        context.dataStore.data.map { parseKeys(it[apiKeysKey(providerId)], it[apiKeyKey(providerId)]) }

    private fun parseKeys(json: String?, legacy: String?): List<String> {
        val out = mutableListOf<String>()
        if (!json.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val k = arr.optString(i).trim()
                    if (k.isNotEmpty()) out.add(k)
                }
            } catch (_: Exception) { }
        }
        if (out.isEmpty() && !legacy.isNullOrBlank()) out.add(legacy.trim())
        return out
    }

    fun modelFor(providerId: String): Flow<String> =
        context.dataStore.data.map { it[modelKey(providerId)] ?: defaultModel(providerId) }

    private fun defaultModel(providerId: String): String = when (providerId) {
        "openrouter" -> "meta-llama/llama-3.3-70b-instruct:free"
        "groq"       -> "llama-3.3-70b-versatile"
        "gemini"     -> "gemini-2.0-flash"
        "mistral"    -> "mistral-small-latest"
        "nvidia"     -> "meta/llama-3.1-8b-instruct"
        "opencode"   -> "big-pickle"
        "ollama"     -> "glm-4.6"
        else         -> ""
    }

    // ── System prompt ────────────────────────────────────────
    val systemPrompt: Flow<String> = context.dataStore.data.map { it[SYSTEM_PROMPT] ?: "" }
    val activeConvId: Flow<String> = context.dataStore.data.map { it[ACTIVE_CONV_ID] ?: "" }

    // ── Web search ───────────────────────────────────────────
    val webSearchProvider: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_PROVIDER] ?: "DUCKDUCKGO" }
    val tavilyApiKey: Flow<String> = context.dataStore.data.map { it[TAVILY_API_KEY] ?: "" }
    val firecrawlApiKey: Flow<String> = context.dataStore.data.map { it[FIRECRAWL_API_KEY] ?: "" }

    // ── Termux proxy ─────────────────────────────────────────
    val useTermuxProxy: Flow<Boolean> = context.dataStore.data.map { it[USE_TERMUX_PROXY] ?: false }
    val termuxProxyUrl: Flow<String> = context.dataStore.data.map {
        it[TERMUX_PROXY_URL] ?: "http://127.0.0.1:8080/v1"
    }

    // ── Free-model catalog (JSON) for auto-fallback ──────────
    val modelCatalog: Flow<String> = context.dataStore.data.map { it[MODEL_CATALOG] ?: "" }

    // ── Agent mode + skills ──────────────────────────────────
    val agentMode: Flow<String> = context.dataStore.data.map { it[AGENT_MODE] ?: "build" }
    val skillsJson: Flow<String> = context.dataStore.data.map { it[SKILLS_JSON] ?: "" }
    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "system" }
    val showCommands: Flow<Boolean> = context.dataStore.data.map { it[SHOW_COMMANDS] ?: true }
    val autoRotateKeys: Flow<Boolean> = context.dataStore.data.map { it[AUTO_ROTATE_KEYS] ?: false }
    /** Chat body text size: "small" | "normal" | "large". */
    val bodyTextSize: Flow<String> = context.dataStore.data.map { it[BODY_TEXT_SIZE] ?: "normal" }

    // ── Generation ───────────────────────────────────────────
    val globalSystemPrompt: Flow<String> = context.dataStore.data.map {
        it[GLOBAL_SYSTEM_PROMPT]
            ?: "You are Featurestic AI, an AI assistant that helps users with terminal tasks, " +
               "coding, and general questions. Be concise and helpful."
    }

    // ── Saves ────────────────────────────────────────────────
    suspend fun saveProviderId(id: String) = context.dataStore.edit { it[PROVIDER_ID] = id }
    suspend fun saveApiKeyFor(providerId: String, key: String) =
        saveApiKeysFor(providerId, listOf(key))

    suspend fun saveApiKeysFor(providerId: String, keys: List<String>) {
        val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        context.dataStore.edit { e ->
            e[apiKeysKey(providerId)] = org.json.JSONArray(cleaned).toString()
            e[apiKeyKey(providerId)] = cleaned.firstOrNull() ?: ""
        }
    }
    suspend fun saveModelFor(providerId: String, model: String) =
        context.dataStore.edit { it[modelKey(providerId)] = model }

    suspend fun saveSystemPrompt(prompt: String) = context.dataStore.edit { it[SYSTEM_PROMPT] = prompt }
    suspend fun saveActiveConvId(id: String) = context.dataStore.edit { it[ACTIVE_CONV_ID] = id }
    suspend fun saveWebSearchProvider(p: String) = context.dataStore.edit { it[WEB_SEARCH_PROVIDER] = p }
    suspend fun saveTavilyApiKey(key: String) = context.dataStore.edit { it[TAVILY_API_KEY] = key }
    suspend fun saveFirecrawlApiKey(key: String) = context.dataStore.edit { it[FIRECRAWL_API_KEY] = key }
    suspend fun saveUseTermuxProxy(enabled: Boolean) = context.dataStore.edit { it[USE_TERMUX_PROXY] = enabled }
    suspend fun saveTermuxProxyUrl(url: String) = context.dataStore.edit { it[TERMUX_PROXY_URL] = url }
    suspend fun saveGlobalSystemPrompt(prompt: String) = context.dataStore.edit { it[GLOBAL_SYSTEM_PROMPT] = prompt }
    suspend fun saveModelCatalog(json: String) = context.dataStore.edit { it[MODEL_CATALOG] = json }
    suspend fun saveAgentMode(mode: String) = context.dataStore.edit { it[AGENT_MODE] = mode }
    suspend fun saveSkills(json: String) = context.dataStore.edit { it[SKILLS_JSON] = json }
    suspend fun saveThemeMode(mode: String) = context.dataStore.edit { it[THEME_MODE] = mode }
    suspend fun saveShowCommands(enabled: Boolean) = context.dataStore.edit { it[SHOW_COMMANDS] = enabled }
    suspend fun saveAutoRotateKeys(enabled: Boolean) = context.dataStore.edit { it[AUTO_ROTATE_KEYS] = enabled }
    suspend fun saveBodyTextSize(value: String) = context.dataStore.edit { it[BODY_TEXT_SIZE] = value }

    /** Wipe every stored preference (keys, models, prompt, skills, catalog). */
    suspend fun clearAll() = context.dataStore.edit { it.clear() }

    // ── Backup / restore (settings + skills) ─────────────────
    suspend fun exportJson(): String {
        val p = context.dataStore.data.first()
        val root = JSONObject()
        root.put("app", "Featurestic AI Terminal")
        root.put("version", 1)
        root.put("providerId", p[PROVIDER_ID] ?: ProviderRegistry.default.id)
        root.put("systemPrompt", p[SYSTEM_PROMPT] ?: "")
        root.put("globalSystemPrompt", p[GLOBAL_SYSTEM_PROMPT] ?: "")
        root.put("webSearchProvider", p[WEB_SEARCH_PROVIDER] ?: "DUCKDUCKGO")
        root.put("tavilyApiKey", p[TAVILY_API_KEY] ?: "")
        root.put("firecrawlApiKey", p[FIRECRAWL_API_KEY] ?: "")
        root.put("agentMode", p[AGENT_MODE] ?: "build")
        root.put("themeMode", p[THEME_MODE] ?: "system")
        root.put("skillsJson", p[SKILLS_JSON] ?: "")
        root.put("modelCatalog", p[MODEL_CATALOG] ?: "")
        root.put("showCommands", p[SHOW_COMMANDS] ?: true)
        root.put("bodyTextSize", p[BODY_TEXT_SIZE] ?: "normal")
        root.put("useTermuxProxy", p[USE_TERMUX_PROXY] ?: false)
        root.put("termuxProxyUrl", p[TERMUX_PROXY_URL] ?: "http://127.0.0.1:8080/v1")
        root.put("activeConvId", p[ACTIVE_CONV_ID] ?: "")
        val providers = JSONObject()
        ProviderRegistry.all.forEach { spec ->
            providers.put(spec.id, JSONObject().apply {
                val ks = parseKeys(p[apiKeysKey(spec.id)], p[apiKeyKey(spec.id)])
                put("apiKey", ks.firstOrNull() ?: "")
                put("apiKeys", org.json.JSONArray(ks))
                put("model", p[modelKey(spec.id)] ?: "")
            })
        }
        root.put("providers", providers)
        return root.toString(2)
    }

    suspend fun importJson(json: String) {
        val root = JSONObject(json)
        context.dataStore.edit { e ->
            root.optString("providerId").takeIf { it.isNotBlank() }?.let { e[PROVIDER_ID] = it }
            e[SYSTEM_PROMPT] = root.optString("systemPrompt", "")
            e[GLOBAL_SYSTEM_PROMPT] = root.optString("globalSystemPrompt", "")
            e[WEB_SEARCH_PROVIDER] = root.optString("webSearchProvider", "DUCKDUCKGO")
            e[TAVILY_API_KEY] = root.optString("tavilyApiKey", "")
            e[FIRECRAWL_API_KEY] = root.optString("firecrawlApiKey", "")
            e[AGENT_MODE] = root.optString("agentMode", "build")
            e[THEME_MODE] = root.optString("themeMode", "system")
            e[SKILLS_JSON] = root.optString("skillsJson", "")
            e[MODEL_CATALOG] = root.optString("modelCatalog", "")
            if (root.has("showCommands")) e[SHOW_COMMANDS] = root.optBoolean("showCommands", true)
            e[BODY_TEXT_SIZE] = root.optString("bodyTextSize", "normal")
            e[USE_TERMUX_PROXY] = root.optBoolean("useTermuxProxy", false)
            e[TERMUX_PROXY_URL] = root.optString("termuxProxyUrl", "http://127.0.0.1:8080/v1")
            val convId = root.optString("activeConvId", "")
            if (convId.isNotBlank()) e[ACTIVE_CONV_ID] = convId
            val prov = root.optJSONObject("providers")
            if (prov != null) {
                for (spec in ProviderRegistry.all) {
                    val o = prov.optJSONObject(spec.id) ?: continue
                    val arr = o.optJSONArray("apiKeys")
                    val ks = mutableListOf<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { ks.add(it) }
                    }
                    if (ks.isEmpty()) o.optString("apiKey", "").trim().takeIf { it.isNotEmpty() }?.let { ks.add(it) }
                    if (ks.isNotEmpty()) {
                        val cleaned = ks.distinct()
                        e[apiKeysKey(spec.id)] = org.json.JSONArray(cleaned).toString()
                        e[apiKeyKey(spec.id)] = cleaned.first()
                    }
                    val m = o.optString("model", "")
                    if (m.isNotEmpty()) e[modelKey(spec.id)] = m
                }
            }
        }
    }
}
