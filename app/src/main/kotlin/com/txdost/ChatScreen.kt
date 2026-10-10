package com.txdost.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.R
import com.txdost.data.ChatMessage
import com.txdost.data.Conversation
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToSkills: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    val c = Apple.colors

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    if (mime.startsWith("image/")) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        chatViewModel.setAttachment(name, mime, b64)
                    } else {
                        chatViewModel.attachTextFile(name, String(bytes, Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) { }
        }
    }

    val totalItems = uiState.messages.size + if (uiState.streamingText.isNotEmpty()) 1 else 0

    LaunchedEffect(uiState.messages.size) {
        if (totalItems > 0) coroutineScope.launch { listState.animateScrollToItem(totalItems - 1) }
    }
    LaunchedEffect(uiState.streamingText.length) {
        if (uiState.streamingText.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= totalItems - 3) coroutineScope.launch { listState.animateScrollToItem(totalItems - 1) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Column(Modifier.fillMaxSize()) {

            ChatNavBar(
                online = uiState.ollamaOnline,
                subtitle = uiState.currentModel.ifBlank { uiState.providerId },
                onHistory = { chatViewModel.toggleHistoryDrawer() },
                onNewChat = { chatViewModel.newConversation() },
                onClear = { showClearDialog = true },
                onSchedule = { showScheduleDialog = true },
                onFiles = onNavigateToFiles,
                onSkills = onNavigateToSkills,
                onTerminal = onNavigateToTerminal,
                onSettings = onNavigateToSettings
            )

            Column(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppleSegmentedControl(
                        options = listOf("Build", "Plan"),
                        selectedIndex = if (uiState.agentMode == "plan") 1 else 0,
                        onSelect = { chatViewModel.setAgentMode(if (it == 1) "plan" else "build") },
                        modifier = Modifier.width(150.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.secondaryBg)
                            .clickable { onNavigateToSkills() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = c.blue, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Skills", style = MaterialTheme.typography.labelMedium, color = c.label)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AppleScrollingChips(
                    items = buildList {
                        add(("Cloud" to uiState.ollamaOnline))
                        add(("Termux" to uiState.termuxConnected))
                        add((uiState.providerId.replaceFirstChar { it.uppercase() } to true))
                        if (uiState.agentMode == "plan") add(("Plan" to true))
                        val skillCount = uiState.skills.count { it.enabled }
                        if (skillCount > 0) add(("$skillCount skill${if (skillCount > 1) "s" else ""}" to true))
                        if (uiState.activeProcesses.isNotEmpty()) add(("${uiState.activeProcesses.size} running" to true))
                    },
                    modifier = Modifier.padding(horizontal = 0.dp)
                )
            }

            if (uiState.selectedFileContext.isNotEmpty()) {
                ContextBanner(onClear = { chatViewModel.clearFileContext() })
            }

            uiState.error?.let { err ->
                ErrorBanner(err)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.messages.isEmpty() && uiState.streamingText.isEmpty()) {
                    item { EmptyChatState(termuxConnected = uiState.termuxConnected) }
                }
                val lastAssistantIdx = uiState.messages.indexOfLast { it.role == "assistant" }
                itemsIndexed(uiState.messages) { idx, msg ->
                    MessageBubble(
                        message = msg,
                        onRegenerate = if (idx == lastAssistantIdx && !uiState.isGenerating)
                            ({ chatViewModel.regenerate() }) else null
                    )
                }
                if (uiState.streamingText.isNotEmpty()) {
                    item { MessageBubble(ChatMessage("assistant", uiState.streamingText), isStreaming = true) }
                }
                if (uiState.isGenerating && uiState.streamingText.isEmpty()) {
                    item { TypingBubble() }
                }
            }

            ChatInputBar(
                value = uiState.currentInput,
                onValueChange = { chatViewModel.updateInput(it) },
                onSend = { chatViewModel.sendMessage(uiState.currentInput) },
                onStop = { chatViewModel.stopCurrentTask() },
                isGenerating = uiState.isGenerating,
                enabled = uiState.ollamaOnline,
                attachmentName = uiState.pendingFileName,
                attachmentIsImage = !uiState.pendingImageBase64.isNullOrBlank(),
                onAttach = { attachLauncher.launch("*/*") },
                onRemoveAttachment = { chatViewModel.clearAttachment() }
            )
        }

        if (uiState.showHistoryDrawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { chatViewModel.closeHistoryDrawer() })
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.84f)
                    .align(Alignment.CenterStart)
                    .background(c.bg)
                    .clickable(enabled = false) {}
            ) {
                HistoryDrawer(
                    conversations = uiState.conversations,
                    activeConvId = uiState.activeConvId,
                    historyManager = chatViewModel.historyManager,
                    onNewChat = { chatViewModel.newConversation() },
                    onSelect = { chatViewModel.switchConversation(it) },
                    onDelete = { chatViewModel.deleteConversation(it) },
                    onRename = { id, title -> chatViewModel.renameConversation(id, title) },
                    onPin = { id, pinned -> chatViewModel.pinConversation(id, pinned) },
                    onClose = { chatViewModel.closeHistoryDrawer() }
                )
            }
        }
    }

    if (showScheduleDialog) {
        ScheduleResearchDialog(
            onDismiss = { showScheduleDialog = false },
            onSchedule = { topic, triggerMs, repeat ->
                chatViewModel.scheduleResearch(topic, triggerMs, repeat)
                showScheduleDialog = false
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = c.secondaryBg,
            title = { Text("Clear chat?", style = MaterialTheme.typography.titleMedium, color = c.label) },
            text = { Text("Aapki saari messages hata di jayengi.", color = c.secondaryLabel) },
            confirmButton = {
                TextButton(onClick = { chatViewModel.clearChat(); showClearDialog = false }) {
                    Text("Clear", color = c.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = c.blue)
                }
            }
        )
    }
}

