package com.txdost.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.data.ChatMessage
import com.txdost.data.Conversation
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

// ── Hacker Color Palette ────────────────────────────────────
private val HackerBg       = Color(0xFF060D06)
private val HackerBgCard   = Color(0xFF0F1A0F)
private val HackerGreen    = Color(0xFF00FF41)
private val HackerGreenDim = Color(0xFF00C832)
private val HackerCyan     = Color(0xFF00E5FF)
private val HackerYellow   = Color(0xFFFFD700)
private val HackerRed      = Color(0xFFFF1744)
private val HackerGray     = Color(0xFF1A2A1A)
private val HackerBorder   = Color(0xFF003300)
private val HackerUserBg   = Color(0xFF002244)   // Deep blue tint for user
private val HackerAiBg     = Color(0xFF0A120A)   // Deep dark green for AI
private val HackerUserAccent = Color(0xFF00B8FF) // User bubble border
private val HackerAiAccent   = Color(0xFF00FF41) // AI bubble border

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToProcesses: () -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    // Auto-scroll logic:
    // - Naya message aaye (size change) → hamesha bottom pe scroll
    // - Streaming chal raha ho → sirf tab scroll karo jab user already bottom ke paas ho
    val totalItems = uiState.messages.size + if (uiState.streamingText.isNotEmpty()) 1 else 0

    LaunchedEffect(uiState.messages.size) {
        if (totalItems > 0) {
            coroutineScope.launch { listState.animateScrollToItem(totalItems - 1) }
        }
    }

    LaunchedEffect(uiState.streamingText.length) {
        if (uiState.streamingText.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val isNearBottom = lastVisible >= totalItems - 3
            if (isNearBottom) {
                coroutineScope.launch { listState.animateScrollToItem(totalItems - 1) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HackerBg)
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── TopBar ──────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, HackerBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (uiState.ollamaOnline) HackerGreen else HackerRed,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "FEATURESTIC // AI TERMINAL",
                        color = HackerGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    // Action buttons — compact
                    IconButton(onClick = { chatViewModel.toggleHistoryDrawer() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Menu, contentDescription = "History", tint = HackerYellow, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onNavigateToDashboard, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.BarChart, contentDescription = "Dashboard", tint = HackerCyan, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onNavigateToFiles, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Folder, contentDescription = "Files", tint = HackerGreen, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onNavigateToProcesses, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Processes", tint = HackerCyan, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { showClearDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear", tint = HackerRed, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { showScheduleDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DateRange, contentDescription = "Schedule", tint = HackerYellow, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = HackerGreenDim, modifier = Modifier.size(16.dp))
                    }
                }
                // Status pills — second line, horizontal scroll
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    item { StatusPill(text = if (uiState.ollamaOnline) "CLOUD:ON" else "CLOUD:OFF", active = uiState.ollamaOnline) }
                    item { StatusPill(text = if (uiState.termuxConnected) "TERMUX:ON" else "TERMUX:OFF", active = uiState.termuxConnected) }
                    item { StatusPill(text = uiState.providerId.uppercase(), active = true, color = Color(0xFF00E5FF)) }
                    item { StatusPill(text = uiState.currentModel, active = true) }
                    if (uiState.activeProcesses.isNotEmpty()) {
                        item { StatusPill(text = "${uiState.activeProcesses.size} PROC", active = true) }
                    }
                }
            }

            // File context banner
            if (uiState.selectedFileContext.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF001400))
                        .border(BorderStroke(1.dp, HackerGreenDim))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ">> FILE_CTX LOADED",
                        color = HackerGreenDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { chatViewModel.clearFileContext() },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("[CLEAR]", color = HackerRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Error banner
            if (uiState.error != null) {
                Text(
                    text = "!! ERROR: ${uiState.error}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A0000))
                        .border(BorderStroke(1.dp, HackerRed))
                        .padding(8.dp),
                    color = HackerRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ── Messages ────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (uiState.messages.isEmpty() && uiState.streamingText.isEmpty()) {
                    item { EmptyStateHacker() }
                }
                items(uiState.messages) { msg -> HackerMessageBubble(msg) }
                if (uiState.streamingText.isNotEmpty()) {
                    item {
                        HackerMessageBubble(
                            ChatMessage("assistant", uiState.streamingText),
                            isStreaming = true
                        )
                    }
                }
                if (uiState.isGenerating && uiState.streamingText.isEmpty()) {
                    item { HackerTypingIndicator() }
                }
            }

            // ── Input Bar ───────────────────────────────────
            HackerInputBar(
                value = uiState.currentInput,
                onValueChange = { chatViewModel.updateInput(it) },
                onSend = { chatViewModel.sendMessage(uiState.currentInput) },
                onStop = { chatViewModel.stopCurrentTask() },
                isGenerating = uiState.isGenerating,
                enabled = uiState.ollamaOnline
            )
        }

        // ── History Drawer Overlay ──────────────────────────────
        if (uiState.showHistoryDrawer) {
            // Background dim tap to close
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { chatViewModel.closeHistoryDrawer() }
            )
            // Drawer panel — left se slide
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF050F05))
                    .border(BorderStroke(1.dp, HackerGreen.copy(alpha = 0.4f)))
                    .clickable(enabled = false) {}
            ) {
                HistoryDrawer(
                    conversations  = uiState.conversations,
                    activeConvId   = uiState.activeConvId,
                    historyManager = chatViewModel.historyManager,
                    onNewChat      = { chatViewModel.newConversation() },
                    onSelect       = { chatViewModel.switchConversation(it) },
                    onDelete       = { chatViewModel.deleteConversation(it) },
                    onClose        = { chatViewModel.closeHistoryDrawer() }
                )
            }
        }
    }

    // Schedule dialog
    if (showScheduleDialog) {
        ScheduleResearchDialog(
            onDismiss  = { showScheduleDialog = false },
            onSchedule = { topic, triggerMs, repeat ->
                chatViewModel.scheduleResearch(topic, triggerMs, repeat)
                showScheduleDialog = false
            }
        )
    }

    // Clear dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Color(0xFF0A0A0A),
            titleContentColor = HackerRed,
            textContentColor = HackerGreen,
            title = { Text("[ CLEAR SESSION? ]", fontFamily = FontFamily.Monospace) },
            text = { Text("All messages will be wiped from memory.", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { chatViewModel.clearChat(); showClearDialog = false }) {
                    Text("[CONFIRM]", color = HackerRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("[ABORT]", color = HackerGreenDim, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
fun StatusPill(text: String, active: Boolean, color: Color? = null) {
    val pillColor = color ?: if (active) HackerGreen else HackerRed
    Text(
        text = text,
        color = pillColor,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(
                if (active) Color(0xFF003300) else Color(0xFF1A0000),
                RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
fun HackerMessageBubble(message: ChatMessage, isStreaming: Boolean = false) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // ── Header label ──────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = if (isUser) 0.dp else 4.dp,
                end = if (isUser) 4.dp else 0.dp,
                bottom = 3.dp
            )
        ) {
            if (!isUser) {
                // AI avatar dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(HackerGreen, CircleShape)
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (isUser) "YOU" else "FEATURESTIC AI",
                color = if (isUser) HackerUserAccent else HackerGreen,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            if (isUser) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(HackerUserAccent, CircleShape)
                )
            }
        }

        // ── Message bubble ───────────────────────────────────
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 310.dp)
                .background(
                    brush = if (isUser) {
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(0xFF001833), Color(0xFF002244))
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(0xFF061206), Color(0xFF0A180A))
                        )
                    },
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 14.dp else 2.dp,
                        topEnd = if (isUser) 2.dp else 14.dp,
                        bottomStart = 14.dp,
                        bottomEnd = 14.dp
                    )
                )
                .border(
                    BorderStroke(
                        width = if (isStreaming) 1.5.dp else 1.dp,
                        color = if (isUser)
                            HackerUserAccent.copy(alpha = if (isStreaming) 0.8f else 0.5f)
                        else
                            HackerGreen.copy(alpha = if (isStreaming) 0.9f else 0.4f)
                    ),
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 14.dp else 2.dp,
                        topEnd = if (isUser) 2.dp else 14.dp,
                        bottomStart = 14.dp,
                        bottomEnd = 14.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            HackerFormattedText(
                text = message.content + if (isStreaming) "█" else "",
                isUser = isUser
            )
        }
    }
}

