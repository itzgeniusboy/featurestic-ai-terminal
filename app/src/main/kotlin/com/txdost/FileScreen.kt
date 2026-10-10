package com.txdost.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.data.FileItem
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

private val FBg      = Color(0xFF0A0A0A)
private val FGreen   = Color(0xFF00FF41)
private val FGreenDim= Color(0xFF00C832)
private val FCyan    = Color(0xFF00FFFF)
private val FYellow  = Color(0xFFFFFF00)
private val FRed     = Color(0xFFFF0040)
private val FBorder  = Color(0xFF003300)
private val FCard    = Color(0xFF0A0F0A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel()
) {
    val fileManager = chatViewModel.fileManager
    val coroutineScope = rememberCoroutineScope()

    var currentPath     by remember { mutableStateOf("") }
    var files           by remember { mutableStateOf(fileManager.listFiles(currentPath)) }
    var statusMessage   by remember { mutableStateOf("") }
    var statusOk        by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var editingFile     by remember { mutableStateOf<FileItem?>(null) }
    var viewingFile     by remember { mutableStateOf<FileItem?>(null) }
    var deleteFile      by remember { mutableStateOf<FileItem?>(null) }
    var executeResult   by remember { mutableStateOf("") }
    var newFileName     by remember { mutableStateOf("") }
    var newFileContent  by remember { mutableStateOf("") }
    var editContent     by remember { mutableStateOf("") }
    var newFolderName   by remember { mutableStateOf("") }

    fun refresh() { files = fileManager.listFiles(currentPath) }

    Box(modifier = Modifier.fillMaxSize().background(FBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, FBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (currentPath.isEmpty()) onBack() else { currentPath = currentPath.substringBeforeLast("/",""); refresh() } },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = FGreen, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("[ WORKSPACE ]", color = FGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                        Text(
                            ">> /${if (currentPath.isEmpty()) "" else currentPath}",
                            color = FCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(onClick = { showFolderDialog = true }, modifier = Modifier.size(32.dp)) {
                        Text("[+D]", color = FYellow, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.size(32.dp)) {
                        Text("[+F]", color = FGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { refresh() }, modifier = Modifier.size(32.dp)) {
                        Text("[↻]", color = FGreenDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Status
            if (statusMessage.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (statusOk) Color(0xFF001400) else Color(0xFF1A0000))
                        .border(BorderStroke(1.dp, if (statusOk) FGreen else FRed))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(statusMessage, color = if (statusOk) FGreen else FRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    TextButton(onClick = { statusMessage = "" }, contentPadding = PaddingValues(0.dp)) {
                        Text("[X]", color = FRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("[ EMPTY ]", color = FGreenDim, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        Text(">> No files in workspace", color = FGreenDim.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        OutlinedButton(
                            onClick = { showCreateDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            border = BorderStroke(1.dp, FGreen)
                        ) {
                            Text("[+] NEW FILE", color = FGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(files) { file ->
                        HackerFileRow(
                            file = file,
                            onOpen = {
                                if (file.isDirectory) {
                                    currentPath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
                                    refresh()
                                } else viewingFile = file
                            },
                            onEdit = {
                                if (!file.isDirectory) {
                                    editContent = fileManager.readFile(file.path).getOrElse { "" }
                                    editingFile = file
                                }
                            },
                            onDelete = { deleteFile = file },
                            onExecute = {
                                coroutineScope.launch {
                                    statusMessage = ">> Executing ${file.name}..."
                                    statusOk = true
                                    val result = fileManager.executeFile(file.path)
                                    executeResult = "$ ${file.name}\n\n${result.output}\n\nExit: ${result.exitCode}"
                                    statusMessage = if (result.success) "✅ Executed: ${file.name}" else "❌ Failed: ${file.name}"
                                    statusOk = result.success
                                }
                            },
                            onAddToAI = {
                                chatViewModel.setFileContext(fileManager.getFileContextForAI(file.path))
                                statusMessage = "✅ ${file.name} → AI context loaded"
                                statusOk = true
                            }
                        )
                    }
                }
            }
        }
    }

    // Create File Dialog
    if (showCreateDialog) {
        HackerDialog(
            title = "[ NEW FILE ]",
            onDismiss = { showCreateDialog = false; newFileName = ""; newFileContent = "" },
            confirmText = "[CREATE]",
            onConfirm = {
                if (newFileName.isNotBlank()) {
                    val path = if (currentPath.isEmpty()) newFileName else "$currentPath/$newFileName"
                    val result = fileManager.writeFile(path, newFileContent)
                    statusMessage = result.getOrElse { it.message ?: "Error" }
                    statusOk = result.isSuccess
                    refresh(); showCreateDialog = false; newFileName = ""; newFileContent = ""
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("FILENAME", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    placeholder = { Text("script.py", color = FGreen.copy(alpha = 0.3f), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fileTextFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = FGreen),
                    shape = RoundedCornerShape(2.dp)
                )
                OutlinedTextField(
                    value = newFileContent,
                    onValueChange = { newFileContent = it },
                    label = { Text("CONTENT", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 15,
                    colors = fileTextFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FGreen),
                    shape = RoundedCornerShape(2.dp)
                )
            }
        }
    }

    // New Folder
    if (showFolderDialog) {
        HackerDialog(
            title = "[ NEW DIRECTORY ]",
            onDismiss = { showFolderDialog = false; newFolderName = "" },
            confirmText = "[MKDIR]",
            onConfirm = {
                if (newFolderName.isNotBlank()) {
                    val path = if (currentPath.isEmpty()) newFolderName else "$currentPath/$newFolderName"
                    val result = fileManager.createFolder(path)
                    statusMessage = result.getOrElse { it.message ?: "Error" }
                    statusOk = result.isSuccess
                    refresh(); showFolderDialog = false; newFolderName = ""
                }
            }
        ) {
            OutlinedTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                label = { Text("DIR_NAME", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fileTextFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = FGreen),
                shape = RoundedCornerShape(2.dp)
            )
        }
    }

    // View File
    viewingFile?.let { file ->
        val content = fileManager.readFile(file.path).getOrElse { "Cannot read file" }
        HackerDialog(
            title = "[ ${file.name.uppercase()} ]",
            onDismiss = { viewingFile = null },
            confirmText = "[EDIT]",
            onConfirm = { editContent = content; editingFile = file; viewingFile = null },
            dismissText = "[CLOSE]"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .background(Color(0xFF000800), RoundedCornerShape(2.dp))
                    .border(BorderStroke(1.dp, FBorder), RoundedCornerShape(2.dp))
            ) {
                Text(
                    text = content,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = FGreen,
                    lineHeight = 16.sp
                )
            }
        }
    }

    // Edit File
    editingFile?.let { file ->
        HackerDialog(
            title = "[ EDIT: ${file.name.uppercase()} ]",
            onDismiss = { editingFile = null },
            confirmText = "[SAVE]",
            onConfirm = {
                val result = fileManager.writeFile(file.path, editContent)
                statusMessage = result.getOrElse { it.message ?: "Error" }
                statusOk = result.isSuccess
                refresh(); editingFile = null
            }
        ) {
            OutlinedTextField(
                value = editContent,
                onValueChange = { editContent = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                maxLines = 50,
                colors = fileTextFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FGreen),
                shape = RoundedCornerShape(2.dp)
            )
        }
    }

    // Execute Result
    if (executeResult.isNotEmpty()) {
        HackerDialog(
            title = "[ EXECUTION OUTPUT ]",
            onDismiss = { executeResult = "" },
            confirmText = "[CLOSE]",
            onConfirm = { executeResult = "" }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .background(Color(0xFF000800), RoundedCornerShape(2.dp))
                    .border(BorderStroke(1.dp, FBorder), RoundedCornerShape(2.dp))
            ) {
                Text(
                    text = executeResult,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = FGreen,
                    lineHeight = 16.sp
                )
            }
        }
    }

    // Delete Confirm
    deleteFile?.let { file ->
        HackerDialog(
            title = "[ DELETE? ]",
            onDismiss = { deleteFile = null },
            confirmText = "[DELETE]",
            onConfirm = {
                val result = fileManager.delete(file.path)
                statusMessage = result.getOrElse { it.message ?: "Error" }
                statusOk = result.isSuccess
                refresh(); deleteFile = null
            },
            isDestructive = true
        ) {
            Text(
                ">> rm -rf ${file.name}\n>> This cannot be undone.",
                color = FRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun HackerFileRow(
    file: FileItem,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExecute: () -> Unit,
    onAddToAI: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val icon = when {
        file.isDirectory -> "📁"
        file.name.endsWith(".py")   -> "🐍"
        file.name.endsWith(".kt")   -> "🟣"
        file.name.endsWith(".js")   -> "🟡"
        file.name.endsWith(".sh")   -> "💻"
        file.name.endsWith(".html") -> "🌐"
        file.name.endsWith(".json") -> "📋"
        file.name.endsWith(".md")   -> "📝"
        else -> "📄"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (expanded) Color(0xFF0A1A0A) else Color(0xFF050F05),
                RoundedCornerShape(2.dp)
            )
            .border(BorderStroke(1.dp, if (expanded) FGreen.copy(alpha = 0.4f) else FBorder), RoundedCornerShape(2.dp))
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, color = FGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text(file.displaySize, color = FGreenDim.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                if (expanded) "[-]" else "[+]",
                color = FGreenDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(4.dp)
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!file.isDirectory) {
                    HackerActionBtn("[EDIT]", FGreen) { onEdit(); expanded = false }
                    HackerActionBtn("[AI]", FCyan) { onAddToAI(); expanded = false }
                    if (file.name.endsWith(".sh") || file.name.endsWith(".py"))
                        HackerActionBtn("[RUN]", FYellow) { onExecute(); expanded = false }
                }
                HackerActionBtn("[DEL]", FRed) { onDelete(); expanded = false }
            }
        }
    }
}

@Composable
fun HackerActionBtn(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(2.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.3f)), RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun fileTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = FGreen, unfocusedTextColor = FGreenDim,
    focusedBorderColor = FGreen, unfocusedBorderColor = FBorder,
    cursorColor = FGreen,
    focusedContainerColor = Color(0xFF000800), unfocusedContainerColor = Color(0xFF000800),
    focusedLabelColor = FCyan, unfocusedLabelColor = FCyan.copy(alpha = 0.6f)
)

@Composable
fun HackerDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String = "[CANCEL]",
    isDestructive: Boolean = false,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A0A),
        titleContentColor = FCyan,
        title = { Text(title, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
        text = { content() },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (isDestructive) FRed else FGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = FGreenDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    )
}
