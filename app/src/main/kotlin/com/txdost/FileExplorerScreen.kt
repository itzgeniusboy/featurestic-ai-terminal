package com.txdost.ui.screens

import androidx.compose.foundation.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import com.txdost.viewmodel.ChatViewModel

private val FBg     = Color(0xFF060D06)
private val FGreen  = Color(0xFF00FF41)
private val FGreenD = Color(0xFF00C832)
private val FCyan   = Color(0xFF00E5FF)
private val FYellow = Color(0xFFFFD700)
private val FRed    = Color(0xFFFF1744)
private val FBorder = Color(0xFF003300)
private val FCardBg = Color(0xFF0A120A)

data class FileItem(
    val name: String,
    val isDir: Boolean,
    val size: String = "",
    val perms: String = "",
    val modified: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("~") }
    var displayPath by remember { mutableStateOf("~/") }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<FileItem?>(null) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    val pathHistory = remember { mutableStateListOf("~") }

    // ── Load directory ─────────────────────────────────────────
    suspend fun loadDir(path: String) {
        loading = true
        error = ""
        files = emptyList()
        try {
            val expandedPath = if (path == "~") "\$HOME" else path
            val cmd = "ls -la $expandedPath 2>&1 | tail -n +2"
            val result = viewModel.runCommandForDashboard(cmd)
            if (result.startsWith("ls:") || result.contains("No such file")) {
                error = result; loading = false; return
            }
            val parsed = mutableListOf<FileItem>()
            result.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 9)
                if (parts.size < 9) return@forEach
                val perms    = parts[0]
                val size     = parts[4]
                val month    = parts[5]
                val day      = parts[6]
                val timeYear = parts[7]
                val name     = parts[8]
                if (name == "." || name == "..") return@forEach
                val isDir = perms.startsWith("d")
                val isLink = perms.startsWith("l")
                val displayName = if (isLink && name.contains(" -> "))
                    name.substringBefore(" -> ") else name
                parsed.add(FileItem(
                    name = displayName,
                    isDir = isDir || isLink,
                    size = if (isDir) "DIR" else formatSize(size),
                    perms = perms,
                    modified = "$month $day $timeYear"
                ))
            }
            // Dirs first, then files — alphabetical
            files = parsed.sortedWith(compareByDescending<FileItem> { it.isDir }.thenBy { it.name })
            displayPath = if (path == "~") "~/" else "$path/"
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        loading = false
    }

    suspend fun loadFile(path: String) {
        loading = true
        try {
            val result = viewModel.runCommandForDashboard("cat '$path' 2>&1")
            fileContent = result
            editContent = result
        } catch (e: Exception) {
            fileContent = "Error: ${e.message}"
        }
        loading = false
    }

    LaunchedEffect(currentPath) {
        loadDir(currentPath)
    }

    // ── File viewer/editor overlay ─────────────────────────────
    if (selectedFile != null && !selectedFile!!.isDir) {
        val filePath = if (currentPath == "~")
            "\$HOME/${selectedFile!!.name}" else "$currentPath/${selectedFile!!.name}"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(FBg)
        ) {
            // File TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, FBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        selectedFile = null; editMode = false
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = FGreen, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("[ FILE ]", color = FGreen, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(selectedFile!!.name, color = FCyan, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (!editMode) {
                        IconButton(onClick = { editMode = true; editContent = fileContent },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit",
                                tint = FYellow, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        // Save button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(FGreen.copy(alpha = 0.15f))
                                .border(BorderStroke(1.dp, FGreen.copy(alpha = 0.5f)),
                                    RoundedCornerShape(3.dp))
                                .clickable(enabled = !saving) {
                                    saving = true
                                    scope.launch {
                                        viewModel.runCommandForDashboard(
                                            "cat > '$filePath' << 'TXEOF'\n$editContent\nTXEOF"
                                        )
                                        fileContent = editContent
                                        editMode = false
                                        saving = false
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (saving) "SAVING..." else "SAVE",
                                color = FGreen, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { editMode = false },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel",
                                tint = FRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading...", color = FGreenD, fontFamily = FontFamily.Monospace)
                }
            } else if (editMode) {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = FGreen,
                        unfocusedTextColor = FGreenD,
                        focusedBorderColor = FGreen,
                        unfocusedBorderColor = FBorder,
                        focusedContainerColor = Color(0xFF020802),
                        unfocusedContainerColor = Color(0xFF020802),
                        cursorColor = FGreen
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FGreen
                    )
                )
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        itemsIndexed(fileContent.lines()) { idx, line ->
                            Row {
                                Text(
                                    text = "${idx + 1}".padStart(4),
                                    color = FGreenD.copy(alpha = 0.3f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(line, color = FGreen, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
        return
    }

    // ── Main File List ─────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(FBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, FBorder))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (pathHistory.size > 1) {
                                pathHistory.removeLast()
                                currentPath = pathHistory.last()
                            } else {
                                onBack()
                            }
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                                tint = FGreen, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("[ FILE EXPLORER ]", color = FGreen, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp)
                            Text(displayPath, color = FCyan, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        // New file button
                        IconButton(onClick = { showNewFileDialog = true },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "New File",
                                tint = FGreenD, modifier = Modifier.size(18.dp))
                        }
                        // Home button
                        IconButton(onClick = {
                            currentPath = "~"
                            pathHistory.clear()
                            pathHistory.add("~")
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Home, contentDescription = "Home",
                                tint = FGreenD, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Quick nav shortcuts
                    Row(
                        modifier = Modifier.padding(start = 36.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("~" to "HOME", "~/txdost" to "TXDOST",
                            "~/scripts" to "SCRIPTS", "/sdcard" to "SDCARD"
                        ).forEach { (path, label) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(FBorder)
                                    .clickable {
                                        currentPath = path
                                        pathHistory.clear()
                                        pathHistory.add(path)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(label, color = FGreenD, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // File list
            when {
                loading -> Box(modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = FGreen, modifier = Modifier.size(24.dp))
                        Text("Loading...", color = FGreenD, fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp)
                    }
                }
                error.isNotEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center) {
                    Text("❌ $error", color = FRed, fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp)
                }
                files.isEmpty() -> Box(modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) {
                    Text("(empty directory)", color = FGreenD.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files) { file ->
                        FileRow(
                            file = file,
                            onClick = {
                                if (file.isDir) {
                                    val newPath = if (currentPath == "~")
                                        "\$HOME/${file.name}" else "$currentPath/${file.name}"
                                    pathHistory.add(newPath)
                                    currentPath = newPath
                                } else {
                                    selectedFile = file
                                    val filePath = if (currentPath == "~")
                                        "\$HOME/${file.name}" else "$currentPath/${file.name}"
                                    scope.launch {
                                        loadFile(filePath)
                                    }
                                }
                            },
                            onDelete = { showDeleteDialog = file }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        // ── Delete confirm dialog ─────────────────────────────
        if (showDeleteDialog != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                containerColor = Color(0xFF0A120A),
                title = { Text("DELETE FILE?", color = FRed, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                text = { Text(showDeleteDialog!!.name, color = FGreen,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        val target = showDeleteDialog!!
                        val delPath = if (currentPath == "~")
                            "\$HOME/${target.name}" else "$currentPath/${target.name}"
                        scope.launch {
                            val cmd = if (target.isDir) "rm -rf '$delPath'" else "rm -f '$delPath'"
                            viewModel.runCommandForDashboard(cmd)
                            loadDir(currentPath)
                        }
                        showDeleteDialog = null
                    }) {
                        Text("DELETE", color = FRed, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("CANCEL", color = FGreenD, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // ── New file dialog ───────────────────────────────────
        if (showNewFileDialog) {
            AlertDialog(
                onDismissRequest = { showNewFileDialog = false; newItemName = "" },
                containerColor = Color(0xFF0A120A),
                title = { Text("NEW FILE", color = FGreen, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        placeholder = { Text("filename.py", color = FGreenD.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = FGreen,
                            unfocusedTextColor = FGreen,
                            focusedBorderColor = FGreen,
                            unfocusedBorderColor = FBorder,
                            focusedContainerColor = Color(0xFF020802),
                            unfocusedContainerColor = Color(0xFF020802),
                            cursorColor = FGreen
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newItemName.isNotBlank()) {
                            val newPath = if (currentPath == "~")
                                "\$HOME/$newItemName" else "$currentPath/$newItemName"
                            scope.launch {
                                viewModel.runCommandForDashboard("touch '$newPath'")
                                loadDir(currentPath)
                            }
                        }
                        showNewFileDialog = false; newItemName = ""
                    }) {
                        Text("CREATE", color = FGreen, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFileDialog = false; newItemName = "" }) {
                        Text("CANCEL", color = FGreenD, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }
}

@Composable
fun FileRow(
    file: FileItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val iconColor = when {
        file.isDir -> FCyan
        file.name.endsWith(".py")  -> FYellow
        file.name.endsWith(".js")  -> Color(0xFFF7DF1E)
        file.name.endsWith(".sh")  -> FGreen
        file.name.endsWith(".log") -> Color(0xFFFF6600)
        file.name.endsWith(".json")-> Color(0xFF4FC3F7)
        file.name.endsWith(".txt") -> FGreenD
        else -> FGreenD
    }
    val icon = when {
        file.isDir -> "📁"
        file.name.endsWith(".py")  -> "🐍"
        file.name.endsWith(".js")  -> "📜"
        file.name.endsWith(".sh")  -> "⚙"
        file.name.endsWith(".log") -> "📋"
        file.name.endsWith(".json")-> "{ }"
        else -> "📄"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(FCardBg)
            .border(BorderStroke(1.dp, FBorder), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = iconColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (file.isDir) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${file.modified}  ${file.perms}",
                color = FGreenD.copy(alpha = 0.4f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = file.size,
            color = FGreenD.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (!file.isDir) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = FRed.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            }
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
