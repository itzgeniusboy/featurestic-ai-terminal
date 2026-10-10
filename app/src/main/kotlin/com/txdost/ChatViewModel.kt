package com.txdost.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.txdost.BuiltInSkills
import com.txdost.Skill
import com.txdost.TxDostService
import com.txdost.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

// Tool test — 2 stage state machine
// IDLE -> WAITING_INPUT -> (run) -> IDLE
// (No confirm step — tool auto-tested after creation)
enum class ToolTestStage { IDLE, WAITING_INPUT }

data class ActiveToolTest(
    val filePath: String,
    val runCommand: String,
    val argHint: String,
    val toolName: String
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentInput: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val termuxConnected: Boolean = false,
    val ollamaOnline: Boolean = false,
    val currentModel: String = "glm-4.6",
    val selectedFileContext: String = "",
    val error: String? = null,
    val activeProcesses: List<com.txdost.ManagedProcess> = emptyList(),
    val debugLogs: List<DebugLogEntry> = emptyList(),   // ← Debug Console
    // ── History ──────────────────────────────────────────────
    val conversations: List<Conversation> = emptyList(),
    val activeConvId: String = "",
    val showHistoryDrawer: Boolean = false,
    // ── Tool Test ────────────────────────────────────────────
    val toolTestStage: ToolTestStage = ToolTestStage.IDLE,
    val activeToolTest: ActiveToolTest? = null,
    val providerId: String = ProviderRegistry.default.id,
    // ── Pending attachment (photo/file to send) ──────────────
    val pendingImageBase64: String? = null,
    val pendingImageMime: String? = null,
    val pendingFileName: String? = null,
    // ── Agent mode + skills ──────────────────────────────────
    val agentMode: String = "build",
    val skills: List<Skill> = emptyList()
)

// Debug log entry
data class DebugLogEntry(
    val time: String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date()),
    val type: DebugLogType,
    val title: String,
    val detail: String = "",
    val isSuccess: Boolean = true
)

