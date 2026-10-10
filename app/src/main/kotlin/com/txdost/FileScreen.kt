package com.txdost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.data.FileItem
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.ui.theme.MonospaceStyle
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun FileScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel()
) {
    val c = Apple.colors
    val fileManager = chatViewModel.fileManager
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(fileManager.listFiles(currentPath)) }
    var statusMessage by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var editingFile by remember { mutableStateOf<FileItem?>(null) }
    var viewingFile by remember { mutableStateOf<FileItem?>(null) }
    var deleteFile by remember { mutableStateOf<FileItem?>(null) }
    var executeResult by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }

    fun refresh() { files = fileManager.listFiles(currentPath) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(
            title = "Workspace",
            subtitle = "/$currentPath",
            onBack = {
                if (currentPath.isEmpty()) onBack()
                else { currentPath = currentPath.substringBeforeLast("/", ""); refresh() }
            },
            actions = {
                IconButton(onClick = { showFolderDialog = true }) { Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = c.blue) }
                IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Rounded.NoteAdd, "New file", tint = c.blue) }
                IconButton(onClick = { refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = c.blue) }
            }
        )

        if (statusMessage.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (statusOk) c.green else c.red).copy(alpha = 0.12f))
                    .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusOk) c.green else c.red,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { statusMessage = "" }) { Text("Dismiss", color = c.blue) }
            }
        }

        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No files yet", style = MaterialTheme.typography.titleMedium, color = c.label)
                    Spacer(Modifier.height(6.dp))
                    Text("Create a file to get started.", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
                    Spacer(Modifier.height(16.dp))
                    AppleButton("New file", { showCreateDialog = true }, icon = Icons.Rounded.NoteAdd)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(files) { file ->
                    FileRow(
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
                            scope.launch {
                                statusMessage = "Executing ${file.name}…"
                                statusOk = true
                                val result = fileManager.executeFile(file.path)
                                executeResult = "$ ${file.name}\n\n${result.output}\n\nExit code: ${result.exitCode}"
                                statusMessage = if (result.success) "Executed ${file.name}" else "Failed: ${file.name}"
                                statusOk = result.success
                            }
                        },
                        onAddToAI = {
                            chatViewModel.setFileContext(fileManager.getFileContextForAI(file.path))
                            statusMessage = "${file.name} added to AI context"
                            statusOk = true
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AppleAlertDialog(
            title = "New file",
            onDismiss = { showCreateDialog = false; newFileName = ""; newFileContent = "" },
            confirmText = "Create",
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppleTextField(newFileName, { newFileName = it }, placeholder = "filename.sh", label = "Name")
                AppleTextField(newFileContent, { newFileContent = it }, placeholder = "contents…", label = "Content", singleLine = false, maxLines = 12)
            }
        }
    }

    if (showFolderDialog) {
        AppleAlertDialog(
            title = "New folder",
            onDismiss = { showFolderDialog = false; newFolderName = "" },
            confirmText = "Create",
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
            AppleTextField(newFolderName, { newFolderName = it }, placeholder = "folder name", label = "Name")
        }
    }

    viewingFile?.let { file ->
        val content = fileManager.readFile(file.path).getOrElse { "" }
        AppleAlertDialog(
            title = file.name,
            onDismiss = { viewingFile = null },
            confirmText = "Close",
            onConfirm = { viewingFile = null }
        ) {
            Box(Modifier.heightIn(max = 360.dp)) {
                SelectionContainerCompat {
                    Text(content.ifEmpty { "(empty file)" }, style = MonospaceStyle, color = c.label)
                }
            }
        }
    }

    editingFile?.let { file ->
        AppleAlertDialog(
            title = "Edit ${file.name}",
            onDismiss = { editingFile = null },
            confirmText = "Save",
            onConfirm = {
                val result = fileManager.writeFile(file.path, editContent)
                statusMessage = result.getOrElse { it.message ?: "Error" }
                statusOk = result.isSuccess
                refresh(); editingFile = null
            }
        ) {
            AppleTextField(editContent, { editContent = it }, placeholder = "", singleLine = false, maxLines = 30)
        }
    }

    if (executeResult.isNotEmpty()) {
        AppleAlertDialog(
            title = "Output",
            onDismiss = { executeResult = "" },
            confirmText = "Close",
            onConfirm = { executeResult = "" },
            dismissText = null
        ) {
            Box(Modifier.heightIn(max = 320.dp)) {
                SelectionContainerCompat {
                    Text(executeResult, style = MonospaceStyle, color = c.label)
                }
            }
        }
    }

    deleteFile?.let { file ->
        AppleAlertDialog(
            title = "Delete \"${file.name}\"?",
            onDismiss = { deleteFile = null },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                val result = fileManager.delete(file.path)
                statusMessage = result.getOrElse { it.message ?: "Error" }
                statusOk = result.isSuccess
                refresh(); deleteFile = null
            }
        ) {
            Text("This cannot be undone.", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
        }
    }
}

@Composable
private fun FileRow(
    file: FileItem,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExecute: () -> Unit,
    onAddToAI: () -> Unit
) {
    val c = Apple.colors
    var expanded by remember { mutableStateOf(false) }
    val emoji = when {
        file.isDirectory -> "📁"
        file.name.endsWith(".py") -> "🐍"
        file.name.endsWith(".kt") -> "🟪"
        file.name.endsWith(".js") -> "🟨"
        file.name.endsWith(".sh") -> "💻"
        file.name.endsWith(".html") -> "🌐"
        file.name.endsWith(".json") -> "📋"
        file.name.endsWith(".md") -> "📝"
        else -> "📄"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) c.secondaryBg else Color.Transparent)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(c.fill.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) { Text(emoji) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, color = c.label, maxLines = 1)
                Text(file.displaySize, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.MoreHoriz, "Actions", tint = c.secondaryLabel)
            }
        }
        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!file.isDirectory) {
                    Chip("Edit", c.blue) { onEdit(); expanded = false }
                    Chip("Add to AI", c.indigo) { onAddToAI(); expanded = false }
                    if (file.name.endsWith(".sh") || file.name.endsWith(".py")) {
                        Chip("Run", c.green) { onExecute(); expanded = false }
                    }
                }
                Chip("Delete", c.red) { onDelete(); expanded = false }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Box(Modifier.verticalScroll(rememberScrollState())) { content() }
    }
}