// ── Nav bar ──────────────────────────────────────────────────

@Composable
private fun ChatNavBar(
    online: Boolean,
    subtitle: String,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onClear: () -> Unit,
    onSchedule: () -> Unit,
    onFiles: () -> Unit,
    onSkills: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit
) {
    val c = Apple.colors
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            IconButton(onClick = onHistory, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(Icons.Rounded.History, "History", tint = c.blue, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Featurestic", style = MaterialTheme.typography.titleMedium, color = c.label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (online) c.green else c.gray))
                    Spacer(Modifier.width(5.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreHoriz, "More", tint = c.blue, modifier = Modifier.size(26.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    AppleMenuItem("New chat", Icons.Rounded.Add) { menu = false; onNewChat() }
                    AppleMenuItem("Terminal", Icons.Rounded.Terminal) { menu = false; onTerminal() }
                    AppleMenuItem("Files", Icons.Rounded.Folder) { menu = false; onFiles() }
                    AppleMenuItem("Skills", Icons.Rounded.AutoAwesome) { menu = false; onSkills() }
                    AppleMenuItem("Schedule", Icons.Rounded.Schedule) { menu = false; onSchedule() }
                    AppleMenuItem("Settings", Icons.Rounded.Settings) { menu = false; onSettings() }
                    HorizontalDivider()
                    AppleMenuItem("Clear chat", Icons.Rounded.Delete, danger = true) { menu = false; onClear() }
                }
            }
        }
        HorizontalDivider(color = c.separator, thickness = 0.5.dp)
    }
}

@Composable
private fun AppleMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val c = Apple.colors
    DropdownMenuItem(
        text = { Text(text, color = if (danger) c.red else c.label) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = if (danger) c.red else c.blue) }
    )
}

// ── Banners ──────────────────────────────────────────────────

@Composable
private fun ContextBanner(onClear: () -> Unit) {
    val c = Apple.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.blue.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.InsertDriveFile, null, tint = c.blue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("File context loaded", color = c.blue, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            "Clear",
            color = c.blue,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onClear() }
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val c = Apple.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.red.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.red, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = c.red, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Message bubble (iMessage style) ──────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    onRegenerate: (() -> Unit)? = null
) {
    val c = Apple.colors
    val isUser = message.role == "user"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            AiAvatar()
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier
                .then(if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f))
                .fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                Modifier
                    .then(if (isUser) Modifier else Modifier.fillMaxWidth())
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp
                        )
                    )
                    .background(if (isUser) c.userBubble else c.aiBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (!message.imageBase64.isNullOrBlank()) {
                        val bmp = remember(message.imageBase64) {
                            try {
                                val bytes = Base64.decode(message.imageBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) { null }
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = message.fileName,
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            if (message.content.isNotBlank())
                                Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (message.content.isNotBlank()) {
                        FormattedMessage(
                            text = message.content + if (isStreaming) " ▌" else "",
                            isUser = isUser
                        )
                    }
                }
            }
                if (onRegenerate != null && !isUser) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRegenerate() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = c.secondaryLabel,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Regenerate",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.secondaryLabel
                        )
                    }
                }
        }
    }
}

