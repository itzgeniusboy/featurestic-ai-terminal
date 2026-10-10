package com.txdost.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.txdost.data.AppPreferences
import com.txdost.data.LlmClient
import com.txdost.data.ModelInfo
import com.txdost.data.ProviderRegistry
import kotlinx.coroutines.launch

private val HBg       = Color(0xFF0A0A0A)
private val HGreen    = Color(0xFF00FF41)
private val HGreenDim = Color(0xFF00C832)
private val HCyan     = Color(0xFF00FFFF)
private val HYellow   = Color(0xFFFFFF00)
private val HRed      = Color(0xFFFF0040)
private val HBorder   = Color(0xFF003300)
private val HCard     = Color(0xFF0A0F0A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToTermuxSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val llm = remember { LlmClient() }
    val coroutineScope = rememberCoroutineScope()

    val savedProviderId by prefs.providerId.collectAsState(initial = ProviderRegistry.default.id)
    var selectedProviderId by remember { mutableStateOf(savedProviderId) }
    LaunchedEffect(savedProviderId) { selectedProviderId = savedProviderId }

    val provider = ProviderRegistry.byId(selectedProviderId)

    val savedApiKey by prefs.apiKeyFor(selectedProviderId).collectAsState(initial = "")
    val savedModel by prefs.modelFor(selectedProviderId).collectAsState(initial = "")
    val savedPrompt by prefs.systemPrompt.collectAsState(initial = "")
    val savedSearch by prefs.webSearchProvider.collectAsState(initial = "DUCKDUCKGO")
    val savedTavily by prefs.tavilyApiKey.collectAsState(initial = "")
    val savedFirecrawl by prefs.firecrawlApiKey.collectAsState(initial = "")
    val savedProxyEnabled by prefs.useTermuxProxy.collectAsState(initial = false)
    val savedProxyUrl by prefs.termuxProxyUrl.collectAsState(initial = "http://127.0.0.1:8080/v1")

    var apiKey by remember(selectedProviderId, savedApiKey) { mutableStateOf(savedApiKey) }
    var model by remember(selectedProviderId, savedModel) { mutableStateOf(savedModel) }
    var systemPrompt by remember(savedPrompt) { mutableStateOf(savedPrompt) }
    var searchProvider by remember(savedSearch) { mutableStateOf(savedSearch) }
    var tavilyKey by remember(savedTavily) { mutableStateOf(savedTavily) }
    var firecrawlKey by remember(savedFirecrawl) { mutableStateOf(savedFirecrawl) }
    var proxyEnabled by remember(savedProxyEnabled) { mutableStateOf(savedProxyEnabled) }
    var proxyUrl by remember(savedProxyUrl) { mutableStateOf(savedProxyUrl) }

    var status by remember { mutableStateOf("") }
    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showSearchDropdown by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var tavilyKeyVisible by remember { mutableStateOf(false) }
    var firecrawlKeyVisible by remember { mutableStateOf(false) }

    val searchProviders = listOf(
        "TAVILY"     to "Tavily (Best — Real-time AI search)",
        "FIRECRAWL"  to "Firecrawl (Full page content)",
        "DUCKDUCKGO" to "DuckDuckGo (Free, no key)"
    )

    fun refreshModels() {
        isLoading = true
        status = ">> FETCHING FREE MODELS..."
        coroutineScope.launch {
            val res = llm.getModels(provider, apiKey.trim())
            if (res.isSuccess) {
                availableModels = res.getOrDefault(emptyList())
                status = if (availableModels.isNotEmpty())
                    "✅ ${provider.name} — ${availableModels.size} free models"
                else "✅ ${provider.name} — no free models found"
                if (model.isBlank() && availableModels.isNotEmpty()) model = availableModels.first().id
            } else {
                status = "❌ ${provider.name} — ${res.exceptionOrNull()?.message ?: "connection failed"}"
            }
            isLoading = false
        }
    }

    // Auto-fetch free models whenever provider (or its saved key) changes
    LaunchedEffect(selectedProviderId, savedApiKey) {
        availableModels = emptyList()
        refreshModels()
    }

    fun saveSettings() {
        coroutineScope.launch {
            prefs.saveProviderId(selectedProviderId)
            prefs.saveApiKeyFor(selectedProviderId, apiKey.trim())
            prefs.saveModelFor(selectedProviderId, model.trim())
            prefs.saveSystemPrompt(systemPrompt.trim())
            prefs.saveWebSearchProvider(searchProvider)
            prefs.saveTavilyApiKey(tavilyKey.trim())
            prefs.saveFirecrawlApiKey(firecrawlKey.trim())
            prefs.saveUseTermuxProxy(proxyEnabled)
            prefs.saveTermuxProxyUrl(proxyUrl.trim())
            status = "✅ CONFIG SAVED"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(HBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, HBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = HGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "[ CONFIG PANEL ]",
                        color = HGreen, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Termux connection ────────────────────────────
                HackerSection("[ TERMUX_CONNECTION ]") {
                    Text(
                        "3 step me phone ko AI terminal se connect karo. Script app me bundled hai.",
                        color = HGreenDim.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Button(
                        onClick = onNavigateToTermuxSetup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF001A00)),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, HCyan)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = HCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ">> TERMUX SETUP / CONNECT",
                            color = HCyan, fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ── Provider ─────────────────────────────────────
                HackerSection("[ PROVIDER ]") {
                    Box {
                        OutlinedButton(
                            onClick = { showProviderDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, HGreen),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF000800))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(">> ${provider.name}", color = HGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text("[▼]", color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        DropdownMenu(
                            expanded = showProviderDropdown,
                            onDismissRequest = { showProviderDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.9f).background(HCard).border(BorderStroke(1.dp, HBorder))
                        ) {
                            ProviderRegistry.all.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            ">> ${p.name}",
                                            color = if (p.id == selectedProviderId) HCyan else HGreen,
                                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                            fontWeight = if (p.id == selectedProviderId) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { selectedProviderId = p.id; showProviderDropdown = false },
                                    modifier = Modifier.background(HCard)
                                )
                            }
                        }
                    }
                }

                // ── API Key ──────────────────────────────────────
                HackerSection("[ API_KEY ]") {
                    HackerTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "${provider.name.uppercase().replace(" ", "_")}_KEY",
                        placeholder = "api key...",
                        isPassword = !apiKeyVisible,
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Text(
                                    if (apiKeyVisible) "[HIDE]" else "[SHOW]",
                                    color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    )
                    Text(
                        ">> ${provider.keyHint}",
                        color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // Refresh free models
                Button(
                    onClick = { refreshModels() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF001A00),
                        disabledContainerColor = Color(0xFF000A00)
                    ),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, if (!isLoading) HGreen else HBorder)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = HGreen, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        ">> REFRESH_FREE_MODELS",
                        color = if (!isLoading) HGreen else HGreenDim,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    )
                }

                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (status.contains("✅")) Color(0xFF001400) else Color(0xFF1A0000),
                                RoundedCornerShape(2.dp)
                            )
                            .border(
                                BorderStroke(1.dp, if (status.contains("✅")) HGreen else HRed),
                                RoundedCornerShape(2.dp)
                            )
                            .padding(10.dp),
                        color = if (status.contains("✅")) HGreen else HRed,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Model ────────────────────────────────────────
                HackerSection("[ FREE_MODELS ]") {
                    HackerTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "MODEL_ID",
                        placeholder = "model id...",
                        trailingIcon = {
                            if (availableModels.isNotEmpty()) {
                                IconButton(onClick = { showModelDropdown = true }) {
                                    Text("[▼]", color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = { showModelDropdown = false },
                        modifier = Modifier.fillMaxWidth(0.9f).background(HCard).border(BorderStroke(1.dp, HBorder))
                    ) {
                        availableModels.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(">> ${m.name}", color = HGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        if (m.name != m.id) {
                                            Text(m.id, color = HGreenDim.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                },
                                onClick = { model = m.id; showModelDropdown = false },
                                modifier = Modifier.background(HCard)
                            )
                        }
                    }
                    Text(
                        if (availableModels.isEmpty()) ">> Tap [REFRESH_FREE_MODELS] to load free models"
                        else ">> ${availableModels.size} free models available",
                        color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Termux Proxy ─────────────────────────────────
                HackerSection("[ TERMUX_PROXY ]") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (proxyEnabled) "PROXY: ON ●" else "PROXY: OFF ○",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (proxyEnabled) HGreen else HGreenDim.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = proxyEnabled,
                            onCheckedChange = { proxyEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = HGreen,
                                checkedTrackColor = HGreen.copy(alpha = 0.3f),
                                uncheckedThumbColor = HBorder,
                                uncheckedTrackColor = HBorder.copy(alpha = 0.3f)
                            )
                        )
                    }
                    HackerTextField(
                        value = proxyUrl,
                        onValueChange = { proxyUrl = it },
                        label = "PROXY_BASE_URL",
                        placeholder = "http://127.0.0.1:8080/v1"
                    )
                    Text(
                        ">> Route model calls through your Termux server (X-Provider-Base header set automatically)",
                        color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── WEB SEARCH PROVIDER ──────────────────────────
                HackerSection("[ WEB_SEARCH_PROVIDER ]") {
                    Box {
                        OutlinedButton(
                            onClick = { showSearchDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, HGreen),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF000800))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    ">> $searchProvider",
                                    color = HGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                                )
                                Text("[▼]", color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        DropdownMenu(
                            expanded = showSearchDropdown,
                            onDismissRequest = { showSearchDropdown = false },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(HCard)
                                .border(BorderStroke(1.dp, HBorder))
                        ) {
                            searchProviders.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                ">> $key",
                                                color = if (searchProvider == key) HCyan else HGreen,
                                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                                fontWeight = if (searchProvider == key) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                label,
                                                color = HGreenDim.copy(alpha = 0.6f),
                                                fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    },
                                    onClick = { searchProvider = key; showSearchDropdown = false },
                                    modifier = Modifier.background(HCard)
                                )
                            }
                        }
                    }

                    if (searchProvider == "TAVILY") {
                        Spacer(Modifier.height(4.dp))
                        HackerTextField(
                            value = tavilyKey,
                            onValueChange = { tavilyKey = it },
                            label = "TAVILY_API_KEY",
                            placeholder = "tvly-...",
                            isPassword = !tavilyKeyVisible,
                            trailingIcon = {
                                IconButton(onClick = { tavilyKeyVisible = !tavilyKeyVisible }) {
                                    Text(
                                        if (tavilyKeyVisible) "[HIDE]" else "[SHOW]",
                                        color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        )
                        Text(
                            ">> app.tavily.com → API Keys | 1000 free/month",
                            color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }

                    if (searchProvider == "FIRECRAWL") {
                        Spacer(Modifier.height(4.dp))
                        HackerTextField(
                            value = firecrawlKey,
                            onValueChange = { firecrawlKey = it },
                            label = "FIRECRAWL_API_KEY",
                            placeholder = "fc-...",
                            isPassword = !firecrawlKeyVisible,
                            trailingIcon = {
                                IconButton(onClick = { firecrawlKeyVisible = !firecrawlKeyVisible }) {
                                    Text(
                                        if (firecrawlKeyVisible) "[HIDE]" else "[SHOW]",
                                        color = HGreenDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        )
                        Text(
                            ">> firecrawl.dev → Dashboard | 500 free/month",
                            color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }

                    if (searchProvider == "DUCKDUCKGO") {
                        Text(
                            ">> Free, no API key needed. Limited results.",
                            color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // ── System Prompt ────────────────────────────────
                HackerSection("[ SYSTEM_PROMPT ]") {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 8,
                        colors = hackerTextFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HGreen
                        ),
                        placeholder = {
                            Text(
                                "Custom system prompt...",
                                color = HGreen.copy(alpha = 0.3f),
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp
                            )
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
                    Text(
                        ">> Leave empty for default Featurestic AI prompt",
                        color = HGreenDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Save ─────────────────────────────────────────
                Button(
                    onClick = { saveSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003300)),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, HGreen)
                ) {
                    Text(
                        ">> SAVE_CONFIG",
                        color = HGreen, fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ── Temperature ─────────────────────────────────────
                HackerSection("[ GENERATION_SETTINGS ]") {
                    val savedTemp by prefs.temperature.collectAsState(initial = 0.7f)
                    var tempSlider by remember(savedTemp) { mutableStateOf(savedTemp) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "TEMPERATURE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = HGreen
                        )
                        Text(
                            "${"%.2f".format(tempSlider)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = HCyan
                        )
                    }
                    Slider(
                        value = tempSlider,
                        onValueChange = { tempSlider = it },
                        onValueChangeFinished = {
                            coroutineScope.launch { prefs.saveTemperature(tempSlider) }
                        },
                        valueRange = 0f..2f,
                        steps = 39,
                        colors = SliderDefaults.colors(
                            thumbColor = HCyan,
                            activeTrackColor = HGreen,
                            inactiveTrackColor = HBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "0.0 = deterministic  |  0.7 = balanced  |  2.0 = creative",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = HGreenDim.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun HackerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = HCyan, fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HCard, RoundedCornerShape(2.dp))
                .border(BorderStroke(1.dp, HBorder), RoundedCornerShape(2.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
fun hackerTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor       = HGreen,
    unfocusedTextColor     = HGreenDim,
    focusedBorderColor     = HGreen,
    unfocusedBorderColor   = HBorder,
    cursorColor            = HGreen,
    focusedContainerColor  = Color(0xFF000800),
    unfocusedContainerColor = Color(0xFF000800),
    focusedLabelColor      = HCyan,
    unfocusedLabelColor    = HCyan.copy(alpha = 0.6f)
)

@Composable
fun HackerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
        placeholder = { Text(placeholder, color = HGreen.copy(alpha = 0.3f), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailingIcon,
        colors = hackerTextFieldColors(),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HGreen),
        shape = RoundedCornerShape(2.dp)
    )
}