@Composable
fun HackerFormattedText(text: String, isUser: Boolean) {
    val segments = parseMessageSegments(text)
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment.type) {

                SegmentType.CODE -> {
                    // ── Premium Code Block ───────────────────
                    var copied by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF020F02), RoundedCornerShape(8.dp))
                            .border(
                                BorderStroke(1.dp, HackerGreenDim.copy(alpha = 0.4f)),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        // Header bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF001800), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Language badge
                            if (segment.language.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .background(HackerGreenDim.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = segment.language.uppercase(),
                                        color = HackerYellow,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            // Dot indicators
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(Color(0xFFFF5F56), Color(0xFFFFBD2E), Color(0xFF27C93F))
                                    .forEach { c ->
                                        Box(Modifier.size(7.dp).background(c, RoundedCornerShape(50)))
                                    }
                            }
                            Spacer(Modifier.weight(1f))
                            // Copy button
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (copied) HackerGreen.copy(alpha = 0.2f) else HackerBorder,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        val clip = android.content.ClipData.newPlainText("code", segment.content)
                                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                            .setPrimaryClip(clip)
                                        copied = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (copied) "✓ Copied" else "⧉ Copy",
                                    color = if (copied) HackerGreen else HackerGreenDim,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        // Code content
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = segment.content,
                                color = HackerGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                SegmentType.COMMAND -> {
                    // ── Premium Command Block with Copy + Run ──
                    var copied by remember { mutableStateOf(false) }
                    val chatVm: ChatViewModel = viewModel()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0900), RoundedCornerShape(6.dp))
                            .border(BorderStroke(1.dp, HackerYellow.copy(alpha = 0.3f)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$", color = HackerYellow, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = segment.content,
                            color = HackerGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        // Copy button
                        Box(
                            modifier = Modifier
                                .background(HackerBorder, RoundedCornerShape(3.dp))
                                .clickable {
                                    val clip = android.content.ClipData.newPlainText("cmd", segment.content)
                                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                        .setPrimaryClip(clip)
                                    copied = true
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (copied) "✓" else "⧉",
                                color = if (copied) HackerGreen else HackerGreenDim,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        // Run button — seedha execute, AI bypass
                        var running by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .background(
                                    if (running) HackerYellow.copy(alpha = 0.15f)
                                    else HackerGreen.copy(alpha = 0.15f),
                                    RoundedCornerShape(3.dp)
                                )
                                .border(
                                    BorderStroke(1.dp,
                                        if (running) HackerYellow.copy(alpha = 0.5f)
                                        else HackerGreen.copy(alpha = 0.4f)
                                    ),
                                    RoundedCornerShape(3.dp)
                                )
                                .clickable(enabled = !running && !chatVm.uiState.value.isGenerating) {
                                    running = true
                                    chatVm.runCommandDirect(segment.content)
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (running) "⏳" else "▶ Run",
                                color = if (running) HackerYellow else HackerGreen,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                SegmentType.STATUS -> {
                    val isOk = segment.content.contains("✅") || segment.content.contains("✓")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isOk) Color(0xFF002800) else Color(0xFF280000),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = segment.content,
                            color = if (isOk) HackerGreen else HackerRed,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                SegmentType.NORMAL -> {
                    if (segment.content.isNotBlank()) {
                        HackerTextWithLinks(text = segment.content, isUser = isUser)
                    }
                }
            }
        }
    }
}

// ── Clickable links wala text ────────────────────────────────
// URLs automatically detect hoti hain aur tap se browser mein khulti hain
@Composable
fun HackerTextWithLinks(text: String, isUser: Boolean) {
    val uriHandler = LocalUriHandler.current
    val urlRegex = remember {
        Regex("""(https?://[^\s\)\"<>]+)""")
    }

    val annotated = buildAnnotatedString {
        var lastIndex = 0
        val baseColor = if (isUser) HackerUserAccent else HackerGreenDim

        // Pehle normal inline markdown parse karo, URLs ke liye alag handle karo
        val fullText = text
        urlRegex.findAll(fullText).forEach { match ->
            val before = fullText.substring(lastIndex, match.range.first)
            // Before part — normal markdown render
            append(renderInlineMarkdownRaw(before, baseColor))

            // URL part — cyan + underline + annotation
            pushStringAnnotation(tag = "URL", annotation = match.value)
            withStyle(SpanStyle(
                color = HackerCyan,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Bold
            )) {
                append(match.value)
            }
            pop()
            lastIndex = match.range.last + 1
        }
        // Remaining text
        if (lastIndex < fullText.length) {
            append(renderInlineMarkdownRaw(fullText.substring(lastIndex), baseColor))
        }
    }

    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try { uriHandler.openUri(annotation.item) } catch (e: Exception) { }
                }
        }
    )
}

