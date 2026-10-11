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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
    data class Required(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo) : UpdateStatus
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateStatus
}

/**
 * Blocking update gate. App khulte hi check hota hai; naya version mile to
 * normal UI ki jagah "Update" screen dikhti hai. Net na ho / check fail ho to
 * app normal khul jata hai (brick nahi hota).
 */
@Composable
fun UpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
        status = if (info != null) UpdateStatus.Required(info) else UpdateStatus.Open
    }

    when (val s = status) {
        is UpdateStatus.Open -> content()

        is UpdateStatus.Checking -> UpdateGate(
            title = "Featurestic AI",
            message = "Update check kar raha hoon...",
            showSpinner = true
        )

        is UpdateStatus.Required -> UpdateGate(
            title = "Update zaroori hai",
            message = "Naya version ${s.info.versionName} aa gaya hai. App use karne ke liye pehle update karo.",
            buttonText = "Update karo",
            onButton = {
                status = UpdateStatus.Downloading(s.info)
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            UpdateChecker.download(context, s.info.apkUrl, s.info.sha256)
                        }
                        installApk(context, file)
                        status = UpdateStatus.Open
                    } catch (e: Exception) {
                        status = UpdateStatus.Failed(s.info, e.message ?: "Download fail")
                    }
                }
            }
        )

        is UpdateStatus.Downloading -> UpdateGate(
            title = "Download ho raha hai...",
            message = "Ruko, update download karke installer kholenge.",
            showSpinner = true
        )

        is UpdateStatus.Failed -> UpdateGate(
            title = "Update fail hua",
            message = s.message,
            buttonText = if (s.info != null) "Dobara try karo" else "Aage badho",
            onButton = {
                status = if (s.info != null) UpdateStatus.Required(s.info) else UpdateStatus.Open
            }
        )
    }
}

@Composable
private fun UpdateGate(
    title: String,
    message: String,
    showSpinner: Boolean = false,
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
            if (showSpinner) {
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
