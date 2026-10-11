package com.txdost

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Info about a newer build published on GitHub Releases. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/**
 * Checks the rolling "latest" GitHub Release for a newer APK and downloads it.
 * The release carries `version.json` (versionCode/versionName) + `app-debug.apk`.
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
                    "app-debug.apk" -> apkUrl = a.getString("browser_download_url")
                }
            }
            if (versionJsonUrl == null || apkUrl == null) return null
            val info = JSONObject(httpGet(versionJsonUrl))
            val remoteCode = info.optInt("versionCode", 0)
            val remoteName = info.optString("versionName", "")
            if (remoteCode > BuildConfig.VERSION_CODE) UpdateInfo(remoteCode, remoteName, apkUrl) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Download the APK into cache/updates/ and return the file. */
    fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        if (dest.exists()) dest.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FeaturesticUpdater")
        }
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FeaturesticUpdater")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