@Composable
private fun AiAvatar() {
    val c = Apple.colors
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.blue, c.indigo))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FormattedMessage(text: String, isUser: Boolean) {
    val c = Apple.colors
    val segments = parseMessageSegments(text)
    val bodyColor = if (isUser) Color.White else c.label

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment.type) {
                SegmentType.CODE -> CodeBlock(segment)
                SegmentType.COMMAND -> CommandBlock(segment, isUser)
                SegmentType.STATUS -> StatusRow(segment.content, isUser)
                SegmentType.NORMAL -> if (segment.content.isNotBlank()) LinkText(segment.content, bodyColor)
            }
        }
    }
}

@Composable
private fun CodeBlock(segment: MessageSegment) {
    val c = Apple.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSystemInDarkTheme()) Color(0xFF141416) else Color(0xFFF7F7F9))
            .border(0.5.dp, c.separator, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                segment.language.ifEmpty { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = c.secondaryLabel
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (copied) "Copied" else "Copy",
                style = MaterialTheme.typography.labelSmall,
                color = c.blue,
                modifier = Modifier.clickable {
                    copyToClipboard(context, segment.content)
                    copied = true
                }
            )
        }
        HorizontalDivider(color = c.separator, thickness = 0.5.dp)
        SelectionContainer {
            Text(
                segment.content,
                style = com.txdost.ui.theme.MonospaceStyle,
                color = if (isSystemInDarkTheme()) Color(0xFFE5E5EA) else Color(0xFF1C1C1E),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CommandBlock(segment: MessageSegment, isUser: Boolean) {
    val c = Apple.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val chatVm: ChatViewModel = viewModel()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isUser) Color.White.copy(alpha = 0.14f) else c.tertiaryFill)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            segment.content,
            style = com.txdost.ui.theme.MonospaceStyle,
            color = if (isUser) Color.White else c.label,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ContentCopy,
            "Copy",
            tint = if (isUser) Color.White.copy(alpha = 0.8f) else c.blue,
            modifier = Modifier.size(18.dp).clickable { copyToClipboard(context, segment.content); copied = true }
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.blue.copy(alpha = 0.15f))
                .clickable(enabled = !running && !chatVm.uiState.value.isGenerating) {
                    running = true
                    chatVm.runCommandDirect(segment.content)
                }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, null, tint = c.blue, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(if (running) "Running" else "Run", color = c.blue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatusRow(content: String, isUser: Boolean) {
    val c = Apple.colors
    val ok = content.contains("✅") || content.contains("✓")
    if (isUser) {
        Text(content, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    } else {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background((if (ok) c.green else c.red).copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                tint = if (ok) c.green else c.red,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(content, color = if (ok) c.green else c.red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LinkText(text: String, baseColor: Color) {
    val c = Apple.colors
    val urlRegex = remember { Regex("""(https?://[^\s\)\"<>]+)""") }
    val annotated = remember(text, baseColor) {
        buildAnnotatedString {
            var lastIndex = 0
            urlRegex.findAll(text).forEach { match ->
                append(renderInlineMarkdownRaw(text.substring(lastIndex, match.range.first), baseColor))
                withLink(
                    LinkAnnotation.Url(
                        match.value,
                        TextLinkStyles(SpanStyle(color = c.blue, textDecoration = TextDecoration.Underline))
                    )
                ) { append(match.value) }
                lastIndex = match.range.last + 1
            }
            append(renderInlineMarkdownRaw(text.substring(lastIndex), baseColor))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge.copy(color = baseColor))
}

// ── Typing indicator ─────────────────────────────────────────

@Composable
private fun TypingBubble() {
    val c = Apple.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(c.aiBubble)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val phase by produceState(0) {
                while (true) { kotlinx.coroutines.delay(350); value = (value + 1) % 3 }
            }
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c.secondaryLabel.copy(alpha = if (i == phase) 1f else 0.35f))
                )
            }
        }
    }
}

// ── Empty state ──────────────────────────────────────────────

@Composable
private fun EmptyChatState(termuxConnected: Boolean) {
    val c = Apple.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = "Featurestic AI",
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
        )
        Spacer(Modifier.height(18.dp))
        Text("Featurestic AI", style = MaterialTheme.typography.titleLarge, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text(
            if (termuxConnected) "Termux connected — terminal commands, files aur packages sab ready hain."
            else "Termux se connect karo taaki main aapke device pe commands chala sakoon.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.secondaryLabel,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "Downloads folder list karo",
            "Ek python script likh ke chalao",
            "Git install karo"
        ).forEach { s ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.secondaryBg)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("“$s”", style = MaterialTheme.typography.bodyMedium, color = c.label)
            }
        }
    }
}

