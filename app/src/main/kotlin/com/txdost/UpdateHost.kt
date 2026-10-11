package com.txdost

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.security.MessageDigest

private sealed interface UpdateStatus {
    data object Checking : UpdateStatus
    data object Open : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class ReadyToDownload(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateStatus
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateStatus
    data class NeedPermission(val info: UpdateInfo, val apk: File) : UpdateStatus
    data class SignatureMismatch(val info: UpdateInfo, val savedName: String?) : UpdateStatus
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateStatus
}

/**
 * Update gate with a smooth, staged flow:
 *   Update available  →  Download  →  (download with progress)  →  install.
 * Agar purani app alag signing key se install hui ho (jis se update install nahi hota),
 * to wo detect karke APK Downloads me save karke uninstall + reinstall guide karta hai.
 */
@Composable
fun UpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
        status = if (info != null) UpdateStatus.Available(info) else UpdateStatus.Open
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
                // Signature check: agar purani installed app ka signer alag hai to
                // ye APK uske upar install ho hi nahi sakta.
                val mismatch = withContext(Dispatchers.IO) {
                    signaturesDiffer(context, file)
                }
                if (mismatch) {
                    val saved = withContext(Dispatchers.IO) { exportApkToDownloads(context, file) }
                    status = UpdateStatus.SignatureMismatch(info, saved)
                } else {
                    status = UpdateStatus.ReadyToInstall(info, file)
                }
            } catch (e: Exception) {
                status = UpdateStatus.Failed(info, e.message ?: "Download fail")
            }
        }
    }

    fun installNow(info: UpdateInfo, apk: File) {
        if (canInstall(context)) {
            try {
                installApk(context, apk)
                status = UpdateStatus.Open
            } catch (e: Exception) {
                status = UpdateStatus.Failed(info, e.message ?: "Install fail")
            }
        } else {
            status = UpdateStatus.NeedPermission(info, apk)
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
            message = "Version ${s.info.versionName} download karo, phir \"Update karo\" dabao.",
            buttonText = "Download",
            onButton = { startDownload(s.info) }
        )

        is UpdateStatus.Downloading -> UpdateGate(
            title = "Download ho raha hai...",
            message = "${s.progress}%",
            progress = s.progress
        )

        is UpdateStatus.ReadyToInstall -> UpdateGate(
            title = "Download ho gaya",
            message = "Version ${s.info.versionName} taiyar hai. \"Update karo\" dabao — naya version install ho jayega.",
            buttonText = "Update karo",
            onButton = { installNow(s.info, s.apk) }
        )

        is UpdateStatus.NeedPermission -> UpdateGate(
            title = "Install permission chahiye",
            message = "Is app ko update install karne ki permission do, phir \"Update karo\" dabao.",
            buttonText = "Update karo",
            onButton = {
                if (canInstall(context)) installNow(s.info, s.apk)
                else requestInstallPermission(context)
            }
        )

        is UpdateStatus.SignatureMismatch -> UpdateGate(
            title = "Purani app se match nahi hua",
            message = "Is update ko install nahi kiya ja sakta kyunki aapki purani app " +
                "alag signing key se install hui hai.\n\n" +
                (if (s.savedName != null) "Naya APK Downloads me save ho gaya: ${s.savedName}\n\n" else "") +
                "Purani app uninstall karo, phir wahi naya APK (ya GitHub release) install karo.",
            buttonText = "Purani app uninstall karo",
            onButton = { uninstallSelf(context) }
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

private fun installApk(context: Context, file: File) {
    // Prefer the system installer UI (ACTION_VIEW + FileProvider). The
    // PackageInstaller session confirm-dialog path is flaky on Android 12/13
    // (dialog flashes and vanishes without installing), so it is only a fallback.
    if (!launchSystemInstaller(context, file)) {
        installViaSession(context, file)
    }
}

/** Opens the stock package installer for the APK; false if it can't be launched. */
private fun launchSystemInstaller(context: Context, file: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

/** Fallback self-update via PackageInstaller session. */
private fun installViaSession(context: Context, file: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
    }
    val sessionId = installer.createSession(params)
    val session = installer.openSession(sessionId)
    try {
        val out = session.openWrite("base.apk", 0, file.length())
        try {
            file.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        } finally {
            out.close()
        }
        val resultIntent = Intent(context, InstallResultReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, piFlags)
        session.commit(pending.intentSender)
    } finally {
        session.close()
    }
}

private fun uninstallSelf(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** True if the installed app's signing certs are known AND differ from the APK's. */
private fun signaturesDiffer(context: Context, apk: File): Boolean = try {
    val installed = signerHashes(installedInfo(context))
    val incoming = signerHashes(archiveInfo(context, apk))
    installed.isNotEmpty() && incoming.isNotEmpty() && installed != incoming
} catch (e: Exception) {
    false
}

private fun installedInfo(context: Context): PackageInfo {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES
    return context.packageManager.getPackageInfo(context.packageName, flags)
}

private fun archiveInfo(context: Context, apk: File): PackageInfo? {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES
    return context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
}

private fun signerHashes(info: PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION") info.signatures
    }
    return (certs ?: emptyArray()).map { sig ->
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.toSet()
}

/** Copies the APK to the public Downloads folder; returns the file name, or null on failure. */
private fun exportApkToDownloads(context: Context, file: File): String? = try {
    val name = "Featurestic-AI-Terminal-v${BuildConfig.VERSION_NAME}.apk"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } else null
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, name)
        file.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        name
    }
} catch (e: Exception) {
    null
}
