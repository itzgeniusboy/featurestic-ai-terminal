package com.txdost.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileManager(private val context: Context) {

    // Root folder inside app's private storage
    private val rootDir: File get() = File(context.filesDir, "workspace")

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    fun getRootPath(): String = rootDir.absolutePath

    // Path traversal rok — sirf workspace ke andar allow
    private fun resolveSafe(relativePath: String): File? = try {
        val root = rootDir.canonicalFile
        val f = File(root, relativePath).canonicalFile
        if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    } catch (e: Exception) { null }

    // List files and folders
    fun listFiles(relativePath: String = ""): List<FileItem> {
        val dir = if (relativePath.isEmpty()) rootDir else File(rootDir, relativePath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.map { file ->
            FileItem(
                name = file.name,
                path = file.relativeTo(rootDir).path,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    // Create a folder
    fun createFolder(relativePath: String): Result<String> {
        return try {
            val folder = resolveSafe(relativePath) ?: return Result.failure(Exception("Invalid path"))
            if (folder.mkdirs()) {
                Result.success("Folder created: $relativePath")
            } else if (folder.exists()) {
                Result.failure(Exception("Folder already exists"))
            } else {
                Result.failure(Exception("Failed to create folder"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Create / write a file
    fun writeFile(relativePath: String, content: String): Result<String> {
        return try {
            val file = resolveSafe(relativePath) ?: return Result.failure(Exception("Invalid path"))
            file.parentFile?.mkdirs()
            file.writeText(content)
            Result.success("File saved: $relativePath")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Read file content
    fun readFile(relativePath: String): Result<String> {
        return try {
            val file = resolveSafe(relativePath) ?: return Result.failure(Exception("Invalid path"))
            if (!file.exists()) return Result.failure(Exception("File not found: $relativePath"))
            if (!file.isFile) return Result.failure(Exception("Not a file: $relativePath"))
            if (file.length() > 500_000) return Result.failure(Exception("File too large (>500KB)"))
            Result.success(file.readText())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Delete file or folder
    fun delete(relativePath: String): Result<String> {
        return try {
            val file = resolveSafe(relativePath) ?: return Result.failure(Exception("Invalid path"))
            if (!file.exists()) return Result.failure(Exception("Not found: $relativePath"))
            if (file.deleteRecursively()) {
                Result.success("Deleted: $relativePath")
            } else {
                Result.failure(Exception("Failed to delete"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Rename a file or folder
    fun rename(oldPath: String, newName: String): Result<String> {
        return try {
            val old = resolveSafe(oldPath) ?: return Result.failure(Exception("Invalid path"))
            if (newName.contains('/') || newName.contains('\\') || newName.contains(".."))
                return Result.failure(Exception("Invalid name"))
            val new = File(old.parentFile, newName)
            if (old.renameTo(new)) {
                Result.success("Renamed to: $newName")
            } else {
                Result.failure(Exception("Rename failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Execute a script file (shell script)
    suspend fun executeFile(relativePath: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val file = File(rootDir, relativePath)
            if (!file.exists()) return@withContext CommandResult("File not found: $relativePath", -1, false)

            val ext = file.extension.lowercase()
            val command = when (ext) {
                "sh" -> "sh ${file.absolutePath}"
                "py" -> "python3 ${file.absolutePath}"
                "js" -> "node ${file.absolutePath}"
                else -> "sh ${file.absolutePath}"
            }

            // Make executable
            file.setExecutable(true)

            val process = ProcessBuilder()
                .command("sh", "-c", command)
                .directory(file.parentFile)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(
                output = output.ifEmpty { "(no output)" }.trim(),
                exitCode = exitCode,
                success = exitCode == 0
            )
        } catch (e: Exception) {
            CommandResult("Execution error: ${e.message}", -1, false)
        }
    }

    // Get full context string of a file for AI
    fun getFileContextForAI(relativePath: String): String {
        val result = readFile(relativePath)
        return if (result.isSuccess) {
            "File: $relativePath\n```\n${result.getOrThrow()}\n```"
        } else {
            "Could not read: $relativePath"
        }
    }

    // Build workspace tree string
    fun getWorkspaceTree(relativePath: String = "", depth: Int = 0): String {
        if (depth > 4) return ""
        val dir = if (relativePath.isEmpty()) rootDir else File(rootDir, relativePath)
        val files = dir.listFiles() ?: return ""
        val sb = StringBuilder()
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { file ->
            val indent = "  ".repeat(depth)
            val icon = if (file.isDirectory) "📁" else "📄"
            sb.appendLine("$indent$icon ${file.name}")
            if (file.isDirectory) {
                sb.append(getWorkspaceTree(file.relativeTo(rootDir).path, depth + 1))
            }
        }
        return sb.toString()
    }
}

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val displaySize: String get() = when {
        isDirectory -> "folder"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}
