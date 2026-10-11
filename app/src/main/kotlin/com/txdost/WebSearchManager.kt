package com.txdost.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

enum class WebSearchProvider {
    TAVILY,
    FIRECRAWL,
    DUCKDUCKGO  // Free fallback, no key needed
}

class WebSearchManager(
    private val tavilyApiKey: String = "",
    private val firecrawlApiKey: String = "",
    private val provider: WebSearchProvider = WebSearchProvider.DUCKDUCKGO
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun search(query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    WebSearchProvider.TAVILY     -> searchTavily(query, maxResults)
                    WebSearchProvider.FIRECRAWL  -> searchFirecrawl(query, maxResults)
                    WebSearchProvider.DUCKDUCKGO -> searchDuckDuckGo(query, maxResults)
                }
            } catch (e: Exception) {
                android.util.Log.e("WebSearch", "Search failed [${provider.name}]: ${e.message}")
                // Fallback to DuckDuckGo if primary fails
                if (provider != WebSearchProvider.DUCKDUCKGO) {
                    try { searchDuckDuckGo(query, maxResults) } catch (e2: Exception) { emptyList() }
                } else emptyList()
            }
        }

    // ── TAVILY ──────────────────────────────────────────────────
    // POST https://api.tavily.com/search
    // Bearer tvly-YOUR_KEY
    private fun searchTavily(query: String, max: Int): List<SearchResult> {
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", max)
            put("search_depth", "basic")      // "basic" or "advanced"
            put("include_answer", true)        // AI-generated direct answer
            put("topic", "general")            // "general" or "news"
        }.toString().toRequestBody(JSON_TYPE)

        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $tavilyApiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(req).execute()
        val bodyStr  = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            android.util.Log.e("WebSearch", "Tavily HTTP ${response.code}: $bodyStr")
            return emptyList()
        }

        val json    = JSONObject(bodyStr)
        val results = mutableListOf<SearchResult>()

        // Direct answer (best part of Tavily)
        val answer = json.optString("answer", "")
        if (answer.isNotBlank()) {
            results.add(SearchResult("Direct Answer", answer, ""))
        }

        // Search results
        val arr = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), max)) {
            val item    = arr.optJSONObject(i) ?: continue
            val title   = item.optString("title", "Result ${i + 1}")
            val content = item.optString("content", "")
            val url     = item.optString("url", "")
            if (content.isNotBlank()) {
                results.add(SearchResult(title, content.take(400), url))
            }
        }

        return results.take(max)
    }

    // ── FIRECRAWL ────────────────────────────────────────────────
    // POST https://api.firecrawl.dev/v1/search
    // Bearer fc-YOUR_KEY
    private fun searchFirecrawl(query: String, max: Int): List<SearchResult> {
        val body = JSONObject().apply {
            put("query", query)
            put("limit", max)
            put("scrapeOptions", JSONObject().apply {
                put("formats", JSONArray().apply { put("markdown") })
                put("onlyMainContent", true)
            })
        }.toString().toRequestBody(JSON_TYPE)

        val req = Request.Builder()
            .url("https://api.firecrawl.dev/v1/search")
            .header("Authorization", "Bearer $firecrawlApiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(req).execute()
        val bodyStr  = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            android.util.Log.e("WebSearch", "Firecrawl HTTP ${response.code}: $bodyStr")
            return emptyList()
        }

        val json    = JSONObject(bodyStr)
        val results = mutableListOf<SearchResult>()
        val arr     = json.optJSONArray("data") ?: JSONArray()

        for (i in 0 until minOf(arr.length(), max)) {
            val item = arr.optJSONObject(i) ?: continue
            val meta = item.optJSONObject("metadata")
            val title   = meta?.optString("title", "") ?: ""
            val desc    = meta?.optString("description", "") ?: ""
            val url     = meta?.optString("sourceURL", "") ?: item.optString("url", "")
            // Firecrawl returns full markdown — take first 400 chars
            val markdown = item.optString("markdown", "")
            val snippet  = markdown.ifBlank { desc }.take(400)
            if (snippet.isNotBlank()) {
                results.add(SearchResult(title.ifBlank { "Result ${i + 1}" }, snippet, url))
            }
        }

        return results.take(max)
    }

    // ── DUCKDUCKGO (free, no key) ────────────────────────────────
    private fun searchDuckDuckGo(query: String, max: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url     = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val req     = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; FeaturesticAITerminal)")
            .build()

        val response = client.newCall(req).execute()
        val body     = response.body?.string() ?: return emptyList()
        val json     = JSONObject(body)
        return parseDuckDuckGo(json, max)
    }

    private fun parseDuckDuckGo(json: JSONObject, max: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val abstract     = json.optString("Abstract", "")
        val abstractUrl  = json.optString("AbstractURL", "")
        val abstractTitle = json.optString("Heading", "")
        if (abstract.isNotBlank())
            results.add(SearchResult(abstractTitle.ifBlank { "Direct Answer" }, abstract, abstractUrl))

        val answer = json.optString("Answer", "")
        if (answer.isNotBlank() && answer != abstract)
            results.add(SearchResult("Quick Answer", answer, ""))

        val topics = json.optJSONArray("RelatedTopics") ?: JSONArray()
        for (i in 0 until minOf(topics.length(), max)) {
            val item = topics.optJSONObject(i) ?: continue
            val text = item.optString("Text", "")
            val url  = item.optString("FirstURL", "")
            if (text.isNotBlank())
                results.add(SearchResult(
                    url.substringAfterLast("/").replace("_", " ").ifBlank { "Result ${i + 1}" },
                    text, url
                ))
        }

        val infobox = json.optJSONObject("Infobox")?.optJSONArray("content")
        if (infobox != null) {
            val facts = StringBuilder()
            for (i in 0 until minOf(infobox.length(), 5)) {
                val item  = infobox.optJSONObject(i) ?: continue
                val label = item.optString("label", "")
                val value = item.optString("value", "")
                if (label.isNotBlank() && value.isNotBlank()) facts.append("$label: $value\n")
            }
            if (facts.isNotEmpty())
                results.add(0, SearchResult("Key Facts", facts.toString().trim(), ""))
        }

        return results.take(max)
    }

    // ── Format for AI context ────────────────────────────────────
    fun formatForAI(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "Web Search '$query' — Koi result nahi mila."
        val sb = StringBuilder()
        sb.appendLine("=== WEB SEARCH RESULTS ===")
        sb.appendLine("Query: $query")
        sb.appendLine("Provider: ${provider.name}")
        sb.appendLine()
        results.forEachIndexed { i, r ->
            sb.appendLine("[${i + 1}] ${r.title}")
            sb.appendLine(r.snippet.take(350))
            if (r.url.isNotBlank()) sb.appendLine("Source: ${r.url}")
            sb.appendLine()
        }
        sb.appendLine("=== END SEARCH ===")
        return sb.toString()
    }
}
