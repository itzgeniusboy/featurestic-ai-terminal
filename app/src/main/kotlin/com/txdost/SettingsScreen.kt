package com.txdost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.txdost.data.AppPreferences
import com.txdost.data.LlmClient
import com.txdost.data.ModelInfo
import com.txdost.data.ProviderRegistry
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToTermuxSetup: () -> Unit = {},
    chatViewModel: ChatViewModel? = null
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val c = Apple.colors
    val prefs = remember { AppPreferences(app) }
    val scope = rememberCoroutineScope()

    val providerId by remember { prefs.providerId }.collectAsState(initial = ProviderRegistry.default.id)
    val apiKeyFlow = remember(providerId) { prefs.apiKeyFor(providerId) }
    val modelFlow = remember(providerId) { prefs.modelFor(providerId) }
    val apiKeyStored by apiKeyFlow.collectAsState(initial = "")
    val modelStored by modelFlow.collectAsState(initial = "")
    val systemPromptStored by remember { prefs.systemPrompt }.collectAsState(initial = "")
    val proxyStored by remember { prefs.useTermuxProxy }.collectAsState(initial = false)
    val proxyUrlStored by remember { prefs.termuxProxyUrl }.collectAsState(initial = "http://127.0.0.1:8080/v1")
    val searchStored by remember { prefs.webSearchProvider }.collectAsState(initial = "DUCKDUCKGO")
    val tavilyStored by remember { prefs.tavilyApiKey }.collectAsState(initial = "")
    val firecrawlStored by remember { prefs.firecrawlApiKey }.collectAsState(initial = "")
    val tempStored by remember { prefs.temperature }.collectAsState(initial = 0.7f)

    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var proxyEnabled by remember { mutableStateOf(false) }
    var proxyUrl by remember { mutableStateOf("http://127.0.0.1:8080/v1") }
    var searchProvider by remember { mutableStateOf("DUCKDUCKGO") }
    var tavilyKey by remember { mutableStateOf("") }
    var firecrawlKey by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.7f) }

    var apiKeyVisible by remember { mutableStateOf(false) }
    var tavilyVisible by remember { mutableStateOf(false) }
    var firecrawlVisible by remember { mutableStateOf(false) }

    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var searchMenu by remember { mutableStateOf(false) }

    LaunchedEffect(providerId, apiKeyStored) { apiKey = apiKeyStored }
    LaunchedEffect(providerId, modelStored) { model = modelStored }
    LaunchedEffect(systemPromptStored) { systemPrompt = systemPromptStored }
    LaunchedEffect(proxyStored) { proxyEnabled = proxyStored }
    LaunchedEffect(proxyUrlStored) { proxyUrl = proxyUrlStored }
    LaunchedEffect(searchStored) { searchProvider = searchStored }
    LaunchedEffect(tavilyStored) { tavilyKey = tavilyStored }
    LaunchedEffect(firecrawlStored) { firecrawlKey = firecrawlStored }
    LaunchedEffect(tempStored) { temperature = tempStored }

    val provider = ProviderRegistry.byId(providerId)

    fun refreshModels() {
        isLoading = true
        status = "Loading free models…"
        scope.launch {
            val result = com.txdost.data.LlmClient().getModels(provider, apiKey)
            isLoading = false
            result.onSuccess { list ->
                availableModels = list
                if (model.isBlank() && list.isNotEmpty()) model = list.first().id
                status = if (list.isEmpty()) "No free models found for ${provider.name}"
                else "${list.size} free model(s) loaded"
            }.onFailure { status = "Failed: ${it.message ?: "unknown error"}" }
        }
    }

    fun saveAll() {
        scope.launch {
            prefs.saveProviderId(providerId)
            prefs.saveApiKeyFor(providerId, apiKey.trim())
            prefs.saveModelFor(providerId, model.trim())
            prefs.saveSystemPrompt(systemPrompt)
            prefs.saveUseTermuxProxy(proxyEnabled)
            prefs.saveTermuxProxyUrl(proxyUrl.trim())
            prefs.saveWebSearchProvider(searchProvider)
            prefs.saveTavilyApiKey(tavilyKey.trim())
            prefs.saveFirecrawlApiKey(firecrawlKey.trim())
            prefs.saveTemperature(temperature)
            status = "Settings saved"
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(title = "Settings", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp)
        ) {

            AppleSectionHeader("Termux")
            AppleGroup {
                AppleRow(
                    title = "Setup & connect server",
                    subtitle = if (chatViewModel?.uiState?.value?.termuxConnected == true) "Connected" else "Not connected",
                    icon = Icons.Rounded.Terminal,
                    iconTint = c.green,
                    showChevron = true,
                    showDivider = false,
                    onClick = onNavigateToTermuxSetup
                )
            }

            AppleSectionHeader("AI provider")
            AppleGroup {
                Box {
                    AppleRow(
                        title = "Provider",
                        icon = Icons.Rounded.Cloud,
                        trailingText = provider.name,
                        showChevron = true,
                        showDivider = false,
                        onClick = { providerMenu = true }
                    )
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        ProviderRegistry.all.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        p.name,
                                        color = if (p.id == providerId) c.blue else c.label,
                                        fontWeight = if (p.id == providerId) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    providerMenu = false
                                    availableModels = emptyList()
                                    scope.launch { prefs.saveProviderId(p.id) }
                                }
                            )
                        }
                    }
                }
            }

            AppleSectionHeader("API key")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "Paste your ${provider.name} key",
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                null,
                                tint = c.secondaryLabel
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                AppleButton("Load free models", { refreshModels() }, style = AppleButtonStyle.Tinted, loading = isLoading, modifier = Modifier.fillMaxWidth())
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
                }
            }

            AppleSectionHeader("Model")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Box {
                    AppleRow(
                        title = "Active model",
                        subtitle = if (model.isBlank()) "None selected" else (availableModels.firstOrNull { it.id == model }?.name ?: model),
                        icon = Icons.Rounded.Memory,
                        showChevron = availableModels.isNotEmpty(),
                        showDivider = false,
                        onClick = { if (availableModels.isNotEmpty()) modelMenu = true }
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        availableModels.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.name, color = if (m.id == model) c.blue else c.label) },
                                onClick = { modelMenu = false; model = m.id }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AppleTextField(value = model, onValueChange = { model = it }, placeholder = "model id", label = "Model id")
            }

            AppleSectionHeader("Termux proxy")
            AppleGroup {
                AppleSwitchRow(
                    title = "Route via Termux",
                    subtitle = "Model calls go through your local server",
                    checked = proxyEnabled,
                    onCheckedChange = { proxyEnabled = it }
                )
                Column(Modifier.padding(16.dp)) {
                    AppleTextField(value = proxyUrl, onValueChange = { proxyUrl = it }, placeholder = "http://127.0.0.1:8080/v1")
                }
            }

            AppleSectionHeader("Web search")
            AppleGroup {
                Box {
                    AppleRow(
                        title = "Search provider",
                        icon = Icons.Rounded.Search,
                        trailingText = searchProvider,
                        showChevron = true,
                        showDivider = false,
                        onClick = { searchMenu = true }
                    )
                    DropdownMenu(expanded = searchMenu, onDismissRequest = { searchMenu = false }) {
                        listOf("TAVILY", "FIRECRAWL", "DUCKDUCKGO").forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key, color = if (searchProvider == key) c.blue else c.label) },
                                onClick = { searchMenu = false; searchProvider = key }
                            )
                        }
                    }
                }
            }
            when (searchProvider) {
                "TAVILY" -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AppleTextField(
                        value = tavilyKey, onValueChange = { tavilyKey = it }, placeholder = "tvly-…", label = "Tavily API key",
                        visualTransformation = if (tavilyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { tavilyVisible = !tavilyVisible }) {
                                Icon(if (tavilyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = c.secondaryLabel)
                            }
                        }
                    )
                }
                "FIRECRAWL" -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AppleTextField(
                        value = firecrawlKey, onValueChange = { firecrawlKey = it }, placeholder = "fc-…", label = "Firecrawl API key",
                        visualTransformation = if (firecrawlVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { firecrawlVisible = !firecrawlVisible }) {
                                Icon(if (firecrawlVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = c.secondaryLabel)
                            }
                        }
                    )
                }
            }

            AppleSectionHeader("System prompt")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = "Leave empty for the default Featurestic prompt",
                    singleLine = false,
                    maxLines = 8
                )
            }

            AppleSectionHeader("Generation")
            AppleGroup {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Temperature", style = MaterialTheme.typography.bodyLarge, color = c.label)
                        Text("%.2f".format(temperature), style = MaterialTheme.typography.bodyLarge, color = c.blue, fontWeight = FontWeight.SemiBold)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        onValueChangeFinished = { scope.launch { prefs.saveTemperature(temperature) } },
                        valueRange = 0f..2f,
                        steps = 39,
                        colors = SliderDefaults.colors(
                            thumbColor = c.blue,
                            activeTrackColor = c.blue,
                            inactiveTrackColor = c.fill
                        )
                    )
                    Text("0 = precise • 0.7 = balanced • 2 = creative", style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
                }
            }

            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleButton("Save changes", { saveAll() }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
