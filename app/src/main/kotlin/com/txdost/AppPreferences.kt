package com.txdost.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
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

        private fun apiKeyKey(providerId: String) = stringPreferencesKey("api_key_$providerId")
        private fun modelKey(providerId: String) = stringPreferencesKey("model_$providerId")
    }

    // ── Provider ─────────────────────────────────────────────
    val providerId: Flow<String> = context.dataStore.data.map { it[PROVIDER_ID] ?: ProviderRegistry.default.id }

    fun apiKeyFor(providerId: String): Flow<String> =
        context.dataStore.data.map { it[apiKeyKey(providerId)] ?: "" }

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

    // ── Generation ───────────────────────────────────────────
    val globalSystemPrompt: Flow<String> = context.dataStore.data.map {
        it[GLOBAL_SYSTEM_PROMPT]
            ?: "You are Featurestic AI, an AI assistant that helps users with terminal tasks, " +
               "coding, and general questions. Be concise and helpful."
    }

    // ── Saves ────────────────────────────────────────────────
    suspend fun saveProviderId(id: String) = context.dataStore.edit { it[PROVIDER_ID] = id }
    suspend fun saveApiKeyFor(providerId: String, key: String) =
        context.dataStore.edit { it[apiKeyKey(providerId)] = key }
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

    /** Wipe every stored preference (keys, models, prompt, skills, catalog). */
    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
