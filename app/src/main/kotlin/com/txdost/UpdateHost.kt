package com.txdost

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.txdost.ui.components.AppleAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Checks GitHub Releases on launch and offers a one-tap self-update.
 * Relies on the CI release carrying `version.json` + `app-debug.apk`,
 * and on a stable signing key so the update installs over this build.
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
        if (info != null) update = info
    }

    val current = update
    if (current != null && !dismissed) {
        AppleAlertDialog(
            title = "Update available",
            onDismiss = { dismissed = true },
            confirmText = if (downloading) "Downloading..." else "Update now",
            onConfirm = {
                if (!downloading) {
                    downloading = true
                    error = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { UpdateChecker.download(context, current.apkUrl) }
                            installApk(context, file)
                            dismissed = true
                        } catch (e: Exception) {
                            error = e.message ?: "Download failed"
                        } finally {
                            downloading = false
                        }
                    }
                }
            },
            dismissText = "Later"
        ) {
            Text(
                if (error != null) "Download fail: $error"
                else "Naya version ${current.versionName} aa gaya hai. Update karke install karo."
            )
        }
    }
}

private fun installApk(context: Context, file: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
