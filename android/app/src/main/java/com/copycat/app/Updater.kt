package com.copycat.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val apkUrl: String)

object Updater {
    private const val LATEST_URL = "https://api.github.com/repos/BeathovenGala/copycat/releases/latest"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Returns a newer release, or null. Network call: run off the main thread. */
    fun check(context: Context, force: Boolean = false): Release? {
        val sp = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) {
            return sp.getString("pending", null)?.let(::parse)?.takeIf { isNewer(it.version, currentVersion(context)) }
        }
        val latest = runCatching { fetchLatest() }.getOrNull() ?: return null
        sp.edit().putLong("lastCheck", now).putString("pending", "${latest.version}|${latest.apkUrl}").apply()
        return latest.takeIf { isNewer(it.version, currentVersion(context)) }
    }

    private fun parse(s: String) = s.split("|", limit = 2).takeIf { it.size == 2 }?.let { Release(it[0], it[1]) }

    private fun fetchLatest(): Release? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CopyCat")
            connectTimeout = 5000
            readTimeout = 10000
        }
        if (conn.responseCode != 200) return null
        val json = JSONObject(conn.inputStream.use { it.reader().readText() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"))
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val diff = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Downloads the APK and hands it to the system installer, which asks the user to confirm. */
    fun install(context: Context, release: Release) {
        val apk = File(context.cacheDir, "update.apk")
        (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "CopyCat")
            connectTimeout = 10000
            readTimeout = 60000
        }.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("copycat.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Toast.makeText(
                context, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG,
            ).show()
        }
    }
}
