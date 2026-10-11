package com.txdost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Javascript
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.ui.theme.MonospaceStyle
import com.txdost.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

data class RemoteFile(
    val name: String,
    val isDir: Boolean,
    val size: String = "",
    val perms: String = "",
    val modified: String = ""
)

@Composable
fun FileExplorerScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val c = Apple.colors
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("~") }
    var displayPath by remember { mutableStateOf("~/") }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<RemoteFile?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<RemoteFile?>(null) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    val pathHistory = remember { mutableStateListOf("~") }

    suspend fun loadDir(path: String) {
        loading = true
        error = ""
        files = emptyList()
        try {
            val expandedPath = if (path == "~") "\$HOME" else shellQuote(path)
            val result = viewModel.runCommand("ls -la $expandedPath 2>&1 | tail -n +2")
            if (result.startsWith("ls:") || result.contains("No such file")) {
                error = result; loading = false; return
            }
            val parsed = mutableListOf<RemoteFile>()
            result.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 9)
                if (parts.size < 9) return@forEach
                val perms = parts[0]
                val size = parts[4]
                val month = parts[5]; val day = parts[6]; val timeYear = parts[7]; val name = parts[8]
                if (name == "." || name == "..") return@forEach
                val isDir = perms.startsWith("d")
                val isLink = perms.startsWith("l")
                val displayName = if (isLink && name.contains(" -> ")) name.substringBefore(" -> ") else name
                parsed.add(RemoteFile(displayName, isDir || isLink, if (isDir) "—" else formatSize(size), perms, "$month $day $timeYear"))
            }
            files = parsed.sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name })
            displayPath = if (path == "~") "~/" else "$path/"
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        loading = false
    }

    suspend fun loadFile(path: String) {
        loading = true
        try {
            val result = viewModel.runCommand("cat ${shellQuote(path)} 2>&1")
            fileContent = result
            editContent = result
        } catch (e: Exception) {
            fileContent = "Error: ${e.message}"
        }
        loading = false
    }

    LaunchedEffect(currentPath) { loadDir(currentPath) }

    // ── File viewer / editor ───────────────────────────────────
    if (selectedFile != null && !selectedFile!!.isDir) {
        val filePath = if (currentPath == "~") "\$HOME/${selectedFile!!.name}" else "$currentPath/${selectedFile!!.name}"
        Column(Modifier.fillMaxSize().background(c.bg)) {
            AppleTopBar(
                title = selectedFile!!.name,
                subtitle = displayPath,
                onBack = { selectedFile = null; editMode = false },
                actions = {
                    if (!editMode) {
                        androidx.compose.material3.IconButton(onClick = { editMode = true; editContent = fileContent }) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Edit", tint = c.blue)
                        }
                    } else {
                        AppleButton(
                            if (saving) "Saving…" else "Save",
                            {
                                saving = true
                                scope.launch {
                                    viewModel.runCommand("cat > '$filePath' << 'TXEOF'\n$editContent\nTXEOF")
                                    fileContent = editContent
                                    editMode = false
                                    saving = false
                                }
                            },
                            loading = saving
                        )
                    }
                }
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
                }
            } else if (editMode) {
                androidx.compose.foundation.text.BasicTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    textStyle = MonospaceStyle.copy(color = c.label),
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        itemsIndexed(fileContent.lines()) { idx, line ->
                            Row {
                                Text(
                                    "${idx + 1}".padStart(4),
                                    color = c.tertiaryLabel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(line, color = c.label, style = MonospaceStyle)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(
            title = "Files",
            subtitle = displayPath,
            onBack = {
                if (pathHistory.size > 1) { pathHistory.removeLast(); currentPath = pathHistory.last() } else onBack()
            },
            actions = {
                androidx.compose.material3.IconButton(onClick = { showNewFileDialog = true }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Add, "New file", tint = c.blue)
                }
                androidx.compose.material3.IconButton(onClick = {
                    currentPath = "~"; pathHistory.clear(); pathHistory.add("~")
                }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Home, "Home", tint = c.blue)
                }
            }
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("~" to "Home", "~/txdost" to "Terminal", "~/scripts" to "Scripts", "/sdcard" to "SD card").forEach { (path, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (currentPath == path) Color.White else c.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (currentPath == path) c.blue else c.fill.copy(alpha = 0.5f))
                        .clickable { currentPath = path; pathHistory.add(path) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
            }
            error.isNotEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = c.red)
            }
            files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty directory", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(files) { file ->
                    ExplorerFileRow(
                        file = file,
                        onClick = {
                            if (file.isDir) {
                                val newPath = if (currentPath == "~") "\$HOME/${file.name}" else "$currentPath/${file.name}"
                                pathHistory.add(newPath); currentPath = newPath
                            } else {
                                selectedFile = file
                                val filePath = if (currentPath == "~") "\$HOME/${file.name}" else "$currentPath/${file.name}"
                                scope.launch { loadFile(filePath) }
                            }
                        },
                        onDelete = { showDeleteDialog = file }
                    )
                }
            }
        }
    }

    showDeleteDialog?.let { target ->
        AppleAlertDialog(
            title = "Delete \"${target.name}\"?",
            onDismiss = { showDeleteDialog = null },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                val delPath = if (currentPath == "~") "\$HOME/${target.name}" else "$currentPath/${target.name}"
                scope.launch {
                    viewModel.runCommand(if (target.isDir) "rm -rf '$delPath'" else "rm -f '$delPath'")
                    loadDir(currentPath)
                }
                showDeleteDialog = null
            }
        ) { Text("This cannot be undone.", style = MaterialTheme.typography.bodyMedium, color = c.secondaryLabel) }
    }

    if (showNewFileDialog) {
        AppleAlertDialog(
            title = "New file",
            onDismiss = { showNewFileDialog = false; newItemName = "" },
            confirmText = "Create",
            onConfirm = {
                if (newItemName.isNotBlank()) {
                    val newPath = if (currentPath == "~") "\$HOME/$newItemName" else "$currentPath/$newItemName"
                    scope.launch { viewModel.runCommand("touch '$newPath'"); loadDir(currentPath) }
                }
                showNewFileDialog = false; newItemName = ""
            }
        ) { AppleTextField(newItemName, { newItemName = it }, placeholder = "filename.sh", label = "Name") }
    }
}

@Composable
private fun ExplorerFileRow(
    file: RemoteFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val c = Apple.colors
    val accent = when {
        file.isDir -> c.blue
        file.name.endsWith(".py") -> c.yellow
        file.name.endsWith(".sh") -> c.green
        file.name.endsWith(".json") -> c.teal
        else -> c.secondaryLabel
    }
    val icon = when {
        file.isDir -> Icons.Rounded.Folder
        file.name.endsWith(".py") -> Icons.Rounded.Code
        file.name.endsWith(".js") -> Icons.Rounded.Javascript
        file.name.endsWith(".sh") -> Icons.Rounded.Terminal
        file.name.endsWith(".json") -> Icons.Rounded.DataObject
        else -> Icons.Rounded.InsertDriveFile
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(c.fill.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, color = if (file.isDir) c.label else accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${file.modified}  ${file.perms}", style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel, maxLines = 1)
        }
        Text(file.size, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
        androidx.compose.material3.IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Delete, "Delete", tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatSize(bytes: String): String {
    val b = bytes.toLongOrNull() ?: return bytes
    return when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        b < 1024 * 1024 * 1024 -> "${b / (1024 * 1024)}MB"
        else -> "${b / (1024 * 1024 * 1024)}GB"
    }
}

// Shell-safe single-quoted path (spaces, quotes, $ etc. safe).
private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
