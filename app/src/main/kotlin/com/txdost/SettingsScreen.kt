package com.txdost.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.txdost.data.AppPreferences
import com.txdost.data.LlmClient
import com.txdost.data.ModelInfo
import com.txdost.data.ProviderRegistry
import com.txdost.data.ProviderSpec
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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

    val keys = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        ProviderRegistry.all.forEach { spec -> keys[spec.id] = prefs.apiKeyFor(spec.id).first() }
    }

    val providerId by remember { prefs.providerId }.collectAsState(initial = ProviderRegistry.default.id)
    val activeModel by remember(providerId) { prefs.modelFor(providerId) }.collectAsState(initial = "")

    val systemPromptStored by remember { prefs.systemPrompt }.collectAsState(initial = "")
    val searchStored by remember { prefs.webSearchProvider }.collectAsState(initial = "DUCKDUCKGO")
    val tavilyStored by remember { prefs.tavilyApiKey }.collectAsState(initial = "")
    val firecrawlStored by remember { prefs.firecrawlApiKey }.collectAsState(initial = "")
    val themeStored by remember { prefs.themeMode }.collectAsState(initial = "system")
    val showCommandsStored by remember { prefs.showCommands }.collectAsState(initial = true)

    var systemPrompt by remember { mutableStateOf("") }
    var searchProvider by remember { mutableStateOf("DUCKDUCKGO") }
    var tavilyKey by remember { mutableStateOf("") }
    var firecrawlKey by remember { mutableStateOf("") }
    var tavilyVisible by remember { mutableStateOf(false) }
    var firecrawlVisible by remember { mutableStateOf(false) }
    var searchMenu by remember { mutableStateOf(false) }

    LaunchedEffect(systemPromptStored) { systemPrompt = systemPromptStored }
    LaunchedEffect(searchStored) { searchProvider = searchStored }
    LaunchedEffect(tavilyStored) { tavilyKey = tavilyStored }
    LaunchedEffect(firecrawlStored) { firecrawlKey = firecrawlStored }

    var modelsByProvider by remember { mutableStateOf<Map<String, List<ModelInfo>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    val providerStatus = remember { mutableStateMapOf<String, String>() }
    var showResetDialog by remember { mutableStateOf(false) }

    val appVersion = remember {
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "v${pi.versionName} (build $code)"
        } catch (e: Exception) { "" }
    }

    val connected = chatViewModel?.uiState?.value?.termuxConnected == true
    val activeProviderName = ProviderRegistry.byId(providerId).name

    fun saveCatalogToPrefs(map: Map<String, List<ModelInfo>>) {
        val root = JSONObject()
        map.forEach { (pid, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(it.id) }
            root.put(pid, arr)
        }
        scope.launch { prefs.saveModelCatalog(root.toString()) }
    }

    fun connect() {
        isLoading = true
        status = "Connecting…"
        val snapshot = ProviderRegistry.all.associateWith { keys[it.id].orEmpty().trim() }
        scope.launch {
            snapshot.forEach { (spec, key) -> if (key.isNotEmpty()) prefs.saveApiKeyFor(spec.id, key) }
            val results = coroutineScope {
                snapshot.map { (spec, key) ->
                    async {
                        if (key.isBlank()) spec.id to emptyList<ModelInfo>()
                        else spec.id to LlmClient().getModels(spec, key).getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            val map = LinkedHashMap<String, List<ModelInfo>>()
            results.forEach { (id, list) -> if (list.isNotEmpty()) map[id] = list }
            modelsByProvider = map
            isLoading = false
            saveCatalogToPrefs(map)
            val total = map.values.sumOf { it.size }
            status = if (total == 0) "No models found — check your API keys"
            else "Connected · $total free model(s) from ${map.size} provider(s)"
        }
    }

    fun connectOne(spec: ProviderSpec) {
        val key = keys[spec.id].orEmpty().trim()
        if (key.isBlank()) { providerStatus[spec.id] = "Enter a key first"; return }
        loadingId = spec.id
        providerStatus[spec.id] = "Connecting…"
        scope.launch {
            prefs.saveApiKeyFor(spec.id, key)
            val list = LlmClient().getModels(spec, key).getOrDefault(emptyList())
            val map = modelsByProvider + (spec.id to list)
            modelsByProvider = map
            providerStatus[spec.id] = if (list.isEmpty()) "No free models (check key)" else "${list.size} free model(s)"
            saveCatalogToPrefs(map)
            loadingId = null
        }
    }

    var autoTried by remember { mutableStateOf(false) }
    LaunchedEffect(keys.size) {
        if (!autoTried && keys.values.any { it.isNotBlank() }) {
            autoTried = true
            connect()
        }
    }

    fun saveSettings() {
        scope.launch {
            ProviderRegistry.all.forEach { spec -> prefs.saveApiKeyFor(spec.id, keys[spec.id].orEmpty().trim()) }
            prefs.saveSystemPrompt(systemPrompt)
            prefs.saveWebSearchProvider(searchProvider)
            prefs.saveTavilyApiKey(tavilyKey.trim())
            prefs.saveFirecrawlApiKey(firecrawlKey.trim())
            status = "Settings saved"
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportJson().toByteArray()) }
                status = "Exported"
            } catch (e: Exception) { status = "Export failed: ${e.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                prefs.importJson(text)
                keys.clear()
                ProviderRegistry.all.forEach { spec -> keys[spec.id] = prefs.apiKeyFor(spec.id).first() }
                status = "Imported"
            } catch (e: Exception) { status = "Import failed: ${e.message}" }
        }
    }

    // ── External agent (opencode) ────────────────────────────────
    val bridge = remember { com.txdost.data.TermuxBridge(ctx) }
    var extInstalled by remember { mutableStateOf<Boolean?>(null) }
    var extBusy by remember { mutableStateOf(false) }
    var extLog by remember { mutableStateOf("") }
    fun checkExt() {
        scope.launch {
            extBusy = true
            extLog = "Checking..."
            val r = bridge.runCommandAndGetOutput("command -v opencode 2>/dev/null || echo NO")
            extInstalled = r.output.contains("/opencode")
            extLog = if (extInstalled == true) "Installed" else "Not installed"
            extBusy = false
        }
    }
    fun installExt() {
        scope.launch {
            extBusy = true
            extLog = "Installing nodejs (may take a few minutes)..."
            bridge.installPackage("nodejs")
            extLog = "Installing opencode-termux via npm..."
            bridge.runCommandAndGetOutput("npm install -g opencode-termux", 600)
            val r = bridge.runCommandAndGetOutput("command -v opencode 2>/dev/null || echo NO")
            extInstalled = r.output.contains("/opencode")
            extLog = if (extInstalled == true) "Installed" else "Install finished — not detected yet. Try Check."
            extBusy = false
        }
    }
    LaunchedEffect(Unit) { checkExt() }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(title = "Settings", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp)
        ) {

            // ── Termux ─────────────────────────────────────────
            AppleSectionHeader("Termux — runs your tools")
            AppleGroup {
                AppleRow(
                    title = "Server",
                    subtitle = if (connected) "Connected — tools ready" else "Not connected",
                    icon = Icons.Rounded.Terminal,
                    iconTint = if (connected) c.green else c.gray,
                    trailingText = if (connected) "Online" else "Offline",
                    valueColor = if (connected) c.green else c.secondaryLabel,
                    showDivider = true
                )
                Box(Modifier.padding(16.dp)) {
                    AppleButton(
                        "Connect & run tools",
                        { onNavigateToTermuxSetup() },
                        Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Bolt
                    )
                }
            }

            // ── Providers ──────────────────────────────────────
            AppleSectionHeader("AI providers — paste one or more keys")
            ProviderRegistry.all.forEach { spec ->
                ProviderKeyCard(
                    spec = spec,
                    value = keys[spec.id].orEmpty(),
                    onValueChange = {
                        keys[spec.id] = it
                        scope.launch { prefs.saveApiKeyFor(spec.id, it.trim()) }
                    },
                    isActive = spec.id == providerId,
                    status = providerStatus[spec.id],
                    isConnecting = loadingId == spec.id,
                    onConnect = { connectOne(spec) }
                )
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            // ── Models ─────────────────────────────────────────
            AppleSectionHeader("Models")
            AppleGroup {
                AppleRow(
                    title = "Active model",
                    subtitle = if (activeModel.isBlank()) "None selected — tap to choose"
                    else "$activeProviderName · $activeModel",
                    icon = Icons.Rounded.Memory,
                    showChevron = true,
                    showDivider = false,
                    onClick = {
                        if (modelsByProvider.isEmpty()) connect()
                        showPicker = true
                    }
                )
            }

            // ── System prompt ──────────────────────────────────
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

            // ── Web search ─────────────────────────────────────
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

            AppleSectionHeader("External agent")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "opencode",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.label,
                        modifier = Modifier.weight(1f)
                    )
                    AppleBadge(
                        text = when (extInstalled) {
                            true -> "Installed"; false -> "Not installed"; null -> "…"
                        },
                        active = extInstalled == true
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bade tasks (poori app banane + build) ke liye app ek full coding agent " +
                        "ko Termux me chalata hai. Aapki wahi API key use hoti hai — alag key nahi chahiye.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.secondaryLabel
                )
                if (extLog.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(extLog, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    AppleButton(
                        "Install",
                        { installExt() },
                        Modifier.weight(1f),
                        style = AppleButtonStyle.Tinted,
                        enabled = !extBusy
                    )
                    Spacer(Modifier.width(10.dp))
                    AppleButton(
                        "Check",
                        { checkExt() },
                        Modifier.weight(1f),
                        style = AppleButtonStyle.Tinted,
                        enabled = !extBusy,
                        loading = extBusy
                    )
                }
            }

            AppleSectionHeader("Chat")
            AppleSwitchRow(
                title = "Terminal commands dikhao",
                subtitle = "Off karne par sirf jawab dikhega, command boxes chhup jayenge",
                checked = showCommandsStored,
                onCheckedChange = { scope.launch { prefs.saveShowCommands(it) } }
            )

            AppleSectionHeader("Appearance")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleSegmentedControl(
                    options = listOf("System", "Light", "Dark"),
                    selectedIndex = when (themeStored) { "light" -> 1; "dark" -> 2; else -> 0 },
                    onSelect = { idx ->
                        val mode = when (idx) { 1 -> "light"; 2 -> "dark"; else -> "system" }
                        scope.launch { prefs.saveThemeMode(mode) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AppleSectionHeader("Backup")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    AppleButton(
                        "Export",
                        { exportLauncher.launch("featurestic-backup.json") },
                        Modifier.weight(1f),
                        style = AppleButtonStyle.Tinted
                    )
                    Spacer(Modifier.width(10.dp))
                    AppleButton(
                        "Import",
                        { importLauncher.launch(arrayOf("application/json")) },
                        Modifier.weight(1f),
                        style = AppleButtonStyle.Tinted
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Backup ya restore: API keys, models, prompt aur skills ek JSON file me.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.secondaryLabel
                )
            }

            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleButton("Save prompt & search", { saveSettings() }, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(28.dp))
            AppleSectionHeader("Danger zone")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleButton(
                    "Reset all data",
                    { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = AppleButtonStyle.Destructive
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Featurestic AI Terminal $appVersion",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = c.secondaryLabel
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showResetDialog) {
        AppleAlertDialog(
            title = "Reset all data?",
            onDismiss = { showResetDialog = false },
            confirmText = "Reset",
            onConfirm = {
                showResetDialog = false
                scope.launch {
                    prefs.clearAll()
                    keys.clear()
                    systemPrompt = ""; searchProvider = "DUCKDUCKGO"
                    tavilyKey = ""; firecrawlKey = ""
                    status = "All data reset"
                }
            },
            destructive = true
        ) {
            Text(
                "Saari API keys, models, prompt, skills aur settings delete ho jaayengi. Ye undo nahi hota.",
                color = c.secondaryLabel
            )
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            modelsByProvider = modelsByProvider,
            activeProviderId = providerId,
            activeModelId = activeModel,
            onDismiss = { showPicker = false },
            onPick = { pid, mid ->
                scope.launch {
                    prefs.saveProviderId(pid)
                    prefs.saveModelFor(pid, mid)
                }
                showPicker = false
            }
        )
    }
}

// ── Provider key card ─────────────────────────────────────────
@Composable
private fun ProviderKeyCard(
    spec: ProviderSpec,
    value: String,
    onValueChange: (String) -> Unit,
    isActive: Boolean,
    status: String? = null,
    isConnecting: Boolean = false,
    onConnect: () -> Unit = {}
) {
    val c = Apple.colors
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        AppleCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.titleSmall, color = c.label)
                Spacer(Modifier.weight(1f))
                if (isActive) AppleBadge("Active")
            }
            Spacer(Modifier.height(8.dp))
            AppleTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = if (spec.keyHint.isNotBlank()) spec.keyHint else "Paste API key",
                label = "API key",
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = c.secondaryLabel)
                    }
                }
            )
            if (value.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppleButton(
                        "Connect",
                        onConnect,
                        style = AppleButtonStyle.Tinted,
                        icon = Icons.Rounded.Bolt,
                        loading = isConnecting,
                        enabled = !isConnecting
                    )
                    if (status != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(status, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
                    }
                }
            } else if (status != null) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
            }
        }
    }
}

