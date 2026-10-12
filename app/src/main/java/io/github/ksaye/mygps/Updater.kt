package io.github.ksaye.mygps

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub release feed. The newest release's tag (`v1.2`) is
 * compared with our versionName; if newer and it carries an .apk, that APK is
 * downloaded and handed to PackageInstaller. Android shows its own confirm
 * prompt -- nothing installs silently.
 */
object Updater {
    private const val API = "https://api.github.com/repos/ksaye/mygps/releases/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val version: String, val apkUrl: String)

    /** Checks off the main thread; [found] runs on the UI thread only when an update exists. */
    fun check(activity: Activity, force: Boolean, found: (Release) -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS) return
        Thread {
            val release = try { latest() } catch (e: Exception) {
                if (force) activity.runOnUiThread {
                    Toast.makeText(activity, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            prefs.edit().putLong("lastCheck", now).apply()
            activity.runOnUiThread {
                if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) found(release)
                else if (force) Toast.makeText(activity, "My GPS is up to date", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "MyGPS/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun latest(): Release? {
        val conn = open(API)
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true))
                    return Release(json.getString("tag_name").trimStart('v', 'V'),
                        a.getString("browser_download_url"))
            }
            return null
        } finally { conn.disconnect() }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads and installs. [progress] gets status text on the UI thread. */
    fun install(activity: Activity, release: Release, progress: (String?) -> Unit) {
        // Android 8+: the user must allow "Install unknown apps" for My GPS once.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow My GPS to install updates, then tap Update again",
                Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            return
        }
        progress("Downloading ${release.version}…")
        Thread {
            try {
                val file = File(activity.cacheDir, "update.apk")
                val conn = open(release.apkUrl)
                try {
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally { conn.disconnect() }

                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(activity.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("update.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val callback = PendingIntent.getBroadcast(activity, id,
                        Intent(activity, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(callback.intentSender)
                }
                file.delete()
                activity.runOnUiThread { progress(null) }
            } catch (e: Exception) {
                activity.runOnUiThread { progress("Update failed: ${e.message}") }
            }
        }.start()
    }
}

/** Receives PackageInstaller results; raises Android's confirm screen when it asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(context,
                "Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}",
                Toast.LENGTH_LONG).show()
        }
    }
}
