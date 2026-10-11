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
import com.txdost.TextUtils
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
    val showCommands: Boolean = true,
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
    val skills: List<Skill> = emptyList(),
    val bodyTextSize: String = "normal"
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

    // Auto mode: har request pe provider ki keys round-robin rotate karo (load distribute + rate-limit bypass)
    private val keyRotation = java.util.concurrent.atomic.AtomicInteger(0)

    // Current AI job — direct cancel ke liye ViewModel mein rakho
    private var currentAiJob: Job? = null

    // Generation ke waqt ka convId — reply SAHI conversation me save ho
    private var generatingConvId: String? = null

    private fun cancelActiveGeneration() {
        currentAiJob?.cancel()
        currentAiJob = null
        txService?.setGenerating(false)
        generatingConvId = null
        if (_uiState.value.isGenerating)
            _uiState.update { it.copy(isGenerating = false, streamingText = "") }
    }

    // Service connection
    private var txService: TxDostService? = null
    private var processCollectJob: Job? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            txService = (binder as TxDostService.LocalBinder).getService()
            // Service ke processes observe karo (purana collector band karke naya — reconnect pe leak nahi hoga)
            processCollectJob?.cancel()
            processCollectJob = viewModelScope.launch {
                txService?.processes?.collect { procs ->
                    _uiState.update { it.copy(activeProcesses = procs) }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            processCollectJob?.cancel()
            processCollectJob = null
            txService = null
        }
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
        viewModelScope.launch {
            prefs.showCommands.collect { v -> _uiState.update { it.copy(showCommands = v) } }
        }
        viewModelScope.launch {
            prefs.bodyTextSize.collect { v -> _uiState.update { it.copy(bodyTextSize = v) } }
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

    // Auto connection watcher — har 30 sec check karo
    fun startConnectionWatcher() {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
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
        val msgs = _uiState.value.messages
        val newMsgs = msgs + ChatMessage("assistant", "Rok diya gaya.")
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
                    "$name pehle se chal raha hai.\nLog: ${proc.logFile}"))
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
        val lastUser = msgs[idx]
        val lastUserText = lastUser.content
        val trimmed = msgs.subList(0, idx).toList()
        _uiState.update {
            it.copy(
                messages = trimmed,
                error = null,
                pendingImageBase64 = lastUser.imageBase64,
                pendingImageMime = lastUser.imageMime,
                pendingFileName = lastUser.fileName
            )
        }
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
            val runningMsg = ChatMessage("assistant", "Tool chal raha hai...")
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
                    appendLine("${tool.toolName} - Result:")
                    appendLine("```")
                    appendLine("$ $command")
                    appendLine(output)
                    appendLine("```")
                    if (result.success) append("Tool sahi chal raha hai.")
                    else append("Kuch galat lag raha hai.")
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
                streamingText = "Soch raha hoon...",
                error = null,
                pendingImageBase64 = null,
                pendingImageMime = null,
                pendingFileName = null
            )
        }

        currentAiJob?.cancel() // purana job cancel karo agar chal raha ho
        generatingConvId = _uiState.value.activeConvId
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

            // ── OpenCode Zen free models → official opencode CLI ──
            // Zen's free models (big-pickle, *-free) only work from the official
            // OpenCode client, even with a key. So route free models (and any
            // keyless Zen request) through the real CLI in Termux. Paid Zen models
            // with a key still use the direct HTTP path.
            val zenModel = prefs.modelFor("opencode").first()
            val zenFree = zenModel.equals("big-pickle", true) || zenModel.contains("-free", true)
            if (prefs.providerId.first() == "opencode" &&
                (prefs.apiKeyFor("opencode").first().isBlank() || zenFree) &&
                _uiState.value.agentMode != "plan"
            ) {
                handleOpencodeCliChat(updatedMessages, termuxLive)
                return@launch
            }

            val sb = StringBuilder()
            val candidates = buildFallbackCandidates()
            var lastError: String? = null
            var usedProvider: String? = null
            var usedModel: String? = null
            var usedKey: String? = null
            val rotateKeysEnabled = prefs.autoRotateKeys.first()
            val rotation = keyRotation.getAndIncrement()
            try {
                withTimeout(90_000L) {
                    for (cand in candidates) {
                        val spec = ProviderRegistry.byId(cand.first)
                        val allKeys = prefs.apiKeysFor(cand.first).first()
                        val orderedKeys = if (rotateKeysEnabled && allKeys.size > 1) {
                            val off = ((rotation % allKeys.size) + allKeys.size) % allKeys.size
                            allKeys.drop(off) + allKeys.take(off)
                        } else allKeys
                        val keysToTry = if (orderedKeys.isEmpty()) listOf("") else orderedKeys
                        var gotResponse = false
                        for (key in keysToTry) {
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
                                else localSb.append(token)
                            }
                            if (localSb.isNotEmpty()) {
                                sb.append(localSb)
                                usedProvider = cand.first
                                usedModel = cand.second
                                usedKey = key
                                gotResponse = true
                                break
                            }
                            lastError = localErr ?: "empty response"
                        }
                        if (gotResponse) break
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeout — jo bhi aaya tha use show karo
                val partial = sb.toString()
                if (partial.isNotEmpty()) {
                    val msgs = _uiState.value.messages + ChatMessage("assistant",
                        "${cleanTags(partial).ifEmpty { partial }}\n\n(Response time out ho gaya - jo aaya dikhaya.)")
                    _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
                    saveConversation(msgs)
                } else {
                    _uiState.update { it.copy(isGenerating = false, streamingText = "",
                        error = "Time out - AI ne 90 second me jawab nahi diya. Dobara try karo.") }
                }
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Exception) {
                // Network/parse crash — app band na ho
                _uiState.update { it.copy(isGenerating = false, streamingText = "",
                    error = "Kuch galat ho gaya: ${e.message ?: "network error"}") }
                return@launch
            }

            // Fallback ne jo model chalaya, usko session ke liye yaad rakho
            val okProvider = usedProvider
            val okModel = usedModel
            if (okProvider != null && okModel != null) {
                currentApiKey = usedKey ?: prefs.apiKeyFor(okProvider).first()
                currentModel = okModel
                llm.provider = ProviderRegistry.byId(okProvider)
                _uiState.update { it.copy(currentModel = okModel) }
            }

            val aiResponse = sb.toString()
            if (aiResponse.isEmpty()) {
                _uiState.update { it.copy(isGenerating = false, streamingText = "",
                    error = (lastError ?: "Koi response nahi aaya") +
                        "\n\n(Sab free models try ho gaye - Settings me dusra provider key add karo.)") }
                return@launch
            }

            // ── PLAN MODE: read-only — web search + file inspection, NO changes ──
            if (_uiState.value.agentMode == "plan") {
                handlePlanMode(aiResponse, updatedMessages, termuxLive, procsInfo)
                return@launch
            }

            // ── Delegate big builds to the external agent (opencode) ──
            val buildMatch = Regex("""\[BUILD:(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(aiResponse)
            if (buildMatch != null) {
                handleExternalBuild(buildMatch.groupValues[1].trim())
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
            val bgRegex      = Regex("""\[BG:(.*?)\|(.*)\|(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val stopRegex    = Regex("""\[STOP:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)

            val hasTermuxActions = listOf(termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                .any { it.containsMatchIn(aiResponse) }

            if (termuxLive && hasTermuxActions) {
                handleAllActions(aiResponse, updatedMessages, fileCreated,
                    termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
            } else {
                // AI responded with text but used no action tags
                val userText = updatedMessages.lastOrNull { it.role == "user" }?.content.orEmpty()
                val userAskedForAction = isActionRequest(userText)

                // ── AUTO-ENFORCE: ONLY when the user asked for a real action ──
                if (termuxLive && userAskedForAction && !hasTermuxActions) {
                    _uiState.update { it.copy(streamingText = "Commands chala raha hoon...") }

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

                    _uiState.update { it.copy(streamingText = "Commands chala raha hoon...") }
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
                            if (!token.startsWith("[ERROR]")) retrySb.append(token)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { return@launch }
                      catch (e: Exception) { /* retry fail — neeche fallback reply dikhega */ }

                    val retryResponse = retrySb.toString()

                    // Did the retry produce action tags?
                    val retryHasActions = listOf(termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                        .any { it.containsMatchIn(retryResponse) }

                    if (retryHasActions) {
                        // Tags found in retry — execute them
                        handleAllActions(retryResponse, messagesForRetry, fileCreated,
                            termuxRegex, installRegex, writeRegex, bgRegex, stopRegex)
                    } else {
                        // Still no tags — just show the (cleaned) reply, no scary warning
                        val body = cleanTags(retryResponse.ifEmpty { aiResponse })
                        val finalMsgs = _uiState.value.messages +
                            ChatMessage("assistant", body.ifEmpty { "Ho gaya." })
                        _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
                        saveConversation(finalMsgs)
                    }
                    return@launch
                }

                // Normal response (info/chat)
                val display = if (fileCreated != null) {
                    "${cleanTags(aiResponse)}\n\nFile ban gayi: $fileCreated".trim()
                } else {
                    cleanTags(aiResponse).ifEmpty { aiResponse }
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
            val noResultMsg = "Web search complete hua lekin koi result nahi mila. Dusra query try karo."
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
        } catch (e: Exception) {
            /* search-summary fail — neeche fallback finalText use hoga */
        }

        val finalText = finalSb.toString().ifEmpty {
            "$cleanedResponse\n\nSearch Results:\n$allSearchResults"
        }.let { cleanTags(it).ifEmpty { it } }
        val withFile = if (fileCreated != null) "$finalText\n\nFile bana di: $fileCreated".trim() else finalText

        val finalMsgs = _uiState.value.messages + ChatMessage("assistant", withFile)
        _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
        txService?.setGenerating(false)
        saveConversation(finalMsgs)
    }

    // ── Progress banner — plain Unicode, no markdown backticks ──
    // ── PLAN MODE: read-only. Web search + read-only shell inspection allowed. ──
    private val planReadOnlyPrefixes = setOf(
        "ls", "cat", "head", "tail", "grep", "find", "wc", "file", "stat", "pwd",
        "whoami", "uname", "env", "printenv", "du", "df", "ps", "pgrep", "which",
        "whereis", "date", "echo", "tree", "realpath", "readlink", "lsblk", "id",
        "hostname", "sed", "awk", "sort", "uniq", "cut", "tr", "nl", "od", "xxd",
        "strings", "basename", "dirname", "type", "command", "test", "lsof",
        "uptime", "free", "vmstat", "getprop"
    )
    private val planBlockedSubstrings = listOf(
        ">", "tee ", "rm ", "rm\t", "mv ", "cp ", "mkdir", "touch", "chmod",
        "chown", "truncate", "dd ", "ln ", "sudo", "pkg ", "apt", "pip ",
        "npm ", "yarn", "pnpm", "kill", "pkill", "nohup", "curl", "wget",
        "git ", "sh -c", "bash -c", "eval", "\$(", "`", " &", "sed -i"
    )

    private fun isReadOnlyCommand(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        if (planBlockedSubstrings.any { cmd.contains(it) }) return false
        val segments = cmd.split("&&", "||", "|", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        return segments.all { seg ->
            val first = seg.split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/').orEmpty()
            first in planReadOnlyPrefixes
        }
    }

    private suspend fun handlePlanMode(
        aiResponse: String,
        updatedMessages: List<ChatMessage>,
        termuxLive: Boolean,
        procsInfo: String
    ) {
        val observations = StringBuilder()

        // 1. Web searches — allowed
        val searches = Regex("""\[WEB_SEARCH:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(aiResponse).toList()
        if (searches.isNotEmpty()) {
            _uiState.update { it.copy(streamingText = "🔍 Searching the web...") }
            searches.forEach { match ->
                val query = match.groupValues[1].trim()
                val results = webSearch.search(query, 5)
                observations.appendLine(webSearch.formatForAI(query, results)).appendLine()
            }
        }

        // 2. Read-only commands — allowed; mutating ones are silently ignored
        val cmds = Regex("""\[TERMUX:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(aiResponse).map { it.groupValues[1].trim() }
            .filter { isReadOnlyCommand(it) }.toList()
        if (cmds.isNotEmpty() && termuxLive) {
            _uiState.update { it.copy(streamingText = "🔎 Inspecting files...") }
            cmds.forEach { cmd ->
                val r = termuxBridge.runCommandAndGetOutput(cmd, 30)
                observations.appendLine("$ $cmd").appendLine(r.output.take(2000)).appendLine()
            }
        }

        val cleaned = cleanTags(aiResponse).trim()

        // 3. Nothing to inspect → just save the plan
        if (observations.isBlank()) {
            val msgs = _uiState.value.messages + ChatMessage(
                "assistant", cleaned.ifEmpty { "(Plan only — read-only mode.)" }
            )
            _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
            saveConversation(msgs)
            return
        }

        // 4. Feed observations back → final plan (no action tags)
        _uiState.update { it.copy(streamingText = "") }
        val msgs2 = updatedMessages +
            ChatMessage("assistant", cleaned.ifEmpty { "[Inspecting...]" }) +
            ChatMessage(
                "user",
                "Read-only observations (Plan mode — nothing was created, changed or started):\n\n" +
                    "$observations\nNow give the final short plan based on this. Do NOT output any action tags."
            )
        val sb = StringBuilder()
        try {
            llm.chatStream(
                apiKey = currentApiKey,
                model = currentModel,
                messages = msgs2,
                systemPrompt = buildSystemPrompt(termuxLive, "", procsInfo),
                fileContext = ""
            ).collect { token ->
                if (!token.startsWith("[ERROR]")) {
                    sb.append(token)
                    _uiState.update { it.copy(streamingText = sb.toString()) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return
        } catch (e: Exception) {
            /* plan summary fail — neeche fallback cleaned text use hoga */
        }

        val finalText = cleanTags(sb.toString().ifBlank { cleaned }).ifEmpty { cleaned }
        val msgs = _uiState.value.messages + ChatMessage("assistant", finalText)
        _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
        saveConversation(msgs)
    }

    // ── OpenCode Zen chat via the official opencode CLI (Termux) ──
    // Free-tier models (big-pickle etc.) are tied to the official OpenCode client;
    // we run the real CLI in Termux so the client fingerprint is genuine (no spoofing).
    private fun opencodeNativeConfig(model: String): String = JSONObject().apply {
        put("\$schema", "https://opencode.ai/config.json")
        put("autoupdate", false)
        put("model", "opencode/$model")
    }.toString(2)

    private suspend fun handleOpencodeCliChat(updatedMessages: List<ChatMessage>, termuxLive: Boolean) {
        if (!termuxLive) {
            _uiState.update { it.copy(isGenerating = false, streamingText = "",
                error = "Zen ke free models ke liye Settings > OpenCode Zen me apna API key daalein.\n(API key ke bina Zen free models sirf official app ke andar chalte hain.)") }
            txService?.setGenerating(false)
            return
        }
        val installed = termuxBridge
            .runCommandAndGetOutput("command -v opencode 2>/dev/null || echo NOT_INSTALLED")
            .output
        if (!installed.contains("/opencode")) {
            _uiState.update { it.copy(isGenerating = false, streamingText = "",
                error = "Zen ke free models ke liye Settings > OpenCode Zen me apna API key daalein.\n(Termux/opencode CLI optional hai — key se app ke andar hi chal jaata hai.)") }
            txService?.setGenerating(false)
            return
        }

        val model = prefs.modelFor("opencode").first().ifBlank { "big-pickle" }
        val userMsg = updatedMessages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (userMsg.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, streamingText = "") }
            txService?.setGenerating(false)
            return
        }

        termuxBridge.writeFile("~/.config/opencode/opencode.json", opencodeNativeConfig(model))
        termuxBridge.writeFile("~/opencode_chat.txt", userMsg)

        _uiState.update { it.copy(streamingText = "$model (opencode CLI) chal raha hai...") }
        termuxBridge.runCommandAndGetOutput(
            "cd ~ && rm -f ~/opencode_chat.log; nohup sh -c " +
                "'opencode run \"\$(cat ~/opencode_chat.txt)\" --model opencode/$model --auto' " +
                "> ~/opencode_chat.log 2>&1 & echo STARTED",
            15
        )

        var done = false
        var iter = 0
        while (!done && iter < 90) {
            delay(3000)
            iter++
            val tail = termuxBridge
                .runCommandAndGetOutput("tail -n 20 ~/opencode_chat.log 2>/dev/null").output
            if (tail.isNotBlank()) {
                _uiState.update { it.copy(streamingText = "$model:\n" + tail.takeLast(1200)) }
            }
            val status = termuxBridge
                .runCommandAndGetOutput("pgrep -f 'opencode run' >/dev/null && echo RUNNING || echo DONE", 10)
                .output
            if (status.contains("DONE")) done = true
        }

        val raw = termuxBridge
            .runCommandAndGetOutput("tail -n 300 ~/opencode_chat.log 2>/dev/null").output
        val answer = when {
            raw.contains("FreeTierError") || raw.contains("free tier can only be used") ->
                "opencode CLI ne free tier reject kar diya — iska matlab CLI official release nahi hai. " +
                    "Settings > External agent > Install (opencode) dobara chalao (ye official package + upgrade " +
                    "karta hai), phir message dobara bhejo."
            else -> cleanTags(raw).ifBlank { "(koi output nahi aaya)" }
        }
        val finalMsgs = _uiState.value.messages + ChatMessage("assistant", answer)
        _uiState.update { it.copy(messages = finalMsgs, isGenerating = false, streamingText = "") }
        txService?.setGenerating(false)
        saveConversation(finalMsgs)
    }

    // ── EXTERNAL AGENT (opencode) — delegate big builds ─────────
    private val opencodeProviderOrder = listOf(
        "opencode", "openrouter", "groq", "gemini", "mistral", "nvidia"
    )

    private fun postAssistant(text: String) {
        val msgs = _uiState.value.messages + ChatMessage("assistant", text)
        _uiState.update { it.copy(messages = msgs, isGenerating = false, streamingText = "") }
        saveConversation(msgs)
    }

    // Reuse the user's EXISTING app keys — never ask for a separate key.
    private suspend fun pickExternalProvider(): Triple<ProviderSpec, String, String>? {
        for (id in opencodeProviderOrder) {
            val spec = ProviderRegistry.byId(id)
            if (spec.nativeOllama) continue
            val key = prefs.apiKeyFor(id).first()
            if (key.isBlank() && id != "opencode") continue
            var model = prefs.modelFor(id).first()
            if (model.isBlank()) {
                model = llm.getModels(spec, key).getOrNull()
                    ?.firstOrNull { it.free }?.id.orEmpty()
            }
            if (model.isBlank()) continue
            return Triple(spec, key, model)
        }
        return null
    }

    private suspend fun handleExternalBuild(task: String) {
        if (!_uiState.value.termuxConnected) {
            postAssistant("External agent ke liye Termux online hona chahiye.")
            return
        }

        val installed = termuxBridge
            .runCommandAndGetOutput("command -v opencode 2>/dev/null || echo NOT_INSTALLED")
            .output.contains("/opencode")
        if (!installed) {
            postAssistant(
                "External agent (opencode) install nahi hai.\n\n" +
                    "Settings > External agent > Install par tap karein (one tap). " +
                    "Uske baad yehi task dobara bhejein."
            )
            return
        }

        val pick = pickExternalProvider()
        if (pick == null) {
            postAssistant(
                "Koi provider key nahi mili. Pehle Settings me kisi free provider " +
                    "(OpenCode Zen / OpenRouter / Groq ...) ki key daalein aur ek model chunein."
            )
            return
        }
        val (spec, key, model) = pick

        // 1. opencode config. For OpenCode Zen use the CLI's NATIVE provider
        //    (free tier is tied to the official client; no custom override).
        val config = if (spec.id == "opencode") {
            opencodeNativeConfig(model)
        } else {
            JSONObject().apply {
                put("\$schema", "https://opencode.ai/config.json")
                put("autoupdate", false)
                put("model", "${spec.id}/$model")
                put("provider", JSONObject().put(spec.id, JSONObject().apply {
                    put("npm", "@ai-sdk/openai-compatible")
                    put("name", spec.name)
                    put("options", JSONObject().apply {
                        put("baseURL", spec.baseUrl)
                        put("apiKey", key)
                    })
                    put("models", JSONObject().put(model, JSONObject()))
                }))
            }.toString(2)
        }
        termuxBridge.writeFile("~/.config/opencode/opencode.json", config)
        termuxBridge.writeFile("~/opencode_task.txt", task)

        // 2. Launch headless, stream to a log file
        _uiState.update { it.copy(streamingText = "opencode chal raha hai... (${spec.id}/$model)") }
        termuxBridge.runCommandAndGetOutput(
            "cd ~ && nohup sh -c " +
                "'opencode run \"\$(cat ~/opencode_task.txt)\" --model ${spec.id}/$model --auto --print-logs' " +
                "> ~/opencode_run.log 2>&1 & echo STARTED",
            15
        )

        // 3. Poll the log until the process exits (or ~5 min)
        var done = false
        var iter = 0
        while (!done && iter < 60) {
            delay(5000)
            iter++
            val tail = termuxBridge
                .runCommandAndGetOutput("tail -n 40 ~/opencode_run.log 2>/dev/null").output
            if (tail.isNotBlank()) {
                _uiState.update { it.copy(streamingText = "opencode:\n" + tail.takeLast(1600)) }
            }
            val status = termuxBridge
                .runCommandAndGetOutput("pgrep -f 'opencode run' >/dev/null && echo RUNNING || echo DONE", 10)
                .output
            if (status.contains("DONE")) done = true
        }

        val finalLog = termuxBridge
            .runCommandAndGetOutput("tail -n 150 ~/opencode_run.log 2>/dev/null").output.trim()
        val shown = if (finalLog.length > 4000) finalLog.takeLast(4000) else finalLog
        postAssistant(
            "External agent (opencode) - ${if (done) "complete" else "still running"}  " +
                "(${spec.id}/$model)\n\n${shown.ifBlank { "(no output)" }}"
        )
    }

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

    private fun cleanTags(response: String): String = TextUtils.tidy(
        TextUtils.stripEmoji(
            response
                .replace(Regex("""\[FILE:.*?\]"""), "")
                .replace(Regex("""\[CONTENT\].*?\[/CONTENT\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[TERMUX:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[INSTALL:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<<<WRITE:.*?>>>.*?<<<END>>>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[BG:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[STOP:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[TOOL:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[WEB_SEARCH:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\[BUILD:.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
        )
    )

    /** Heuristic: did the user actually ASK for an action (not just a question)? */
    private fun isActionRequest(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // Questions are never treated as action commands
        if (t.endsWith("?") || t.contains("?")) return false
        val qWords = listOf("kya", "kaise", "kaisa", "kyun", "kyu", "kab", "kahan",
            "what", "how", "why", "when", "where", "which", "who", "explain", "samjha")
        val first = t.lowercase().substringBefore(" ").trim()
        if (qWords.contains(first)) return false
        val verbs = listOf(
            "banao", "bana", "create", "make", "likho", "likh", "write",
            "install", "setup", "set up", "daalo", "daal", "add",
            "chalao", "chala", "run", "start", "launch", "shuru",
            "band", "stop", "kill", "rok", "delete", "hata", "remove",
            "fix", "theek", "patch", "mod", "crack", "edit", "change",
            "download", "clone", "update", "upgrade", "build", "compile",
            "nikal", "find", "dhoondh", "extract", "decompile", "disassemble"
        )
        return verbs.any { t.lowercase().contains(it) }
    }

    private suspend fun tryCreateFileFromResponse(response: String): String? {
        return try {
            val fileName = Regex("""\[FILE:(.*?)\]""").find(response)?.groupValues?.get(1)?.trim() ?: return null
            val content  = Regex("""\[CONTENT\](.*?)\[/CONTENT\]""", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.groupValues?.get(1)?.trim() ?: return null
            writeToCorrectRoot(fileName, content)
        } catch (e: Exception) { null }
    }

    // Ek hi jagah decide karo kaha likhna hai:
    //  · absolute / phone path (`/...`, `~...`) → real filesystem (Termux)
    //  · relative naam → app ka private workspace (sandboxed)
    // Absolute path ko workspace me nest hone se bachata hai.
    private suspend fun writeToCorrectRoot(path: String, content: String): String? {
        val p = path.trim()
        val isAbsolute = p.startsWith("/") || p.startsWith("~")
        if (!isAbsolute) {
            val res = fileManager.writeFile(p, content)
            return if (res.isSuccess) p else null
        }
        if (termuxBridge.isSharedStoragePath(p) && !termuxBridge.ensureStorageAccess()) return null
        return if (termuxBridge.writeFile(p, content)) p else null
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
        val toolRegex = Regex("""\[TOOL:(.*?)\|(.*)\|(.*?)\|(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
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
                    "${tool.toolName} ready hai.\nTest ke liye input do: ${tool.argHint}"
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
                val runMsg = ChatMessage("assistant", "${tool.toolName} test ho raha hai...")
                _uiState.update { it.copy(messages = it.messages + runMsg) }
                val result = termuxBridge.runCommandAndGetOutput(tool.runCommand, 30)
                val output = result.output.trim().ifEmpty { "(no output received)" }
                val resultMsg = buildString {
                    appendLine("${tool.toolName} - Test Result:")
                    appendLine("```")
                    appendLine("$ ${tool.runCommand}")
                    appendLine(output)
                    appendLine("```")
                    if (result.success) append("Tool sahi chal raha hai.")
                    else append("Kuch galat lag raha hai.")
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
            updateProgress(completedSteps, "Writing: $path", totalSteps, stepsDone)
            val storageOk = !termuxBridge.isSharedStoragePath(path) || termuxBridge.ensureStorageAccess()
            val ok   = storageOk && termuxBridge.writeFile(path, content)
            val line = if (ok) "Written: $path"
                       else if (!storageOk) "Storage access chahiye: $path (Termux me termux-setup-storage)"
                       else "Write failed: $path"
            actionResults.appendLine("Writing: $path")
            actionResults.appendLine(if (ok) "Written" else "Failed").appendLine()
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

        _uiState.update { it.copy(streamingText = "Summarizing...") }
        val finalSb = StringBuilder()

        try {
            llm.chatStream(
                apiKey = currentApiKey,
                model = currentModel,
                messages = messagesWithResult,
                systemPrompt = buildSystemPrompt(true, "", ""),
                fileContext = ""
            ).collect { token ->
                if (!token.startsWith("[ERROR]")) finalSb.append(token)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return
        } catch (e: Exception) {
            /* summary fail — neeche fallback finalText use hoga */
        }

        val finalText = finalSb.toString().ifEmpty {
            cleanResponse.ifEmpty { "Kaam ho gaya." }
        }
        // Debug log — AI ka final response
        addDebugLog(DebugLogType.AI_RECV, "← AI response", finalText.take(200))
        val cleanFinal = cleanTags(finalText).ifEmpty { finalText }
        val withFile = if (fileCreated != null) "$cleanFinal\n\nApp file: $fileCreated".trim() else cleanFinal

        // Notification bhejo
        txService?.sendNotification("Task Complete", cleanResponse.take(60).ifEmpty { "Kaam ho gaya" })
        txService?.setGenerating(false)

        val actionFinalMsgs = _uiState.value.messages + ChatMessage("assistant", withFile)
        _uiState.update {
            it.copy(messages = actionFinalMsgs, isGenerating = false, streamingText = "")
        }
        saveConversation(actionFinalMsgs)
    }

    // ── Helper: conversation save + title update ─────────────────
    private fun saveConversation(msgs: List<ChatMessage>) {
        // Generation ke waqt ka convId use karo — user switch kar chuka ho to bhi sahi jagah save ho
        val convId = generatingConvId ?: _uiState.value.activeConvId
        generatingConvId = null
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

    private fun buildSystemPrompt(termuxConnected: Boolean, deviceInfo: String, activeProcs: String): String =
        SystemPrompts.build(
            customPrompt = currentSystemPrompt,
            enabledSkills = _uiState.value.skills.filter { it.enabled },
            planMode = _uiState.value.agentMode == "plan",
            termuxConnected = termuxConnected,
            deviceInfo = deviceInfo,
            activeProcs = activeProcs
        )

    fun clearChat() {
        cancelActiveGeneration()
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
            appendLine("Output Summary (${lines.size} lines -> compressed):")
            if (errorLines.isNotEmpty()) {
                appendLine("ERRORS FOUND:")
                errorLines.distinct().take(8).forEach { appendLine("  $it") }
            }
            if (successLines.isNotEmpty() && errorLines.isEmpty()) {
                appendLine("SUCCESS:")
                successLines.take(4).forEach { appendLine("  $it") }
            }
            if (errorLines.isEmpty() && successLines.isEmpty()) {
                appendLine("Last output:")
                lastLines.forEach { appendLine("  $it") }
            }
            appendLine("--- Full cmd: $cmd")
        }.trim()
    }

    // ── History Functions ────────────────────────────────────

    fun newConversation() {
        cancelActiveGeneration()
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
        // Purani generation rok do — warna reply galat conv me chala jayega
        cancelActiveGeneration()
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
        if (convId == _uiState.value.activeConvId) cancelActiveGeneration()
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
    fun setShowCommands(enabled: Boolean) = viewModelScope.launch { prefs.saveShowCommands(enabled) }

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
            "Research Scheduled!\n\nTopic: $topic\nWhen: ${java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(triggerAtMs))}\nRepeat: ${repeat.name}\n\nYou can close the app - research will run automatically at the scheduled time and you'll get a notification. Tap it to open this conversation."
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
        val cmdMsg = ChatMessage("user", "Run: $command")
        _uiState.update {
            it.copy(
                messages      = it.messages + cmdMsg,
                isGenerating  = true,
                streamingText = "Running: $command"
            )
        }
        viewModelScope.launch {
            val result  = termuxBridge.runCommandAndGetOutput(command, 60)
            val output  = result.output.trim().ifEmpty { "(no output)" }
            val resultMsg = ChatMessage(
                "assistant",
                "$ $command\n$output"
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
