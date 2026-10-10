package com.txdost.data

// ─────────────────────────────────────────────────────────────
//  Provider registry — every backend the app can talk to.
//  6 are OpenAI-compatible (chat/completions + models).
//  Ollama Cloud uses its own native API (api/chat + api/tags).
// ─────────────────────────────────────────────────────────────

enum class FreeFilter {
    PRICING_ZERO, // OpenRouter: keep models priced 0 (or id ends with ":free")
    ID_FREE,      // OpenCode Zen: keep ids containing "free" (+ big-pickle)
    ALL           // Groq / Gemini / Mistral / NVIDIA / Ollama: whole API is free-tier
}

data class ProviderSpec(
    val id: String,
    val name: String,
    val baseUrl: String,
    val chatPath: String = "/chat/completions",
    val modelsPath: String = "/models",
    val freeFilter: FreeFilter = FreeFilter.ALL,
    val keyHint: String = "",
    val nativeOllama: Boolean = false
)

object ProviderRegistry {

    val OPENROUTER = ProviderSpec(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        freeFilter = FreeFilter.PRICING_ZERO,
        keyHint = "openrouter.ai → Keys → Create key"
    )

    val GROQ = ProviderSpec(
        id = "groq",
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        freeFilter = FreeFilter.ALL,
        keyHint = "console.groq.com → API Keys"
    )

    val GEMINI = ProviderSpec(
        id = "gemini",
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        freeFilter = FreeFilter.ALL,
        keyHint = "aistudio.google.com → Get API key"
    )

    val MISTRAL = ProviderSpec(
        id = "mistral",
        name = "Mistral",
        baseUrl = "https://api.mistral.ai/v1",
        freeFilter = FreeFilter.ALL,
        keyHint = "console.mistral.ai → API Keys"
    )

    val NVIDIA = ProviderSpec(
        id = "nvidia",
        name = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        freeFilter = FreeFilter.ALL,
        keyHint = "build.nvidia.com → Get API Key"
    )

    val OPENCODE = ProviderSpec(
        id = "opencode",
        name = "OpenCode Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        freeFilter = FreeFilter.ID_FREE,
        keyHint = "opencode.ai/auth → Zen → API key"
    )

    val OLLAMA = ProviderSpec(
        id = "ollama",
        name = "Ollama Cloud",
        baseUrl = "https://ollama.com",
        chatPath = "/api/chat",
        modelsPath = "/api/tags",
        freeFilter = FreeFilter.ALL,
        keyHint = "ollama.com → Settings → API Keys",
        nativeOllama = true
    )

    val all: List<ProviderSpec> = listOf(
        OPENROUTER, GROQ, GEMINI, MISTRAL, NVIDIA, OPENCODE, OLLAMA
    )

    val default: ProviderSpec = OPENROUTER

    fun byId(id: String): ProviderSpec = all.firstOrNull { it.id == id } ?: default
}

// Model entry shown in the UI.
data class ModelInfo(
    val id: String,
    val name: String = id,
    val free: Boolean = true
)