// ── Input bar ────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
    attachmentName: String? = null,
    attachmentIsImage: Boolean = false,
    onAttach: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {}
) {
    val c = Apple.colors
    Surface(color = c.bg, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (attachmentName != null) {
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.secondaryBg)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (attachmentIsImage) Icons.Rounded.Image else Icons.Rounded.InsertDriveFile,
                    null, tint = c.blue, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    attachmentName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.label,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = onRemoveAttachment, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", tint = c.secondaryLabel, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.secondaryBg)
                    .clickable(enabled = enabled && !isGenerating) { onAttach() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AttachFile, "Attach", tint = if (enabled) c.blue else c.tertiaryLabel, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (enabled) "Message" else "Offline", color = c.tertiaryLabel, style = MaterialTheme.typography.bodyLarge)
                },
                maxLines = 5,
                enabled = enabled && !isGenerating,
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.secondaryBg,
                    unfocusedContainerColor = c.secondaryBg,
                    disabledContainerColor = c.secondaryBg,
                    focusedBorderColor = c.separator,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = c.label,
                    unfocusedTextColor = c.label,
                    cursorColor = c.blue
                )
            )
            Spacer(Modifier.width(8.dp))
            val canSend = (value.isNotBlank() || attachmentName != null) && enabled && !isGenerating
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGenerating) c.red
                        else if (canSend) c.blue
                        else c.fill
                    )
                    .clickable(enabled = isGenerating || canSend) {
                        if (isGenerating) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGenerating) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = if (isGenerating || canSend) Color.White else c.tertiaryLabel,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        }
    }
}

// ── History drawer ───────────────────────────────────────────

@Composable
fun HistoryDrawer(
    conversations: List<Conversation>,
    activeConvId: String,
    historyManager: com.txdost.data.ChatHistoryManager,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onPin: (String, Boolean) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    val c = Apple.colors
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Text(
                    "Chats",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.label,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Close, "Close", tint = c.blue)
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.blue)
                .clickable { onNewChat() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New chat", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AppleTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search chats…",
                label = null
            )
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (conversations.isEmpty()) "No chats yet" else "No matches",
                    color = c.secondaryLabel,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(filtered, key = { it.id }) { conv ->
                    val isActive = conv.id == activeConvId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) c.blue.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSelect(conv.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                conv.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isActive) c.blue else c.label,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                historyManager.formatDate(conv.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.secondaryLabel
                            )
                        }
                        IconButton(onClick = { onPin(conv.id, !conv.pinned) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.PushPin,
                                "Pin",
                                tint = if (conv.pinned) c.blue else c.tertiaryLabel,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { renameTargetId = conv.id; renameText = conv.title }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Edit, "Rename", tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deleteTargetId = conv.id }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Delete, "Delete", tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Text(
            "${conversations.size} saved",
            style = MaterialTheme.typography.labelSmall,
            color = c.tertiaryLabel,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    if (deleteTargetId != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = c.secondaryBg,
            title = { Text("Delete chat?", style = MaterialTheme.typography.titleMedium, color = c.label) },
            text = { Text("Ye conversation permanently delete ho jayegi.", color = c.secondaryLabel) },
            confirmButton = {
                TextButton(onClick = { deleteTargetId?.let { onDelete(it) }; deleteTargetId = null }) {
                    Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text("Cancel", color = c.blue) }
            }
        )
    }

    if (renameTargetId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = c.secondaryBg,
            title = { Text("Rename chat", style = MaterialTheme.typography.titleMedium, color = c.label) },
            text = {
                AppleTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = "Chat name",
                    label = null
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTargetId?.let { onRename(it, renameText) }
                    renameTargetId = null
                }) { Text("Save", color = c.blue, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null }) { Text("Cancel", color = c.blue) }
            }
        )
    }
}

