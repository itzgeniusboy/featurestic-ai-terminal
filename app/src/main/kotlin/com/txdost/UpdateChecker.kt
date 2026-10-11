package com.txdost

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Info about a newer build published on GitHub Releases. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?
)

/**
 * Checks the rolling "ci-latest" GitHub Release for a newer APK and downloads it.
 * The release carries `version.json` (versionCode/versionName/sha256) + `app-release.apk`.
 */
object UpdateChecker {

    /** Returns update info if the published build is newer than this one, else null. */
    fun check(): UpdateInfo? {
        return try {
            val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/${BuildConfig.UPDATE_TAG}"
            val release = JSONObject(httpGet(api))
            val assets = release.getJSONArray("assets")
            var versionJsonUrl: String? = null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) {
                    "version.json" -> versionJsonUrl = a.getString("browser_download_url")
                    "app-release.apk", "app-debug.apk" -> if (apkUrl == null) apkUrl = a.getString("browser_download_url")
                }
            }
            if (versionJsonUrl == null || apkUrl == null) return null
            val info = JSONObject(httpGet(versionJsonUrl))
            val remoteCode = info.optInt("versionCode", 0)
            val remoteName = info.optString("versionName", "")
            val sha = info.optString("sha256").ifBlank { null }
            if (remoteCode > BuildConfig.VERSION_CODE) UpdateInfo(remoteCode, remoteName, apkUrl, sha) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Download the APK into cache/updates/ and return the file (checksum verified). */
    fun download(
        context: Context,
        url: String,
        expectedSha256: String?,
        onProgress: ((Int) -> Unit)? = null
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        if (dest.exists()) dest.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FeaturesticUpdater")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(8192)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct) }
                    }
                }
            }
        }
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw IOException("APK checksum match nahi hua (download corrupt ho sakta hai)")
            }
        }
        return dest
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FeaturesticUpdater")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