enum class DebugLogType { COMMAND, OUTPUT, AI_SEND, AI_RECV, ERROR, INFO }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)
    private val llm = LlmClient()
    private val gson = Gson()
    private val termuxBridge = TermuxBridge(app)
    val fileManager = FileManager(app)
    val historyManager = ChatHistoryManager(app)
    // WebSearch — provider + keys prefs se dynamically banao
    private var webSearch = WebSearchManager()  // default DuckDuckGo until prefs load

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentModel = "glm-4.6"
    private var currentApiKey = ""
    private var currentSystemPrompt = ""

    // Current AI job — direct cancel ke liye ViewModel mein rakho
    private var currentAiJob: Job? = null

    // Service connection
    private var txService: TxDostService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            txService = (binder as TxDostService.LocalBinder).getService()
            // Service ke processes observe karo
            viewModelScope.launch {
                txService!!.processes.collect { procs ->
                    _uiState.update { it.copy(activeProcesses = procs) }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { txService = null }
    }

    init {
        // Service start + bind karo
        TxDostService.start(app)
        app.bindService(
            Intent(app, TxDostService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        viewModelScope.launch {
            prefs.providerId.collectLatest { pid ->
                val spec = ProviderRegistry.byId(pid)
                llm.provider = spec
                _uiState.update { it.copy(providerId = pid) }
                combine(
                    prefs.modelFor(pid),
                    prefs.apiKeyFor(pid),
                    prefs.systemPrompt
                ) { model, apiKey, sysPrompt -> Triple(model, apiKey, sysPrompt) }
                    .collect { (model, apiKey, sysPrompt) ->
                        currentModel = model
                        currentApiKey = apiKey
                        currentSystemPrompt = sysPrompt
                        _uiState.update { it.copy(currentModel = model) }
                        checkConnections()
                    }
            }
        }

        // Agent mode + skills
        viewModelScope.launch {
            prefs.agentMode.collect { mode -> _uiState.update { it.copy(agentMode = mode) } }
        }
        viewModelScope.launch {
            prefs.skillsJson.collect { json -> _uiState.update { it.copy(skills = parseSkills(json)) } }
        }

        // Termux proxy
        viewModelScope.launch {
            combine(prefs.useTermuxProxy, prefs.termuxProxyUrl) { en, url -> en to url }
                .collect { (en, url) ->
                    llm.proxyEnabled = en
                    llm.proxyBaseUrl = url
                }
        }

        // Auto connection watcher start karo
        startConnectionWatcher()

        // Web search provider — prefs change hone par rebuild karo
        viewModelScope.launch {
            combine(
                prefs.webSearchProvider,
                prefs.tavilyApiKey,
                prefs.firecrawlApiKey
            ) { providerStr, tavilyKey, firecrawlKey ->
                webSearch = when (providerStr) {
                    "TAVILY"    -> WebSearchManager(
                        tavilyApiKey = tavilyKey,
                        provider = WebSearchProvider.TAVILY
                    )
                    "FIRECRAWL" -> WebSearchManager(
                        firecrawlApiKey = firecrawlKey,
                        provider = WebSearchProvider.FIRECRAWL
                    )
                    else -> WebSearchManager(provider = WebSearchProvider.DUCKDUCKGO)
                }
                android.util.Log.d("ChatVM", "WebSearch provider set: $providerStr")
            }.collect()
        }

        // History init karo
        viewModelScope.launch {
            val savedId = prefs.activeConvId.first()
            val allConvs = historyManager.getAllConversations()
            if (allConvs.isEmpty()) {
                // Pehli baar — naya conversation banao
                val conv = historyManager.createConversation("New Chat")
                prefs.saveActiveConvId(conv.id)
                _uiState.update {
                    it.copy(
                        conversations = listOf(conv),
                        activeConvId = conv.id,
                        messages = emptyList()
                    )
                }
            } else {
                val activeId = if (savedId.isNotEmpty() && allConvs.any { it.id == savedId })
                    savedId else allConvs.first().id
                val msgs = historyManager.loadMessages(activeId)
                _uiState.update {
                    it.copy(
                        conversations = allConvs,
                        activeConvId = activeId,
                        messages = msgs
                    )
                }
            }
        }
    }

    override fun onCleared() {
        try { getApplication<Application>().unbindService(serviceConnection) } catch (e: Exception) {}
        super.onCleared()
    }

    fun checkConnections() {
        viewModelScope.launch {
            // Termux: 3 attempts with delay (server might be starting)
            var termuxConnected = false
            repeat(3) { attempt ->
                if (!termuxConnected) {
                    termuxConnected = termuxBridge.isConnected()
                    if (!termuxConnected && attempt < 2) delay(1000)
                }
            }
            val isOnline = llm.ping(currentApiKey)
            _uiState.update { it.copy(ollamaOnline = isOnline, termuxConnected = termuxConnected) }
        }
    }

    // Auto connection watcher — har 8 sec check karo
    fun startConnectionWatcher() {
        viewModelScope.launch {
            while (true) {
                delay(8000)
                val termuxConnected = termuxBridge.isConnected()
                val isOnline = llm.ping(currentApiKey)
                val prev = _uiState.value
                if (prev.termuxConnected != termuxConnected || prev.ollamaOnline != isOnline) {
                    _uiState.update { it.copy(ollamaOnline = isOnline, termuxConnected = termuxConnected) }
                }
            }
        }
    }

    // ── STOP current AI task ─────────────────────────────────
    fun stopCurrentTask() {
        // 1. Network call seedha cancel karo — yeh sabse zaroori hai
        llm.cancelActiveCall()
        // 2. Coroutine bhi cancel karo
        currentAiJob?.cancel()
        currentAiJob = null
        txService?.setGenerating(false)
        // 3. UI update
        val streaming = _uiState.value.streamingText
        val msgs = _uiState.value.messages
        val newMsgs = if (streaming.isNotEmpty())
            msgs + ChatMessage("assistant", streaming + "\n\n⏹ [Stopped]")
        else
            msgs + ChatMessage("assistant", "⏹ Task rok diya gaya.")
        _uiState.update {
            it.copy(isGenerating = false, streamingText = "", messages = newMsgs)
        }
        // History save karo
        val convId = _uiState.value.activeConvId
        if (convId.isNotEmpty()) {
            viewModelScope.launch {
                historyManager.saveMessages(convId, newMsgs)
                historyManager.touchConversation(convId)
                refreshConversations()
            }
        }
    }

    // ── Stop a specific background process ───────────────────
    fun stopProcess(id: String) {
        txService?.stopProcess(id)
    }

    fun stopAllProcesses() {
        txService?.stopAllProcesses()
    }

    // ── Activate bot by name ─────────────────────────────────
    fun activateBotByName(name: String) {
        val proc = txService?.getProcessByName(name)
        if (proc != null && proc.isRunning) {
            _uiState.update {
                it.copy(messages = it.messages + ChatMessage("assistant",
                    "✅ **$name** is already running.\nLog: `${proc.logFile}`"))
            }
        } else {
            sendMessage("Start the $name bot")
        }
    }

    // ── Send a message ───────────────────────────────────────
    /** Drop the last AI reply and re-run the last user message. */
    fun regenerate() {
        if (_uiState.value.isGenerating) return
        val msgs = _uiState.value.messages
        val idx = msgs.indexOfLast { it.role == "user" }
        if (idx < 0) return
        val lastUserText = msgs[idx].content
        val trimmed = msgs.subList(0, idx).toList()
        _uiState.update { it.copy(messages = trimmed, error = null) }
        sendMessage(lastUserText)
    }

    fun sendMessage(text: String) {
        val hasAttachment = !_uiState.value.pendingImageBase64.isNullOrBlank()
        if ((text.isBlank() && !hasAttachment) || _uiState.value.isGenerating) return

        // ── Tool test state machine intercept ────────────────
        val stage = _uiState.value.toolTestStage
        val tool  = _uiState.value.activeToolTest

        if (stage == ToolTestStage.WAITING_INPUT && tool != null) {
            val input    = text.trim()
            val userMsg  = ChatMessage("user", input)
            val runningMsg = ChatMessage("assistant", "⏳ Running tool...")
            val msgsNow  = _uiState.value.messages + userMsg + runningMsg
            _uiState.update {
                it.copy(
                    messages       = msgsNow,
                    currentInput   = "",
                    isGenerating   = true,
                    toolTestStage  = ToolTestStage.IDLE,
                    activeToolTest = null
                )
            }
            viewModelScope.launch {
                val command = if (input.isNotEmpty()) "${tool.runCommand} \"$input\"" else tool.runCommand
                val result  = termuxBridge.runCommandAndGetOutput(command, 30)
                val output  = result.output.trim().ifEmpty { "(no output received)" }
                val resultMsg = buildString {
                    appendLine("**🔧 ${tool.toolName} — Result:**")
                    appendLine("```")
                    appendLine("$ $command")
                    appendLine(output)
                    appendLine("```")
                    if (result.success) append("✅ Working correctly!")
                    else append("⚠️ Something looks wrong.")
                }
                // "running..." message replace karo result se
                val withResult = msgsNow.dropLast(1) + ChatMessage("assistant", resultMsg)
                _uiState.update { it.copy(messages = withResult, isGenerating = false) }
                val convId = _uiState.value.activeConvId
                if (convId.isNotEmpty()) {
                    historyManager.saveMessages(convId, withResult)
                    historyManager.touchConversation(convId)
                    refreshConversations()
                }
            }
            return
        }
        // ── Normal message flow ──────────────────────────────

        val pending = _uiState.value
        val userMsg = ChatMessage(
            role = "user",
            content = text.trim(),
            imageBase64 = pending.pendingImageBase64,
            imageMime = pending.pendingImageMime,
            fileName = pending.pendingFileName
        )
        val updatedMessages = _uiState.value.messages + userMsg

        _uiState.update {
            it.copy(
                messages = updatedMessages,
                currentInput = "",
                isGenerating = true,
                streamingText = "",
                error = null,
                pendingImageBase64 = null,
                pendingImageMime = null,
                pendingFileName = null
            )
        }

        currentAiJob?.cancel() // purana job cancel karo agar chal raha ho
        val job = viewModelScope.launch {
            val termuxLive = termuxBridge.isConnected()
            if (termuxLive != _uiState.value.termuxConnected)
                _uiState.update { it.copy(termuxConnected = termuxLive) }

            val deviceInfo = if (termuxLive) {
                try { termuxBridge.getDeviceInfo().toString(2) } catch (e: Exception) { "" }
            } else ""

            // Active processes info bhi system prompt mein do
            val activeProcs = _uiState.value.activeProcesses
            val procsInfo = if (activeProcs.isNotEmpty()) {
                activeProcs.joinToString("\n") { "- ${it.name} (id:${it.id}, log:${it.logFile})" }
            } else "None"

            val sb = StringBuilder()
            val candidates = buildFallbackCandidates()
            var lastError: String? = null
            var usedProvider: String? = null
            var usedModel: String? = null
            try {
                withTimeout(90_000L) {
                    for (cand in candidates) {
                        val spec = ProviderRegistry.byId(cand.first)
                        val key = prefs.apiKeyFor(cand.first).first()
                        llm.provider = spec
                        val localSb = StringBuilder()
                        var localErr: String? = null
                        llm.chatStream(
                            apiKey = key,
                            model = cand.second,
                            messages = updatedMessages,
                            systemPrompt = buildSystemPrompt(termuxLive, deviceInfo, procsInfo),
                            fileContext = _uiState.value.selectedFileContext
                        ).collect { token ->
                            ensureActive()
                            if (token.startsWith("[ERROR]")) localErr = token
                            else {
                                localSb.append(token)
                                _uiState.update { it.copy(streamingText = sb.toString() + localSb.toString()) }
                            }
                        }
                        if (localSb.isNotEmpty()) {
                            sb.append(localSb)
                            usedProvider = cand.first
                            usedModel = cand.second
                            break
                        }
                        lastError = localErr ?: "empty response"
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeout — jo bhi aaya tha use show karo
                val partial = sb.toString()
                if (partial.isNotEmpty()) {
                    val msgs = _uiState.value.messages + ChatMessage("assistant",
                        "$partial\n\n⚠️ _Response timed out (90s). Showing partial response._")
                    _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
                    saveConversation(msgs)
                } else {
                    _uiState.update { it.copy(isGenerating = false, streamingText = "",
                        error = "⏱️ Timeout — AI did not respond within 90 seconds. Please try again.") }
                }
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            }

            // Fallback ne jo model chalaya, usko session ke liye yaad rakho
            val okProvider = usedProvider
            val okModel = usedModel
            if (okProvider != null && okModel != null) {
                currentApiKey = prefs.apiKeyFor(okProvider).first()
                currentModel = okModel
                llm.provider = ProviderRegistry.byId(okProvider)
                _uiState.update { it.copy(currentModel = okModel) }
            }

            val aiResponse = sb.toString()
            if (aiResponse.isEmpty()) {
                _uiState.update { it.copy(isGenerating = false, streamingText = "",
                    error = (lastError ?: "No response") +
                        "\n\n_(Tried all available free models — add another provider key in Settings.)_") }
                return@launch
            }

            // ── PLAN MODE: never execute anything, just show the plan ──
            if (_uiState.value.agentMode == "plan") {
                val msgs = _uiState.value.messages + ChatMessage("assistant", cleanTags(aiResponse))
                _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
                saveConversation(msgs)
                return@launch
            }

            val fileCreated = tryCreateFileFromResponse(aiResponse)
            // Yeh check PEHLE hoga, chahe Termux online ho ya offline
            val webSearchRegex = Regex("""\[WEB_SEARCH:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val webSearchMatches = webSearchRegex.findAll(aiResponse).toList()

            if (webSearchMatches.isNotEmpty()) {
                // AI ne web search maanga — pehle search karo, phir dobara AI call karo
                handleWebSearchFlow(
                    aiResponse = aiResponse,
                    webSearchMatches = webSearchMatches,
                    updatedMessages = updatedMessages,
                    fileCreated = fileCreated,
                    termuxLive = termuxLive,
                    procsInfo = procsInfo
                )
                return@launch
            }

            // ── Termux actions (web search nahi hai) ────────────────
            val termuxRegex  = Regex("""\[TERMUX:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val installRegex = Regex("""\[INSTALL:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val writeRegex   = Regex("""<<<WRITE:(.*?)>>>(.*?)<<<END>>>""", RegexOption.DOT_MATCHES_ALL)
            val bgRegex      = Regex("""\[BG:(.*?)\|(.*?)\|(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val stopRegex    = Regex("""\[STOP:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)

            val hasTermuxActions = listOf(termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                .any { it.containsMatchIn(aiResponse) }

            if (termuxLive && hasTermuxActions) {
                handleAllActions(aiResponse, updatedMessages, fileCreated,
                    termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
            } else {
                // AI responded with text but used no action tags
                val claimedWork = listOf(
                    "kar diya", "create kar diya", "likh diya", "ban gaya",
                    "successfully", "install ho gaya", "start ho gaya", "chal raha hai",
                    "band kar", "stop kar", "karta hoon", "karo", "chalao", "kill kar",
                    "band ho", "verify karo", "check karo", "dikha do", "batao",
                    "done", "created", "written", "started", "stopped", "installed"
                ).any { aiResponse.contains(it, ignoreCase = true) }

                // ── AUTO-ENFORCE: AI described steps but used no tags — retry ──
                if (termuxLive && claimedWork && !hasTermuxActions) {
                    _uiState.update { it.copy(streamingText = "⚡ Executing commands...") }

                    val forceMsg = """
STOP. You only described what to do but used NO ACTION TAGS.

User's request was: "${updatedMessages.last().content}"

Your previous response was just planning — NO ACTUAL ACTION WAS TAKEN.

USE ACTION TAGS NOW. No explanations, just tags:

To stop a bot:
[STOP:Telegram Bot]
[TERMUX:pkill -9 -f telegram_bot.py 2>/dev/null; echo "Done"]
[TERMUX:pgrep -f telegram_bot && echo "STILL RUNNING" || echo "STOPPED"]

To check a process:
[TERMUX:ps aux | grep python]

To run any command — use [TERMUX:command] directly.

RULES:
- Tags first, explanation after
- Don't say "I will do it" — DO IT
- Every action requires a tag
""".trimIndent()

                    val messagesForRetry = updatedMessages +
                        ChatMessage("assistant", aiResponse) +
                        ChatMessage("user", forceMsg)

                    _uiState.update { it.copy(streamingText = "") }
                    val retrySb = StringBuilder()

                    try {
                        llm.chatStream(
                            apiKey = currentApiKey,
                            model = currentModel,
                            messages = messagesForRetry,
                            systemPrompt = buildSystemPrompt(true, "", procsInfo),
                            fileContext = ""
                        ).collect { token ->
                            ensureActive()
                            if (!token.startsWith("[ERROR]")) {
                                retrySb.append(token)
                                _uiState.update { it.copy(streamingText = retrySb.toString()) }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { return@launch }

                    val retryResponse = retrySb.toString()

                    // Did the retry produce action tags?
                    val retryHasActions = listOf(termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                        .any { it.containsMatchIn(retryResponse) }

                    if (retryHasActions) {
                        // Tags found in retry — execute them
                        handleAllActions(retryResponse, messagesForRetry, fileCreated,
                            termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                    } else {
                        // Still no tags — show response with warning
                        val warning = "\n\n⚠️ **AI could not execute commands automatically.** " +
                            "Run manually in Termux:\n```\npkill -9 -f telegram_bot.py\n```"
                        val finalMsgs = _uiState.value.messages +
                            ChatMessage("assistant", cleanTags(retryResponse.ifEmpty { aiResponse }) + warning)
                        _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
                        saveConversation(finalMsgs)
                    }
                    return@launch
                }

                // Normal response (no claimed work, just info/chat)
                val display = if (fileCreated != null) {
                    "${cleanTags(aiResponse)}\n\n✅ File created: **$fileCreated**"
                } else {
                    aiResponse
                }

                val finalMsgs = _uiState.value.messages + ChatMessage("assistant", display)
                _uiState.update {
                    it.copy(
                        messages = finalMsgs,
                        isGenerating = false, streamingText = ""
                    )
                }
                // History save karo
                val convId = _uiState.value.activeConvId
                if (convId.isNotEmpty()) {
                    historyManager.saveMessages(convId, finalMsgs)
                    val convs = historyManager.getAllConversations()
                    val conv  = convs.firstOrNull { it.id == convId }
                    if (conv != null && conv.title == "New Chat" && finalMsgs.size >= 2) {
                        historyManager.updateConversationTitle(convId, historyManager.generateTitle(finalMsgs))
                    }
                    historyManager.touchConversation(convId)
                    refreshConversations()
                }
            }
        }

        currentAiJob = job
        txService?.setCurrentAiJob(job)
        txService?.setGenerating(true)
    }

    // ── WEB SEARCH FLOW — Termux se independent ──────────────────
    // 1. AI ne [WEB_SEARCH:query] tag use kiya
    // 2. Hum search karte hain (await — result aane tak wait)
    // 3. Results ke saath AI ko dobara call karte hain
    // 4. AI real results padh ke sahi jawab deta hai
    private suspend fun handleWebSearchFlow(
        aiResponse: String,
        webSearchMatches: List<MatchResult>,
        updatedMessages: List<ChatMessage>,
        fileCreated: String?,
        termuxLive: Boolean,
        procsInfo: String
    ) {
        // Step 1: Show "Searching..." status
        val cleanedResponse = cleanTags(aiResponse).trim()
        val searchingMsg = if (cleanedResponse.isNotEmpty())
            "$cleanedResponse\n\n🔍 **Searching the web...**"
        else
            "🔍 **Searching the web...**"
        _uiState.update { it.copy(streamingText = searchingMsg) }

        // Step 2: Run all searches
        val allSearchResults = StringBuilder()
        webSearchMatches.forEach { match ->
            val query = match.groupValues[1].trim()
            android.util.Log.d("ChatVM", "WebSearch executing: $query")
            _uiState.update { it.copy(streamingText = "$searchingMsg\n🔎 Searching: \"$query\"") }

            val results = webSearch.search(query, 5)
            val formatted = webSearch.formatForAI(query, results)
            allSearchResults.appendLine(formatted)
            allSearchResults.appendLine()
            android.util.Log.d("ChatVM", "WebSearch done: $query — ${results.size} results")
        }

        // Step 3: If no results found
        if (allSearchResults.toString().contains("Koi result nahi mila") &&
            !allSearchResults.toString().contains("=== WEB SEARCH RESULTS ===")) {
            val noResultMsg = "🔍 Web search completed but no results were found. Please try a different query."
            val finalMsgs = (_uiState.value.messages) + ChatMessage("assistant", noResultMsg)
            _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
            saveConversation(finalMsgs)
            return
        }

        // Step 4: Call AI again with search results as context
        val searchContext = """
These are the real-time web search results you requested via [WEB_SEARCH:...]:

$allSearchResults

Now answer the user based on these ACTUAL real-time results.
- Report what the search results say — do not assume dates or years
- Do NOT say "I cannot predict the future" — these results are current
- Do NOT say "my knowledge cutoff..." — these results are live
- Answer clearly and confidently
""".trimIndent()

        val messagesForSecondCall = updatedMessages +
            ChatMessage("assistant", cleanedResponse.ifEmpty { "[Performing web search]" }) +
            ChatMessage("user", searchContext)

        _uiState.update { it.copy(streamingText = "") }
        val finalSb = StringBuilder()

        try {
            llm.chatStream(
                apiKey = currentApiKey,
                model = currentModel,
                messages = messagesForSecondCall,
                systemPrompt = buildSystemPrompt(termuxLive, "", procsInfo),
                fileContext = ""
            ).collect { token ->
                if (!token.startsWith("[ERROR]")) {
                    finalSb.append(token)
                    _uiState.update { it.copy(streamingText = finalSb.toString()) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return
        }

        val finalText = finalSb.toString().ifEmpty {
            "$cleanedResponse\n\n**Search Results:**\n$allSearchResults"
        }
        val withFile = if (fileCreated != null) "$finalText\n\n✅ File bana di: **$fileCreated**" else finalText

        val finalMsgs = _uiState.value.messages + ChatMessage("assistant", withFile)
        _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
        txService?.setGenerating(false)
        saveConversation(finalMsgs)
    }

    // ── Progress banner — plain Unicode, no markdown backticks ──
    private fun updateProgress(
        completedSteps: List<String>,
        currentStep: String,
        totalSteps: Int,
        stepIndex: Int
    ) {
        val filled = if (totalSteps > 0) (stepIndex * 8 / totalSteps.coerceAtLeast(1)) else 0
        val bar    = "█".repeat(filled) + "░".repeat(8 - filled)
        val done   = completedSteps.takeLast(3).joinToString("\n")
        val banner = buildString {
            appendLine("⚡ Executing... [$bar] $stepIndex/$totalSteps")
            if (done.isNotEmpty()) { appendLine(); appendLine(done) }
            appendLine(); append("⏳ Now: $currentStep")
        }
        _uiState.update { it.copy(streamingText = banner) }
    }

    private fun cleanTags(response: String): String = response
        .replace(Regex("""\[FILE:.*?\]"""), "")
        .replace(Regex("""\[CONTENT\].*?\[/CONTENT\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[TERMUX:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[INSTALL:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<<<WRITE:.*?>>>.*?<<<END>>>""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[BG:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[STOP:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[TOOL:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[WEB_SEARCH:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        .trim()

    private fun tryCreateFileFromResponse(response: String): String? {
        return try {
            val fileName = Regex("""\[FILE:(.*?)\]""").find(response)?.groupValues?.get(1)?.trim() ?: return null
            val content  = Regex("""\[CONTENT\](.*?)\[/CONTENT\]""", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.groupValues?.get(1)?.trim() ?: return null
            val result = fileManager.writeFile(fileName, content)
            if (result.isSuccess) fileName else null
        } catch (e: Exception) { null }
    }

    private suspend fun handleAllActions(
        aiResponse: String,
        previousMessages: List<ChatMessage>,
        fileCreated: String?,
        termuxRegex: Regex,
        installRegex: Regex,
        writeRegex: Regex,
        bgRegex: Regex,
        stopRegex: Regex
    ) {
        val actionResults  = StringBuilder()

        // Pre-count steps for progress bar
        val stopList    = stopRegex.findAll(aiResponse).toList()
        val installList = installRegex.findAll(aiResponse).toList()
        val writeList   = writeRegex.findAll(aiResponse).toList()
        val bgList      = bgRegex.findAll(aiResponse).toList()
        val termuxList  = termuxRegex.findAll(aiResponse).toList()
        val totalSteps  = stopList.size + installList.size + writeList.size +
                          bgList.size + termuxList.size
        var stepsDone   = 0
        val completedSteps = mutableListOf<String>()
        if (totalSteps > 0) {
            _uiState.update { it.copy(streamingText = "⚡ Starting... [░░░░░░░░] 0/$totalSteps") }
        }

        // 0b. Tool detect karo — [TOOL:filepath|run_command|arg_hint|tool_name]
        val toolRegex = Regex("""\[TOOL:(.*?)\|(.*?)\|(.*?)\|(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val toolMatch = toolRegex.find(aiResponse)
        if (toolMatch != null) {
            val tool = ActiveToolTest(
                filePath   = toolMatch.groupValues[1].trim(),
                runCommand = toolMatch.groupValues[2].trim(),
                argHint    = toolMatch.groupValues[3].trim(),
                toolName   = toolMatch.groupValues[4].trim()
            )
            val needsInput = tool.argHint.isNotBlank()
            if (needsInput) {
                val askMsg = ChatMessage(
                    "assistant",
                    "\uD83D\uDD27 **${tool.toolName}** is ready!\nProvide input to test: **${tool.argHint}**"
                )
                _uiState.update {
                    it.copy(
                        messages       = it.messages + askMsg,
                        toolTestStage  = ToolTestStage.WAITING_INPUT,
                        activeToolTest = tool
                    )
                }
            } else {
                // No input needed — run directly
                val runMsg = ChatMessage("assistant", "\uD83D\uDD27 **${tool.toolName}** is being tested...")
                _uiState.update { it.copy(messages = it.messages + runMsg) }
                val result = termuxBridge.runCommandAndGetOutput(tool.runCommand, 30)
                val output = result.output.trim().ifEmpty { "(no output received)" }
                val resultMsg = buildString {
                    appendLine("**\uD83D\uDD27 ${tool.toolName} — Test Result:**")
                    appendLine("```")
                    appendLine("$ ${tool.runCommand}")
                    appendLine(output)
                    appendLine("```")
                    if (result.success) append("\u2705 Tool is working correctly!")
                    else append("\u26A0\uFE0F Something looks wrong.")
                }
                val prevMsgs = _uiState.value.messages
                val updatedMsgs = prevMsgs.dropLast(1) + ChatMessage("assistant", resultMsg)
                _uiState.update { it.copy(messages = updatedMsgs) }
            }
        }

        // 1. Stop commands
        stopList.forEach { match ->
            val nameOrId = match.groupValues[1].trim()
            updateProgress(completedSteps, "⏹ Stopping: $nameOrId", totalSteps, stepsDone)
            val proc = txService?.getProcessByName(nameOrId)
                ?: _uiState.value.activeProcesses.find { it.id == nameOrId }
            val line = if (proc != null) {
                txService?.stopProcess(proc.id); "⏹ Stopped: ${proc.name}"
            } else "⚠ Not found: $nameOrId"
            actionResults.appendLine(line).appendLine()
            completedSteps.add(line); stepsDone++
        }

        // 2. Packages install
        installList.forEach { match ->
            val pkg = match.groupValues[1].trim()
            updateProgress(completedSteps, "📦 Installing: $pkg (may take 1-5 min)...", totalSteps, stepsDone)
            // Live progress update every 15s so user knows it's working
            val progressJob = viewModelScope.launch {
                val dots = listOf("⏳", "⌛")
                var i = 0
                while (true) {
                    delay(15_000)
                    val filled3 = if (totalSteps > 0) (stepsDone * 8 / totalSteps.coerceAtLeast(1)) else 0
                    val bar3 = "█".repeat(filled3) + "░".repeat(8 - filled3)
                    _uiState.update {
                        it.copy(streamingText = "${dots[i % 2]} Installing $pkg... [$bar3] $stepsDone/$totalSteps\n(Large packages like ffmpeg can take 2-5 minutes)")
                    }
                    i++
                }
            }
            actionResults.appendLine("📦 Installing: $pkg")
            val result = termuxBridge.installPackage(pkg)
            progressJob.cancel()  // Stop progress animation
            val line = if (result.success) "✅ $pkg installed" else "❌ $pkg failed: ${result.output.take(80)}"
            actionResults.appendLine(result.output.take(200))  // Already trimmed by TermuxBridge
            actionResults.appendLine()
            completedSteps.add(line); stepsDone++
            updateProgress(completedSteps, line, totalSteps, stepsDone)
        }

        // 3. File write
        writeList.forEach { match ->
            val path    = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()
            updateProgress(completedSteps, "📝 Writing: $path", totalSteps, stepsDone)
            val ok   = termuxBridge.writeFile(path, content)
            val line = if (ok) "✅ Written: $path" else "❌ Write failed: $path"
            actionResults.appendLine("📝 Writing: $path")
            actionResults.appendLine(if (ok) "✅ Written" else "❌ Failed").appendLine()
            completedSteps.add(line); stepsDone++
        }

        // 4. Background processes — PID capture for reliable stop
        bgList.forEach { match ->
            val name    = match.groupValues[1].trim()
            val command = match.groupValues[2].trim()
            val logFile = match.groupValues[3].trim()
            val id      = UUID.randomUUID().toString().take(8)
            updateProgress(completedSteps, "🚀 Starting: $name", totalSteps, stepsDone)
            val result = termuxBridge.runCommandAndGetOutput(
                "nohup $command >> $logFile 2>&1 & BG_PID=\$!; echo PID:\$BG_PID", 10
            )
            val pid = Regex("PID:(\\d+)").find(result.output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            txService?.registerProcess(id, name, command, logFile, pid)
            val pidInfo = if (pid > 0) " PID:$pid" else ""
            val line = "🚀 $name started$pidInfo"
            actionResults.appendLine("🚀 Starting bg: $name")
            actionResults.appendLine("✅ Started (id:$id$pidInfo) Log: $logFile").appendLine()
            completedSteps.add(line); stepsDone++
        }

        // 5. Terminal commands — LIVE streaming output
        termuxList.forEach { match ->
            val cmd      = match.groupValues[1].trim()
            val shortCmd = if (cmd.length > 50) cmd.take(47) + "..." else cmd
            updateProgress(completedSteps, "$ $shortCmd", totalSteps, stepsDone)
            addDebugLog(DebugLogType.COMMAND, "$ $cmd")

            val liveLines = mutableListOf<String>()
            val result = termuxBridge.runCommandWithLiveOutput(cmd, 120) { newLines ->
                liveLines.addAll(newLines)
                val visible  = liveLines.takeLast(6)
                val filled2  = if (totalSteps > 0) (stepsDone * 8 / totalSteps.coerceAtLeast(1)) else 0
                val bar2     = "█".repeat(filled2) + "░".repeat(8 - filled2)
                val donePart = completedSteps.takeLast(2).joinToString("\n")
                val liveBanner = buildString {
                    appendLine("⚡ Running... [$bar2] $stepsDone/$totalSteps")
                    appendLine("$ $shortCmd")
                    if (donePart.isNotEmpty()) { appendLine(); appendLine(donePart) }
                    appendLine(); appendLine("── Output ──")
                    visible.forEach { appendLine("  $it") }
                }
                _uiState.update { it.copy(streamingText = liveBanner) }
            }

            val rawOutput    = result.output
            val parsedOutput = if (rawOutput.lines().size > 8) smartParseOutput(cmd, rawOutput) else rawOutput
            addDebugLog(
                type      = if (result.success) DebugLogType.OUTPUT else DebugLogType.ERROR,
                title     = if (result.success) "✅ Output" else "❌ Error",
                detail    = rawOutput,
                isSuccess = result.success
            )
            completedSteps.add("${if (result.success) "✅" else "❌"} $ $shortCmd")
            actionResults.appendLine("$ $cmd").appendLine(parsedOutput).appendLine()
            stepsDone++
        }

        val cleanResponse = cleanTags(aiResponse).trim()

        // All done — show summary before AI call
        if (totalSteps > 0) {
            val summaryBanner = buildString {
                appendLine("✅ All done! [████████] $stepsDone/$totalSteps")
                appendLine()
                completedSteps.forEach { appendLine(it) }
                appendLine(); append("🤖 AI summarizing...")
            }
            _uiState.update { it.copy(streamingText = summaryBanner) }
        }

        val hasError = actionResults.contains("Error") ||
                       actionResults.contains("Traceback") ||
                       actionResults.contains("error:") ||
                       actionResults.contains("Failed") ||
                       actionResults.contains("not found")
        val resultFeedback = if (hasError) {
            "Results:\n```\n$actionResults\n```\n" +
            "An ERROR is visible. Analyze it: what failed? " +
            "ModuleNotFoundError → install it. SyntaxError → fix the code. " +
            "Connection error → retry. Fix and report what you did."
        } else {
            "Results:\n```\n$actionResults\n```\n" +
            "Report what was done. If a process was started, include its name and log file."
        }

        // Debug log — AI ko kya bheja ja raha hai
        addDebugLog(DebugLogType.AI_SEND, "→ AI ko results bheje", resultFeedback.take(300))
        val messagesWithResult = previousMessages +
            ChatMessage("assistant", cleanResponse.ifEmpty { "Executing..." }) +
            ChatMessage("user", resultFeedback)

        _uiState.update { it.copy(streamingText = "") }
        val finalSb = StringBuilder()

        llm.chatStream(
            apiKey = currentApiKey,
            model = currentModel,
            messages = messagesWithResult,
            systemPrompt = buildSystemPrompt(true, "", ""),
            fileContext = ""
        ).collect { token ->
            if (!token.startsWith("[ERROR]")) {
                finalSb.append(token)
                _uiState.update { it.copy(streamingText = finalSb.toString()) }
            }
        }

        val finalText = finalSb.toString().ifEmpty {
            "$cleanResponse\n\n**Results:**\n```\n$actionResults\n```"
        }
        // Debug log — AI ka final response
        addDebugLog(DebugLogType.AI_RECV, "← AI response", finalText.take(200))
        val withFile = if (fileCreated != null) "$finalText\n\n✅ App file: **$fileCreated**" else finalText

        // Notification bhejo
        txService?.sendNotification("✅ Task Complete", cleanResponse.take(60).ifEmpty { "Kaam ho gaya" })
        txService?.setGenerating(false)

        val actionFinalMsgs = _uiState.value.messages + ChatMessage("assistant", withFile)
        _uiState.update {
            it.copy(messages = actionFinalMsgs, isGenerating = false, streamingText = "")
        }
        saveConversation(actionFinalMsgs)
    }

    // ── Helper: conversation save + title update ─────────────────
    private fun saveConversation(msgs: List<ChatMessage>) {
        val convId = _uiState.value.activeConvId
        if (convId.isEmpty()) return
        viewModelScope.launch {
            historyManager.saveMessages(convId, msgs)
            val convs = historyManager.getAllConversations()
            val conv  = convs.firstOrNull { it.id == convId }
            if (conv != null && conv.title == "New Chat" && msgs.size >= 2) {
                historyManager.updateConversationTitle(convId, historyManager.generateTitle(msgs))
            }
            historyManager.touchConversation(convId)
            refreshConversations()
        }
    }

    // ── Auto-fallback: ordered (providerId, modelId) candidates ──
    private suspend fun buildFallbackCandidates(): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val pid = prefs.providerId.first()
        val model = prefs.modelFor(pid).first()
        if (prefs.apiKeyFor(pid).first().isNotBlank() && model.isNotBlank()) out.add(pid to model)
        val catalogJson = prefs.modelCatalog.first()
        if (catalogJson.isNotBlank()) {
            try {
                val root = JSONObject(catalogJson)
                root.keys().forEach { p ->
                    if (prefs.apiKeyFor(p).first().isNotBlank()) {
                        val arr = root.optJSONArray(p)
                        if (arr != null) for (i in 0 until arr.length()) {
                            val m = arr.optString(i)
                            if (m.isNotBlank()) out.add(p to m)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        if (out.isEmpty() && currentModel.isNotBlank()) out.add(prefs.providerId.first() to currentModel)
        return out.toList()
    }

    private fun buildSystemPrompt(termuxConnected: Boolean, deviceInfo: String, activeProcs: String): String {
        val base = currentSystemPrompt.ifBlank {
            "You are Featurestic AI Terminal, an elite AI assistant and terminal controller running on Android. Be direct and execute tasks immediately. Keep replies short and clean — never send long, unnecessary messages."
        }

        val enabledSkills = _uiState.value.skills.filter { it.enabled }
        val skillsBlock = if (enabledSkills.isEmpty()) "" else
            "\n\n=== ACTIVE SKILLS (follow strictly) ===\n" +
                enabledSkills.joinToString("\n") { "- ${it.instruction}" }

        val modeBlock = if (_uiState.value.agentMode == "plan") {
            "\n\n=== MODE: PLAN ===\nYou are in PLAN mode. Do NOT execute anything and do NOT output any action tags " +
                "([TERMUX:], [INSTALL:], <<<WRITE:...>>>, [BG:], [STOP:]). Reply with only a short, clean plan."
        } else {
            "\n\n=== MODE: BUILD ===\nYou may execute actions with action tags when needed. Keep the reply short and clean."
        }

        // ── Web Search — ALWAYS available ──────────────────────────
        val webSearchSection = """

╔══════════════════════════════════════════╗
║         WEB SEARCH — REAL-TIME           ║
╚══════════════════════════════════════════╝
Format: [WEB_SEARCH:query]

WHEN TO USE:
→ News, latest updates, current events → [WEB_SEARCH:India news today 2026]
→ Prices (crypto, stocks) → [WEB_SEARCH:bitcoin price today]
→ Technical errors → [WEB_SEARCH:python telebot error fix]
→ Anything that requires current/live information

HARD RULES:
✗ NEVER say "I cannot access the internet"
✗ NEVER say "my knowledge cutoff is..."
✓ Use [WEB_SEARCH:] — real results will be fetched
"""

        // ── App File Manager ───────────────────────────────────────
        val fileSection = """
╔══════════════════════════════════════════╗
║           APP FILE MANAGER               ║
╚══════════════════════════════════════════╝
[FILE:filename.ext]
[CONTENT]
...file content...
[/CONTENT]
"""

        if (!termuxConnected) {
            return base + skillsBlock + modeBlock + webSearchSection + fileSection + """

⚠ STATUS: Termux OFFLINE
→ Web Search and File creation are available.
→ Termux commands are NOT available.
"""
        }

        val deviceSection = if (deviceInfo.isNotEmpty()) "\n── Device Info ──\n$deviceInfo\n" else ""
        val procsSection  = if (activeProcs.isNotBlank() && activeProcs != "None")
            "\n── Active Processes ──\n$activeProcs\n" else ""

        return base + skillsBlock + modeBlock + webSearchSection + fileSection + deviceSection + procsSection + """

╔══════════════════════════════════════════╗
║     TERMUX DIRECT CONTROL — ONLINE       ║
╚══════════════════════════════════════════╝

TERMUX IS ONLINE. You can run commands directly.
NEVER say "I cannot do this." DO IT.

━━━ CRITICAL ANTI-LIE RULES ━━━

✗✗✗ NEVER MAKE FALSE CLAIMS ✗✗✗
→ Only say "stopped" if you actually used [STOP:name]
→ Only say "started" if you actually used [BG:...]
→ Only say "command ran" if you actually used [TERMUX:...]
→ Just talking = lying. DO the action, then report it.

HOW TO STOP A PROCESS:
→ Stop process: [STOP:Telegram Bot]
→ Also run these to confirm:
   [TERMUX:pkill -9 -f telegram_bot.py 2>/dev/null; echo "Kill sent"]
   [TERMUX:pgrep -f telegram_bot && echo "STILL RUNNING" || echo "CONFIRMED STOPPED"]
→ Process name must match EXACTLY what was used in [BG:...]

⚡ TEMPLATE TO STOP SOMETHING:
[STOP:Process Name]
[TERMUX:pkill -9 -f script_name.py 2>/dev/null; echo done]
[TERMUX:pgrep -f script_name && echo RUNNING || echo STOPPED]

━━━ ACTIONS (use exact format) ━━━

① RUN A COMMAND:
[TERMUX:command here]
e.g. [TERMUX:python3 --version]
e.g. [TERMUX:ls ~/]
e.g. [TERMUX:pip install pyTelegramBotAPI]

② INSTALL A PACKAGE:
[INSTALL:package_name]
e.g. [INSTALL:python]

③ WRITE A FILE (with FULL content):
<<<WRITE:~/filepath.py>>>
...full file content here...
<<<END>>>
⚠ REQUIRED: Full code must be between <<<WRITE:>>> and <<<END>>>.
⚠ Do NOT leave content empty — the file will be broken.

④ START A BACKGROUND PROCESS:
[BG:Process Name|command|~/logfile.log]
e.g. [BG:Telegram Bot|python3 ~/telegram_bot.py|~/bot.log]

⑤ STOP A PROCESS:
[STOP:Process Name]

⑥ TEST A TOOL:
[TOOL:~/filepath|run_command|arg_hint|Tool Name]
arg_hint EMPTY = auto-run, FILLED = ask user for input

━━━ HOW TO BUILD A TELEGRAM BOT — EXACT STEPS ━━━

When user asks to create a telegram bot, do ALL of this in one response:

STEP 1 — Packages install:
[INSTALL:python]
[TERMUX:pip install pyTelegramBotAPI requests]

STEP 2 — Write the bot file (use TOKEN if provided, otherwise use YOUR_TOKEN_HERE):
<<<WRITE:~/telegram_bot.py>>>
import telebot, os, time, logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s %(message)s',
    handlers=[
        logging.FileHandler(os.path.expanduser('~/bot.log')),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

BOT_TOKEN = "YOUR_TOKEN_HERE"
bot = telebot.TeleBot(BOT_TOKEN)

@bot.message_handler(commands=['start'])
def start(msg):
    bot.reply_to(msg, "Hello! I am Featurestic AI Bot. Type /help for commands.")

@bot.message_handler(commands=['help'])
def help_cmd(msg):
    bot.reply_to(msg, "/start - Start\n/help - Help\n/time - Time")

@bot.message_handler(commands=['time'])
def time_cmd(msg):
    import datetime
    bot.reply_to(msg, f"Current time: {datetime.datetime.now().strftime('%H:%M:%S')}")

@bot.message_handler(func=lambda m: True)
def echo(msg):
    bot.reply_to(msg, f"You said: {msg.text}")

if __name__ == "__main__":
    logger.info("Bot is starting...")
    while True:
        try:
            bot.polling(none_stop=True, timeout=60, long_polling_timeout=60)
        except Exception as e:
            logger.error(f"Crash: {e}")
            time.sleep(5)
<<<END>>>

STEP 3 — Set token (if user provided one):
[TERMUX:sed -i 's/YOUR_TOKEN_HERE/USER_KA_TOKEN/' ~/telegram_bot.py]

STEP 4 — Test it:
[TERMUX:timeout 15 python3 ~/telegram_bot.py 2>&1]

STEP 5 — Start in background:
[BG:Telegram Bot|python3 ~/telegram_bot.py|~/bot.log]

STEP 6 — Check logs:
[TERMUX:sleep 3 && tail -20 ~/bot.log]

━━━ TERMUX — COMPLETE COMMAND REFERENCE ━━━

📁 FILES & DIRECTORIES:
ls -la ~/              → list all files (including hidden)
cd ~/scripts           → navigate to folder
mkdir -p ~/myfolder    → create folder
rm -rf ~/file.txt      → delete file (CAREFUL!)
cp ~/a.py ~/b.py       → copy file
mv ~/old.py ~/new.py   → rename/move
cat ~/file.txt         → read file
head -20 ~/file.txt    → first 20 lines
tail -50 ~/bot.log     → last 50 lines (great for logs)
tail -f ~/bot.log      → follow live log output
wc -l ~/file.py        → count lines
find ~/ -name "*.py"   → find all Python files
du -sh ~/              → disk usage
df -h                  → total storage
chmod +x ~/script.sh   → make file executable

🐍 PYTHON:
python3 --version                    → Python version
python3 ~/script.py                  → run a script
python3 -c "print('hello')"         → quick inline command
pip install requests telebot flask   → install packages
pip list                             → list installed packages
pip uninstall package_name           → uninstall
pip show package_name                → package info
python3 -m venv ~/myenv              → create virtual env
source ~/myenv/bin/activate          → activate venv

📦 PACKAGE MANAGEMENT:
pkg update && pkg upgrade            → update system
pkg list-installed                   → installed packages
apt list --installed                 → apt packages

⚠️ INSTALL TAG RULES (IMPORTANT):
- Use [INSTALL:package_name] for ALL pkg installs — runs silently, no output flood
- For large packages (ffmpeg, nodejs, python, imagemagick): ALWAYS use [INSTALL:pkg]
- NEVER use [TERMUX:pkg install ...] — floods output with 500+ lines and breaks AI
- Multiple packages: separate tags → [INSTALL:python] then [INSTALL:ffmpeg]
- [INSTALL:] auto-trims output — you get clean ✅ installed or ❌ failed result

🔄 PROCESS MANAGEMENT:
ps aux                               → list all processes
ps aux | grep python                 → filter python processes
pgrep -f telegram_bot                → get PID of specific process
kill PID                             → stop process (gentle)
kill -9 PID                          → force kill
pkill -f script_name                 → kill by name
pkill -9 -f script_name              → force kill by name
jobs                                 → list background jobs
nohup python3 ~/bot.py &             → run in background

📊 SYSTEM INFO:
uname -a                             → system info
free -h                              → RAM usage
top                                  → live system monitor (q to quit)
uptime                               → system uptime
whoami                               → current user
echo ${'$'}HOME                      → home directory
env                                  → environment variables
echo ${'$'}PATH                      → PATH variable

🌐 NETWORK:
curl https://api.example.com         → GET request
curl -X POST -d '{}' URL            → POST request
wget https://file.com/file.zip      → download file
ping -c 4 google.com                 → check network
netstat -tulpn                       → open ports
ssh user@ip                          → SSH connect
scp file.txt user@ip:~/             → transfer file via SSH

📝 TEXT PROCESSING:
grep "error" ~/bot.log               → search text
grep -r "TODO" ~/scripts/           → recursive search
sed -i 's/OLD/NEW/g' ~/file.py      → find & replace in file
awk '{print ${'$'}1}' ~/file.txt     → extract column
sort ~/file.txt                      → sort file
uniq                                 → remove duplicates
wc -l ~/file.txt                    → count lines
cut -d',' -f1 ~/data.csv           → extract CSV column

🗃️ ARCHIVE:
zip -r backup.zip ~/scripts/        → create zip
unzip backup.zip                     → extract zip
tar -czf backup.tar.gz ~/scripts/  → create tar.gz
tar -xzf backup.tar.gz              → extract tar.gz

⚙️ SCHEDULING (cron):
crontab -e                           → edit cron jobs
crontab -l                           → list current crons
# Format: minute hour day month weekday command
# 0 9 * * * python3 ~/script.py    → daily at 9am

🔐 ENVIRONMENT VARIABLES:
export MY_TOKEN="abc123"             → set variable
echo ${'$'}MY_TOKEN                  → read variable
echo 'export TOKEN="xyz"' >> ~/.bashrc  → save permanently

📡 GIT:
git clone https://github.com/repo   → clone repo
git status                           → view changes
git add .                            → stage all changes
git commit -m "message"              → commit
git push                             → push to remote
git pull                             → pull latest

🤖 WHATSAPP BOT (Node.js + whatsapp-web.js):
━━━ HOW TO BUILD A WHATSAPP BOT — EXACT STEPS ━━━

STEP 1 — Setup:
[INSTALL:nodejs]
[TERMUX:mkdir -p ~/whatsapp_bot && cd ~/whatsapp_bot && npm init -y && npm install whatsapp-web.js qrcode-terminal]

STEP 2 — Write the bot file:
<<<WRITE:~/whatsapp_bot/bot.js>>>
const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require('qrcode-terminal');

const client = new Client({
    authStrategy: new LocalAuth(),
    puppeteer: { args: ['--no-sandbox'] }
});

client.on('qr', qr => {
    console.log('Scan this QR code in WhatsApp:');
    qrcode.generate(qr, { small: true });
});

client.on('ready', () => {
    console.log('WhatsApp Bot ready!');
});

client.on('message', async msg => {
    if (msg.body === '!help') {
        msg.reply('Commands:\n!help - Help\n!ping - Pong\n!time - Time');
    } else if (msg.body === '!ping') {
        msg.reply('Pong! 🏓');
    } else if (msg.body === '!time') {
        msg.reply('Time: ' + new Date().toLocaleString());
    }
});

client.initialize();
<<<END>>>

STEP 3 — Start it (you will need to scan a QR code):
[TERMUX:cd ~/whatsapp_bot && node bot.js]

STEP 4 — Run in background (after QR scan):
[BG:WhatsApp Bot|cd ~/whatsapp_bot && node bot.js|~/whatsapp_bot/bot.log]

🔧 DEBUGGING:
python3 -c "import traceback; ..." → quick debug
python3 -m py_compile ~/script.py  → syntax check without running
strace -p PID                        → trace process
lsof -i :8080                        → who is using port 8080

💡 USEFUL TRICKS:
echo "hello" >> ~/file.txt          → append to file
> ~/file.txt                        → empty a file
command1 && command2                 → run command2 only if command1 succeeds
command1 || command2                 → run command2 only if command1 fails
command1 | command2                  → pipe output
command > output.txt 2>&1           → save stdout+stderr
sleep 5 && command                  → run after 5 seconds
timeout 30 command                  → run with 30 second limit
watch -n 5 command                  → repeat every 5 seconds

━━━ GOLDEN RULES — NEVER VIOLATE ━━━

✗ "I wrote the file" — NEVER say this unless you used <<<WRITE:>>>
✗ "Bot is created" — NEVER say this unless steps actually ran
✗ "Token is invalid" — NEVER say this without testing first
✗ "I used [STOP:name]" — NEVER say this without actually using the tag
✓ Do the work FIRST, then report what you did
✓ If an error occurs: check the LOG, fix it, retry
✓ To stop a process: ALWAYS use the [STOP:Exact Name] tag

━━━ SELF-HEALING ━━━

If an error occurs:
1. [TERMUX:tail -30 ~/bot.log]
2. Read the error
3. ModuleNotFoundError → [TERMUX:pip install MODULE_NAME]
4. SyntaxError → fix the code, update with <<<WRITE:>>>
5. Token error → ask user for a new token
6. After fix: [STOP:Telegram Bot] then [BG:Telegram Bot|python3 ~/telegram_bot.py|~/bot.log]

STATUS: Termux ONLINE ✓ | Web Search ONLINE ✓ | Self-Heal ACTIVE ✓
"""
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList(), streamingText = "", error = null) }
        val convId = _uiState.value.activeConvId
        if (convId.isNotEmpty()) {
            viewModelScope.launch {
                historyManager.saveMessages(convId, emptyList())
            }
        }
    }

    fun clearDebugLogs() {
        _uiState.update { it.copy(debugLogs = emptyList()) }
    }

    private fun addDebugLog(type: DebugLogType, title: String, detail: String = "", isSuccess: Boolean = true) {
        val entry = DebugLogEntry(type = type, title = title, detail = detail, isSuccess = isSuccess)
        // viewModelScope.launch ensures UI thread update
        viewModelScope.launch {
            _uiState.update { it.copy(debugLogs = (it.debugLogs + entry).takeLast(200)) }
        }
    }

    // ── Smart Error Parser ────────────────────────────────────
    // 1000 line output → 3-4 line summary. AI ko sirf yeh padna hoga.
    private fun smartParseOutput(cmd: String, rawOutput: String): String {
        val lines = rawOutput.lines().filter { it.isNotBlank() }
        if (lines.size <= 8) return rawOutput  // Chhota output — as-is bhejo

        // Error patterns — sirf relevant lines nikaalo
        val errorPatterns = listOf(
            "Traceback", "Error:", "error:", "FAILED", "Exception",
            "Warning:", "WARNING", "ModuleNotFoundError", "ImportError",
            "SyntaxError", "PermissionError", "FileNotFoundError",
            "ConnectionError", "TimeoutError", "KeyError", "ValueError",
            "CRITICAL", "fatal", "Cannot", "Unable to", "No such file"
        )
        val successPatterns = listOf(
            "Successfully installed", "successfully", "OK", "done", "complete",
            "started", "running", "connected", "✓", "PASSED"
        )

        // Error lines collect karo (context ke saath)
        val errorLines = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (errorPatterns.any { line.contains(it, ignoreCase = true) }) {
                // 1 line context upar-neeche
                if (i > 0) errorLines.add(lines[i - 1])
                errorLines.add(line)
                if (i < lines.size - 1) errorLines.add(lines[i + 1])
            }
        }

        // Success lines collect karo
        val successLines = lines.filter { line ->
            successPatterns.any { line.contains(it, ignoreCase = true) }
        }

        // Last 3 lines — hamesha useful hoti hain
        val lastLines = lines.takeLast(3)

        return buildString {
            appendLine("📊 Output Summary (${lines.size} lines → compressed):")
            if (errorLines.isNotEmpty()) {
                appendLine("❌ ERRORS FOUND:")
                errorLines.distinct().take(8).forEach { appendLine("  $it") }
            }
            if (successLines.isNotEmpty() && errorLines.isEmpty()) {
                appendLine("✅ SUCCESS:")
                successLines.take(4).forEach { appendLine("  $it") }
            }
            if (errorLines.isEmpty() && successLines.isEmpty()) {
                appendLine("📄 Last output:")
                lastLines.forEach { appendLine("  $it") }
            }
            appendLine("─── Full cmd: $cmd")
        }.trim()
    }

    // ── History Functions ────────────────────────────────────

    fun newConversation() {
        viewModelScope.launch {
            val conv = historyManager.createConversation("New Chat")
            prefs.saveActiveConvId(conv.id)
            refreshConversations()
            _uiState.update {
                it.copy(
                    activeConvId = conv.id,
                    messages = emptyList(),
                    streamingText = "",
                    error = null,
                    showHistoryDrawer = false
                )
            }
        }
    }

    fun switchConversation(convId: String) {
        if (convId == _uiState.value.activeConvId) {
            _uiState.update { it.copy(showHistoryDrawer = false) }
            return
        }
        viewModelScope.launch {
            val msgs = historyManager.loadMessages(convId)
            prefs.saveActiveConvId(convId)
            _uiState.update {
                it.copy(
                    activeConvId = convId,
                    messages = msgs,
                    streamingText = "",
                    error = null,
                    showHistoryDrawer = false
                )
            }
        }
    }

    fun deleteConversation(convId: String) {
        viewModelScope.launch {
            historyManager.deleteConversation(convId)
            val current = _uiState.value.activeConvId
            if (current == convId) {
                // Active conversation delete hui — new banao
                newConversation()
            } else {
                refreshConversations()
            }
        }
    }

    fun renameConversation(convId: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            historyManager.updateConversationTitle(convId, t)
            refreshConversations()
        }
    }

    fun pinConversation(convId: String, pinned: Boolean) {
        viewModelScope.launch {
            historyManager.setPinned(convId, pinned)
            refreshConversations()
        }
    }

    fun toggleHistoryDrawer() {
        _uiState.update { it.copy(showHistoryDrawer = !it.showHistoryDrawer) }
    }

    fun closeHistoryDrawer() {
        _uiState.update { it.copy(showHistoryDrawer = false) }
    }

    private fun refreshConversations() {
        val convs = historyManager.getAllConversations()
        _uiState.update { it.copy(conversations = convs) }
    }

    fun updateInput(text: String) { _uiState.update { it.copy(currentInput = text) } }
    fun setFileContext(ctx: String) { _uiState.update { it.copy(selectedFileContext = ctx) } }
    fun clearFileContext() { _uiState.update { it.copy(selectedFileContext = "") } }
    fun openTermux() { termuxBridge.openTermux() }

    // ── Chat attachments ─────────────────────────────────────
    fun setAttachment(name: String, mime: String, base64: String) =
        _uiState.update { it.copy(pendingFileName = name, pendingImageMime = mime, pendingImageBase64 = base64) }

    fun clearAttachment() =
        _uiState.update { it.copy(pendingFileName = null, pendingImageMime = null, pendingImageBase64 = null) }

    fun attachTextFile(name: String, content: String) {
        val snippet = "### File: $name\n```\n${content.take(20000)}\n```"
        _uiState.update { it.copy(selectedFileContext = (it.selectedFileContext + "\n\n" + snippet).trim()) }
    }

    // ── Agent mode + skills ──────────────────────────────────
    private fun parseSkills(json: String): List<Skill> {
        val stored: List<Skill> = if (json.isBlank()) emptyList() else try {
            gson.fromJson(json, object : TypeToken<List<Skill>>() {}.type)
        } catch (_: Exception) { emptyList() }
        val storedById = stored.associateBy { it.id }
        val builtIns = BuiltInSkills.all.map { b -> (storedById[b.id] ?: b).copy(builtIn = true) }
        val customs = stored.filter { s -> !s.builtIn && BuiltInSkills.all.none { it.id == s.id } }
        return builtIns + customs
    }

    private fun persistSkills(list: List<Skill>) {
        viewModelScope.launch { prefs.saveSkills(gson.toJson(list)) }
    }

    fun setAgentMode(mode: String) = viewModelScope.launch { prefs.saveAgentMode(mode) }

    fun toggleSkill(id: String) {
        val updated = _uiState.value.skills.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        _uiState.update { it.copy(skills = updated) }
        persistSkills(updated)
    }

    fun addSkill(name: String, description: String, instruction: String) {
        if (name.isBlank() || instruction.isBlank()) return
        val updated = _uiState.value.skills + Skill(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = name.trim(),
            description = description.trim(),
            instruction = instruction.trim(),
            builtIn = false,
            enabled = true
        )
        _uiState.update { it.copy(skills = updated) }
        persistSkills(updated)
    }

    fun updateSkill(id: String, name: String, description: String, instruction: String) {
        val updated = _uiState.value.skills.map {
            if (it.id == id) it.copy(name = name.trim(), description = description.trim(), instruction = instruction.trim()) else it
        }
        _uiState.update { it.copy(skills = updated) }
        persistSkills(updated)
    }

    fun deleteSkill(id: String) {
        val updated = _uiState.value.skills.filter { it.id != id || it.builtIn }
        _uiState.update { it.copy(skills = updated) }
        persistSkills(updated)
    }

    // Tool test cancel (agar zaroorat pade)
    fun cancelToolTest() {
        _uiState.update {
            it.copy(toolTestStage = ToolTestStage.IDLE, activeToolTest = null)
        }
    }

    // ── Schedule Research Task ───────────────────────────────

    fun scheduleResearch(topic: String, triggerAtMs: Long, repeat: com.txdost.data.RepeatMode = com.txdost.data.RepeatMode.ONCE) {
        val convId = _uiState.value.activeConvId
        if (convId.isEmpty() || topic.isBlank()) return
        val task = com.txdost.data.ScheduledTask(
            id          = java.util.UUID.randomUUID().toString().take(8),
            convId      = convId,
            topic       = topic,
            triggerAtMs = triggerAtMs,
            repeat      = repeat
        )
        txService?.scheduleResearchTask(task)
        val msg = ChatMessage(
            "assistant",
            "\uD83D\uDCC5 **Research Scheduled!**\n\n**Topic:** $topic\n**When:** ${java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(triggerAtMs))}\n**Repeat:** ${repeat.name}\n\nYou can close the app — research will run automatically at the scheduled time and you'll receive a notification. Tap the notification to open this conversation."
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
        viewModelScope.launch {
            val convId2 = _uiState.value.activeConvId
            if (convId2.isNotEmpty()) {
                historyManager.saveMessages(convId2, _uiState.value.messages)
                refreshConversations()
            }
        }
    }

    // ── Live Log Functions ───────────────────────────────────

    suspend fun fetchLiveLog(logFile: String, lines: Int, sinceByteOffset: Long): String {
        return termuxBridge.fetchLiveLog(logFile, lines, sinceByteOffset)
    }

    suspend fun getLogFileSize(logFile: String): Long {
        return termuxBridge.getLogFileSize(logFile)
    }

    fun clearLogFile(logFile: String) {
        viewModelScope.launch {
            termuxBridge.clearLogFile(logFile)
        }
    }

    // ── Process Control ──────────────────────────────────────

    fun toggleAutoRestart(id: String) {
        txService?.toggleAutoRestart(id)
    }

    fun checkProcessStatuses() {
        txService?.checkProcessStatuses()
    }
    // ── FileExplorer helper ──────────────────────────────────────

    suspend fun runCommand(command: String): String {
        return try {
            termuxBridge.runCommandAndGetOutput(command, 30).output
        } catch (e: Exception) { "" }
    }

    suspend fun getDeviceInfoJson(): org.json.JSONObject {
        return try {
            termuxBridge.getDeviceInfo()
        } catch (e: Exception) { org.json.JSONObject() }
    }

    // ── Direct command run — Run button se call hota hai ────────
    fun runCommandDirect(command: String) {
        if (_uiState.value.isGenerating) return
        val cmdMsg = ChatMessage("user", "▶ Run: `$command`")
        _uiState.update {
            it.copy(
                messages      = it.messages + cmdMsg,
                isGenerating  = true,
                streamingText = "⚡ Running: $command"
            )
        }
        viewModelScope.launch {
            val result  = termuxBridge.runCommandAndGetOutput(command, 60)
            val output  = result.output.trim().ifEmpty { "(no output)" }
            val icon    = if (result.success) "✅" else "❌"
            val resultMsg = ChatMessage(
                "assistant",
                "$icon `$ $command`\n```\n$output\n```"
            )
            val newMsgs = _uiState.value.messages + resultMsg
            _uiState.update {
                it.copy(
                    messages      = newMsgs,
                    isGenerating  = false,
                    streamingText = ""
                )
            }
            saveConversation(newMsgs)
        }
    }

}