// ── Schedule dialog ──────────────────────────────────────────

@Composable
fun ScheduleResearchDialog(
    onDismiss: () -> Unit,
    onSchedule: (topic: String, triggerMs: Long, repeat: com.txdost.data.RepeatMode) -> Unit
) {
    val c = Apple.colors
    var topic by remember { mutableStateOf("") }
    var timeHint by remember { mutableStateOf("") }
    var delayMinutes by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(com.txdost.data.RepeatMode.ONCE) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = c.secondaryBg,
        title = { Text("Schedule a prompt", style = MaterialTheme.typography.titleMedium, color = c.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppleTextField(topic, { topic = it }, placeholder = "Kya poochna hai?", label = "Prompt", maxLines = 3, singleLine = false)
                AppleTextField(delayMinutes, { delayMinutes = it.filter { ch -> ch.isDigit() } }, placeholder = "e.g. 30", label = "Kitne minute baad", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                Text("Repeat", style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
                AppleSegmentedControl(
                    options = listOf("Once", "Daily", "Weekly"),
                    selectedIndex = when (repeatMode) {
                        com.txdost.data.RepeatMode.ONCE -> 0
                        com.txdost.data.RepeatMode.DAILY -> 1
                        else -> 2
                    },
                    onSelect = {
                        repeatMode = when (it) {
                            0 -> com.txdost.data.RepeatMode.ONCE
                            1 -> com.txdost.data.RepeatMode.DAILY
                            else -> com.txdost.data.RepeatMode.WEEKLY
                        }
                    }
                )
                if (error.isNotEmpty()) Text(error, color = c.red, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = ""
                val mins = delayMinutes.toLongOrNull()
                if (topic.isBlank()) { error = "Prompt khali hai"; return@TextButton }
                if (mins == null || mins <= 0) { error = "Sahi minutes daalo"; return@TextButton }
                onSchedule(topic, System.currentTimeMillis() + mins * 60_000L, repeatMode)
            }) { Text("Schedule", color = c.blue, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.blue) } }
    )
}

// ── Helpers ──────────────────────────────────────────────────

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("featurestic", text))
}

enum class SegmentType { NORMAL, CODE, COMMAND, STATUS }
data class MessageSegment(val type: SegmentType, val content: String, val language: String = "")

fun parseMessageSegments(text: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val lines = text.lines()
    var i = 0
    val currentNormal = StringBuilder()

    fun flushNormal() {
        val s = currentNormal.toString().trim()
        if (s.isNotEmpty()) segments.add(MessageSegment(SegmentType.NORMAL, s))
        currentNormal.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushNormal()
                val lang = line.trim().removePrefix("```").trim()
                val codeLines = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.appendLine(lines[i]); i++
                }
                segments.add(MessageSegment(SegmentType.CODE, codeLines.toString().trimEnd(), lang))
            }
            line.contains("[TERMUX:") || line.trim().startsWith("$ ") -> {
                flushNormal()
                val cmd = line.replace(Regex("""\[TERMUX:(.*?)\]""")) { it.groupValues[1] }.trim()
                segments.add(MessageSegment(SegmentType.COMMAND, cmd))
            }
            line.contains("✅") || line.contains("❌") -> {
                flushNormal()
                segments.add(MessageSegment(SegmentType.STATUS, line.trim()))
            }
            else -> currentNormal.appendLine(line)
        }
        i++
    }
    flushNormal()
    return segments
}

fun renderInlineMarkdownRaw(text: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { withStyle(SpanStyle(color = baseColor)) { append(text[i]) }; i++ }
                }
                text.startsWith("`", i) && !text.startsWith("```", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = baseColor, background = baseColor.copy(alpha = 0.08f))) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { withStyle(SpanStyle(color = baseColor)) { append(text[i]) }; i++ }
                }
                else -> { withStyle(SpanStyle(color = baseColor)) { append(text[i]) }; i++ }
            }
        }
    }
}