// ── Model picker ──────────────────────────────────────────────
@Composable
private fun ModelPickerDialog(
    modelsByProvider: Map<String, List<ModelInfo>>,
    activeProviderId: String,
    activeModelId: String,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit
) {
    val c = Apple.colors
    var query by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = c.bg, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize()) {
                AppleTopBar(title = "Select model", subtitle = "Tap to switch", onBack = onDismiss)
                Box(Modifier.padding(16.dp)) {
                    AppleTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search all models…",
                        label = "Search",
                        trailing = { Icon(Icons.Rounded.Search, null, tint = c.secondaryLabel) }
                    )
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    modelsByProvider.forEach { (pid, list) ->
                        val spec = ProviderRegistry.byId(pid)
                        val filtered = list.filter {
                            query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true)
                        }
                        if (filtered.isNotEmpty()) {
                            item(key = "h_$pid") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(spec.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
                                    Spacer(Modifier.width(6.dp))
                                    Text("(${filtered.size})", style = MaterialTheme.typography.labelSmall, color = c.tertiaryLabel)
                                }
                            }
                            items(filtered, key = { "$pid:${it.id}" }) { m ->
                                ModelRow(
                                    model = m,
                                    selected = pid == activeProviderId && m.id == activeModelId,
                                    onClick = { onPick(pid, m.id) }
                                )
                            }
                        }
                    }
                    if (modelsByProvider.values.all { list ->
                            query.isBlank() || list.none { it.id.contains(query, true) || it.name.contains(query, true) }
                        }
                    ) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("No matching models", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val c = Apple.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) c.blue else c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (model.name != model.id) {
                Text(model.id, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, null, tint = c.blue)
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
}