// renderInlineMarkdown ka raw version jo SpanStyle return kare (ClickableText ke liye)
fun renderInlineMarkdownRaw(
    text: String,
    baseColor: Color
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(color = HackerYellow, fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { withStyle(SpanStyle(color = baseColor)) { append(text[i]) }; i++ }
                }
                text.startsWith("`", i) && !text.startsWith("```", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(color = HackerGreen, background = Color(0xFF001400), fontFamily = FontFamily.Monospace)) {
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
                    codeLines.appendLine(lines[i])
                    i++
                }
                segments.add(MessageSegment(SegmentType.CODE, codeLines.toString().trimEnd(), lang))
            }
            line.contains("[TERMUX:") || line.trim().startsWith("$ ") -> {
                flushNormal()
                val cmd = line.replace(Regex("""\[TERMUX:(.*?)\]""")) { it.groupValues[1] }.trim()
                segments.add(MessageSegment(SegmentType.COMMAND, cmd))
            }
            line.contains("✅") || line.contains("❌") || line.contains(">>") -> {
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

@Composable
fun HackerTypingIndicator() {
    val dots = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            listOf(".", "..", "...", "....").forEach { d ->
                dots.value = d
                kotlinx.coroutines.delay(300)
            }
        }
    }
    Text(
        text = "< AI@FEATURESTIC :: PROCESSING${dots.value}",
        color = HackerGreen,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(4.dp)
            .background(HackerAiBg, RoundedCornerShape(4.dp))
            .padding(8.dp)
    )
}

