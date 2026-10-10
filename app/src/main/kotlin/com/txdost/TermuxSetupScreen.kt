package com.txdost.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.txdost.data.TermuxBridge
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.ui.theme.MonospaceStyle
import java.io.File
import java.io.FileOutputStream

private const val ASSET_NAME = "termux_install.sh"
private const val OUT_NAME = "featurestic-install.sh"
private const val STORAGE_CMD = "termux-setup-storage"
private const val RUN_CMD = "bash ~/storage/downloads/$OUT_NAME"
private const val FDROID_TERMUX = "https://f-droid.org/packages/com.termux/"

private fun saveAssetToDownloads(context: Context, assetName: String, fileName: String): String? {
    return try {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-sh")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            "Download/$fileName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            f.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun TermuxSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val termux = remember { TermuxBridge(context) }
    val c = Apple.colors

    var savedPath by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("") }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            savedPath = saveAssetToDownloads(context, ASSET_NAME, OUT_NAME)
            statusMsg = if (savedPath != null) "Script saved" else "Could not save script"
        } else {
            statusMsg = "Storage permission needed to save the script"
        }
    }

    fun doDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        savedPath = saveAssetToDownloads(context, ASSET_NAME, OUT_NAME)
        statusMsg = if (savedPath != null) "Script saved to $savedPath" else "Could not save script"
        Toast.makeText(context, if (savedPath != null) "Saved!" else "Failed", Toast.LENGTH_SHORT).show()
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun oneTapConnect() {
        if (!termux.isTermuxInstalled()) {
            statusMsg = "Pehle Termux install karo (Step 1)"
            return
        }
        try {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val cmd = "printf '%s' '$b64' | base64 -d | bash"
            val ok = termux.runCommandViaTermux(cmd)
            statusMsg = if (ok) "Sent to Termux — ready in a few seconds!"
            else "Termux rejected it — use the manual steps below"
        } catch (e: Exception) {
            statusMsg = e.message ?: "Failed"
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppleTopBar(title = "Termux setup", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Hero
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.blue.copy(alpha = 0.10f))
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("One tap connect", style = MaterialTheme.typography.titleMedium, color = c.label)
                        Text("Termux installed? Just tap below.", style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
                    }
                }
                Spacer(Modifier.height(14.dp))
                AppleButton("Connect automatically", { oneTapConnect() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "First time? Enable allow-external-apps in Termux once, or use the manual steps below.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.secondaryLabel
                )
            }

            StepCard(1, "Install Termux") {
                Text(
                    "Use the F-Droid build (the Play Store version is outdated).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryLabel
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppleButton(
                        "Get Termux",
                        {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_TERMUX)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(i) }
                        },
                        style = AppleButtonStyle.Filled,
                        icon = Icons.Rounded.OpenInNew,
                        modifier = Modifier.weight(1f)
                    )
                    AppleButton("Open", { termux.openTermux() }, style = AppleButtonStyle.Gray, icon = Icons.Rounded.Terminal, modifier = Modifier.weight(1f))
                }
            }

            StepCard(2, "Download the script") {
                Text(
                    "The installer is bundled inside this app. Save it to your Downloads folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryLabel
                )
                Spacer(Modifier.height(12.dp))
                AppleButton("Download script", { doDownload() }, style = AppleButtonStyle.Filled, icon = Icons.Rounded.Download, modifier = Modifier.fillMaxWidth())
                if (savedPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Saved to $savedPath", style = MaterialTheme.typography.labelSmall, color = c.green)
                }
            }

            StepCard(3, "Run in Termux") {
                Text(
                    "Open Termux and paste these commands (storage first time only).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondaryLabel
                )
                Spacer(Modifier.height(12.dp))
                CommandRow(STORAGE_CMD) { copyToClipboard("storage setup", STORAGE_CMD) }
                Spacer(Modifier.height(8.dp))
                CommandRow(RUN_CMD) { copyToClipboard("install command", RUN_CMD) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "That's it — the app will show Termux connected once the server is up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.secondaryLabel
                )
            }

            if (statusMsg.isNotEmpty()) {
                Text(
                    statusMsg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.secondaryBg)
                        .padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.label
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Apple.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.secondaryBg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(c.blue),
                contentAlignment = Alignment.Center
            ) {
                Text("$number", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.label)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun CommandRow(command: String, onCopy: () -> Unit) {
    val c = Apple.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.tertiaryBg)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            command,
            style = MonospaceStyle.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            color = c.label,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCopy() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.ContentCopy, "Copy", tint = c.blue, modifier = Modifier.size(18.dp))
        }
    }
}
