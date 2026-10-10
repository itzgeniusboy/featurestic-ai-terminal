package com.txdost.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.txdost.data.TermuxBridge
import java.io.File
import java.io.FileOutputStream

private val HBg2       = Color(0xFF0A0A0A)
private val HGreen2    = Color(0xFF00FF41)
private val HGreenDim2 = Color(0xFF00C832)
private val HCyan2     = Color(0xFF00FFFF)
private val HRed2      = Color(0xFFFF0040)
private val HBorder2   = Color(0xFF003300)
private val HCard2     = Color(0xFF0A0F0A)

private const val ASSET_NAME = "termux_install.sh"
private const val OUT_NAME   = "featurestic-install.sh"
private const val STORAGE_CMD = "termux-setup-storage"
private const val RUN_CMD     = "bash ~/storage/downloads/$OUT_NAME"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val termux = remember { TermuxBridge(context) }

    var savedPath by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("") }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            savedPath = saveAssetToDownloads(context, ASSET_NAME, OUT_NAME)
            statusMsg = if (savedPath != null) "✅ Script saved" else "❌ Could not save script"
        } else {
            statusMsg = "❌ Storage permission needed to save the script"
        }
    }

    fun doDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        savedPath = saveAssetToDownloads(context, ASSET_NAME, OUT_NAME)
        statusMsg = if (savedPath != null) "✅ Script saved" else "❌ Could not save script"
        Toast.makeText(
            context,
            if (savedPath != null) "Saved to $savedPath" else "Save failed",
            Toast.LENGTH_LONG
        ).show()
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        statusMsg = "📋 Copied: $label"
        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
    }

    fun oneTapConnect() {
        if (!termux.isTermuxInstalled()) {
            statusMsg = "❌ Pehle Termux install karo (Step 1)"
            return
        }
        try {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val cmd = "printf '%s' '$b64' | base64 -d | bash"
            val ok = termux.runCommandViaTermux(cmd)
            statusMsg = if (ok)
                "⚡ Termux ko bhej diya! 5-10 sec baad [ TERMUX:ON ] aa jayega."
            else
                "❌ Termux ne reject kiya — neeche manual steps use karo"
        } catch (e: Exception) {
            statusMsg = "❌ ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(HBg2)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000A00))
                    .border(BorderStroke(1.dp, HBorder2))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = HGreen2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "[ TERMUX SETUP ]",
                        color = HGreen2, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 1-TAP ────────────────────────────────────────
                HackerSection("[ ⚡ 1-TAP CONNECT ]") {
                    Text(
                        "Termux install hone ke baad ye button dabao — baaki sab automatic.",
                        color = HGreenDim2.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Button(
                        onClick = { oneTapConnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003300)),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, HGreen2)
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = HGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(">> 1-TAP CONNECT", color = HGreen2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Pehli baar Termux me allow-external-apps on hona chahiye. Agar connect na ho " +
                        "to neeche manual steps follow karo.",
                        color = HGreenDim2.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    "3 step me phone ko AI terminal se connect karo. Internet sirf python install ke liye chahiye.",
                    color = HGreenDim2, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )

                // ── STEP 1 ───────────────────────────────────────
                HackerSection("[ 1 ] INSTALL TERMUX") {
                    Text(
                        "F-Droid wala Termux chahiye (Play Store wala outdated hai).",
                        color = HGreenDim2.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val i = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_TERMUX))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { context.startActivity(i) } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF001A00)),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, HGreen2)
                        ) {
                            Text(">> GET TERMUX", color = HGreen2, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { termux.openTermux() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, HCyan2),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HCyan2)
                        ) {
                            Text(">> OPEN TERMUX", color = HCyan2, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }

                // ── STEP 2 ───────────────────────────────────────
                HackerSection("[ 2 ] DOWNLOAD SCRIPT") {
                    Text(
                        "Installer script is app me bundled hai. Button dabao — Downloads folder me save ho jayega.",
                        color = HGreenDim2.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Button(
                        onClick = { doDownload() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003300)),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, HGreen2)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = HGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(">> DOWNLOAD SCRIPT", color = HGreen2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (savedPath != null) {
                        Text(
                            ">> Saved: $savedPath",
                            color = HGreen2, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // ── STEP 3 ───────────────────────────────────────
                HackerSection("[ 3 ] RUN IN TERMUX") {
                    Text(
                        "Termux kholo, pehle storage enable karo (sirf ek baar — Allow dabana):",
                        color = HGreenDim2.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    CommandRow(STORAGE_CMD) { copyToClipboard("storage setup", STORAGE_CMD) }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ab ye command chalao (script download se run hogi):",
                        color = HGreenDim2.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    CommandRow(RUN_CMD) { copyToClipboard("install command", RUN_CMD) }

                    Text(
                        "Termux me paste karke Enter dabao. Sab automatic ho jayega aur [ TERMUX:ON ] dikhega.",
                        color = HGreenDim2.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }

                if (statusMsg.isNotEmpty()) {
                    Text(
                        statusMsg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF001400), RoundedCornerShape(2.dp))
                            .border(BorderStroke(1.dp, HGreen2), RoundedCornerShape(2.dp))
                            .padding(10.dp),
                        color = HGreen2, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CommandRow(command: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF000800), RoundedCornerShape(2.dp))
            .border(BorderStroke(1.dp, HBorder2), RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            command,
            color = HCyan2, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = HGreen2, modifier = Modifier.size(16.dp))
        }
    }
}