@Composable
fun EmptyStateHacker() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = """
 ████████╗██╗  ██╗
    ██╔══╝╚██╗██╔╝
    ██║    ╚███╔╝ 
    ██║    ██╔██╗ 
    ██║   ██╔╝ ██╗
    ╚═╝   ╚═╝  ╚═╝""".trimIndent(),
            color = HackerGreen,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("FEATURESTIC AI TERMINAL v3.0", color = HackerGreenDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        Text("TERMUX BRIDGE ACTIVE", color = HackerCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        listOf(
            ">> Run terminal commands",
            ">> Install packages",
            ">> Create & execute scripts",
            ">> Full Termux control"
        ).forEach {
            Text(it, color = HackerGreen.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HackerInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    isGenerating: Boolean,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF000A00))
            .border(BorderStroke(1.dp, HackerBorder))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = ">>",
            color = HackerGreen,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 6.dp, bottom = 10.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = if (enabled) "Enter command or query..." else "[ OFFLINE ]",
                    color = HackerGreen.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            maxLines = 5,
            enabled = enabled && !isGenerating,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HackerGreen,
                unfocusedTextColor = HackerGreenDim,
                focusedBorderColor = HackerGreen,
                unfocusedBorderColor = HackerBorder,
                cursorColor = HackerGreen,
                focusedContainerColor = Color(0xFF000800),
                unfocusedContainerColor = Color(0xFF000800),
                disabledContainerColor = Color(0xFF050805),
                disabledTextColor = HackerGreen.copy(alpha = 0.3f),
                disabledBorderColor = HackerBorder.copy(alpha = 0.3f)
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = HackerGreen
            ),
            shape = RoundedCornerShape(2.dp),
            trailingIcon = {
                if (isGenerating) {
                    // Red square STOP button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .padding(4.dp)
                            .background(Color(0xFF1A0000), RoundedCornerShape(3.dp))
                            .border(BorderStroke(1.dp, HackerRed), RoundedCornerShape(3.dp))
                            .clickable { onStop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(HackerRed, RoundedCornerShape(2.dp))
                        )
                    }
                } else if (value.isNotBlank() && enabled) {
                    IconButton(onClick = onSend) {
                        Text("[SEND]", color = HackerGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        )
    }
}

// Legacy functions — purane code ke saath compatibility
@Composable
fun MessageBubble(message: ChatMessage, isStreaming: Boolean = false) = HackerMessageBubble(message, isStreaming)

@Composable
fun EmptyStateView(ollamaOnline: Boolean) = EmptyStateHacker()

@Composable
fun StatusChip(text: String, isPositive: Boolean) = StatusPill(text, isPositive)




// ── History Drawer ───────────────────────────────────────────
@Composable
fun HistoryDrawer(
    conversations: List<Conversation>,
    activeConvId: String,
    historyManager: com.txdost.data.ChatHistoryManager,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF001A00))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[ HISTORY ]",
                color = HackerGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = HackerRed, modifier = Modifier.size(16.dp))
            }
        }

        // ── New Chat Button ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .background(Color(0xFF001400), RoundedCornerShape(4.dp))
                .border(BorderStroke(1.dp, HackerGreen.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
                .clickable { onNewChat() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = HackerGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "+ NEW CHAT",
                color = HackerGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // ── Divider ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HackerBorder)
        )

        // ── Conversation List ─────────────────────────────────
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">> No history found",
                    color = HackerGreen.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(conversations, key = { it.id }) { conv ->
                    val isActive = conv.id == activeConvId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .background(
                                if (isActive) Color(0xFF002200) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                BorderStroke(
                                    if (isActive) 1.dp else 0.dp,
                                    if (isActive) HackerGreen.copy(alpha = 0.6f) else Color.Transparent
                                ),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onSelect(conv.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Active indicator
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isActive) HackerGreen else HackerGreen.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conv.title,
                                color = if (isActive) HackerGreen else HackerGreen.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = historyManager.formatDate(conv.updatedAt),
                                color = HackerGreen.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        // Delete button
                        IconButton(
                            onClick = { deleteTargetId = conv.id },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = HackerRed.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Footer ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF000A00))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${conversations.size} conversation(s) saved",
                color = HackerGreen.copy(alpha = 0.35f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // ── Delete Confirm Dialog ─────────────────────────────────
    if (deleteTargetId != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            containerColor   = Color(0xFF0A0A0A),
            titleContentColor = HackerRed,
            textContentColor  = HackerGreen,
            title = { Text("[ DELETE? ]", fontFamily = FontFamily.Monospace) },
            text  = { Text("Yeh conversation permanently delete ho jayegi.", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargetId?.let { onDelete(it) }
                    deleteTargetId = null
                }) {
                    Text("[DELETE]", color = HackerRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) {
                    Text("[CANCEL]", color = HackerGreen, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// ── Schedule Research Dialog ──────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleResearchDialog(
    onDismiss: () -> Unit,
    onSchedule: (topic: String, triggerMs: Long, repeat: com.txdost.data.RepeatMode) -> Unit
) {
    var topic     by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }   // dd/MM/yyyy
    var timeStr   by remember { mutableStateOf("") }   // hh:mm
    var isPm      by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(com.txdost.data.RepeatMode.ONCE) }
    var error     by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF0A0F0A),
        titleContentColor = HackerGreen,
        textContentColor  = HackerGreen,
        title = {
            Text(
                "[ SCHEDULE RESEARCH ]",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Topic
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Research Topic", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    placeholder = { Text("e.g. Bitcoin price analysis", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = HackerGreen.copy(alpha = 0.3f)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    colors = scheduleFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HackerGreen)
                )

                // Date
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = { dateStr = it },
                    label = { Text("Date (dd/MM/yyyy)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    placeholder = { Text("14/04/2026", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = HackerGreen.copy(alpha = 0.3f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = scheduleFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HackerGreen)
                )

                // Time + AM/PM row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Time (hh:mm)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        placeholder = { Text("08:30", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = HackerGreen.copy(alpha = 0.3f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = scheduleFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = HackerGreen)
                    )
                    // AM / PM toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(false to "AM", true to "PM").forEach { (pm, label) ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isPm == pm) HackerGreen.copy(alpha = 0.2f) else Color.Transparent,
                                        RoundedCornerShape(3.dp)
                                    )
                                    .border(BorderStroke(1.dp, if (isPm == pm) HackerGreen else HackerBorder), RoundedCornerShape(3.dp))
                                    .clickable { isPm = pm }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(label, color = if (isPm == pm) HackerGreen else HackerGreen.copy(alpha = 0.4f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Repeat
                Text("Repeat:", color = HackerGreen.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.txdost.data.RepeatMode.ONCE   to "Once",
                        com.txdost.data.RepeatMode.DAILY  to "Daily",
                        com.txdost.data.RepeatMode.WEEKLY to "Weekly"
                    ).forEach { (mode, label) ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (repeatMode == mode) HackerGreen.copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(3.dp)
                                )
                                .border(BorderStroke(1.dp, if (repeatMode == mode) HackerGreen else HackerBorder), RoundedCornerShape(3.dp))
                                .clickable { repeatMode = mode }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(label, color = if (repeatMode == mode) HackerGreen else HackerGreen.copy(alpha = 0.4f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Text(error, color = HackerRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = ""
                if (topic.isBlank())   { error = "Topic khali hai!"; return@TextButton }
                if (dateStr.isBlank()) { error = "Date daalo!"; return@TextButton }
                if (timeStr.isBlank()) { error = "Time daalo!"; return@TextButton }
                val amPm = if (isPm) "PM" else "AM"
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy hh:mm a", java.util.Locale.getDefault())
                val parsed = try { sdf.parse("$dateStr $timeStr $amPm") } catch (e: Exception) { null }
                if (parsed == null) { error = "Date/Time format galat hai!"; return@TextButton }
                if (parsed.time <= System.currentTimeMillis()) { error = "Future time select karo!"; return@TextButton }
                onSchedule(topic.trim(), parsed.time, repeatMode)
            }) {
                Text("[SCHEDULE]", color = HackerGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("[CANCEL]", color = HackerRed, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun scheduleFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor      = HackerGreen,
    unfocusedTextColor    = HackerGreenDim,
    focusedBorderColor    = HackerGreen,
    unfocusedBorderColor  = HackerBorder,
    cursorColor           = HackerGreen,
    focusedContainerColor    = Color(0xFF000800),
    unfocusedContainerColor  = Color(0xFF000800),
    focusedLabelColor     = HackerGreen,
    unfocusedLabelColor   = HackerGreen.copy(alpha = 0.5f)
)
