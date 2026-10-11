package com.txdost

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UpdateStatus {
    data object Checking : UpdateStatus
    data object Open : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class ReadyToDownload(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateStatus
    data class NeedPermission(val info: UpdateInfo) : UpdateStatus
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateStatus
}

/**
 * Update gate with a smooth, staged flow:
 *   Update available  →  Download  →  (download with progress)  →  install.
 * Net na ho / check fail ho to app normal khul jata hai.
 */
@Composable
fun UpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }
    var pendingFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
        status = if (info != null) UpdateStatus.Available(info) else UpdateStatus.Open
    }

    fun installReady(file: File) {
        if (canInstall(context)) {
            startInstaller(context, file)
            status = UpdateStatus.Open
        } else {
            requestInstallPermission(context)
        }
    }

    fun startDownload(info: UpdateInfo) {
        status = UpdateStatus.Downloading(info, 0)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    UpdateChecker.download(context, info.apkUrl, info.sha256) { pct ->
                        status = UpdateStatus.Downloading(info, pct)
                    }
                }
                pendingFile = file
                if (canInstall(context)) {
                    startInstaller(context, file)
                    status = UpdateStatus.Open
                } else {
                    status = UpdateStatus.NeedPermission(info)
                }
            } catch (e: Exception) {
                status = UpdateStatus.Failed(info, e.message ?: "Download fail")
            }
        }
    }

    when (val s = status) {
        is UpdateStatus.Open -> content()

        is UpdateStatus.Checking -> UpdateGate(
            title = "Featurestic AI",
            message = "Update check kar raha hoon...",
            showSpinner = true
        )

        is UpdateStatus.Available -> UpdateGate(
            title = "Update available",
            message = "Naya version ${s.info.versionName} taiyar hai.",
            buttonText = "Update available",
            onButton = { status = UpdateStatus.ReadyToDownload(s.info) }
        )

        is UpdateStatus.ReadyToDownload -> UpdateGate(
            title = "Update ready",
            message = "Version ${s.info.versionName} download karo, phir auto install ho jayega.",
            buttonText = "Download",
            onButton = { startDownload(s.info) }
        )

        is UpdateStatus.Downloading -> UpdateGate(
            title = "Download ho raha hai...",
            message = "${s.progress}%",
            progress = s.progress
        )

        is UpdateStatus.NeedPermission -> UpdateGate(
            title = "Install permission chahiye",
            message = "Is app ko update install karne ki permission do, phir \"Install karo\" dabao.",
            buttonText = "Install karo",
            onButton = {
                val f = pendingFile
                if (f != null && canInstall(context)) installReady(f)
                else requestInstallPermission(context)
            }
        )

        is UpdateStatus.Failed -> UpdateGate(
            title = "Update fail hua",
            message = s.message,
            buttonText = if (s.info != null) "Dobara try karo" else "Aage badho",
            onButton = {
                status = if (s.info != null) UpdateStatus.ReadyToDownload(s.info) else UpdateStatus.Open
            }
        )
    }
}

@Composable
private fun UpdateGate(
    title: String,
    message: String,
    showSpinner: Boolean = false,
    progress: Int? = null,
    buttonText: String? = null,
    onButton: (() -> Unit)? = null
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress != null) {
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (showSpinner) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }
            if (buttonText != null && onButton != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onButton) { Text(buttonText) }
            }
        }
    }
}

private fun canInstall(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

private fun requestInstallPermission(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun startInstaller(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
