package io.github.ksaye.mygps

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * Downloaded Mapsforge vector maps (one .map file per country/state, from
 * download.mapsforge.org). Rendered on the phone, so they need no network.
 *
 * Files download as `<name>.part` and are renamed only once complete and readable,
 * so the map never opens a half-written file.
 */
object OfflineMaps {
    const val SERVER = "https://download.mapsforge.org/maps/v5/"
    private const val PREFS = "offline_maps"

    data class Download(val id: Long, val name: String, val status: Int, val reason: Int,
                        val done: Long, val total: Long)

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "maps").apply { mkdirs() }

    fun installed(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()

    /** Changes whenever a map is added, replaced or removed. */
    fun signature(ctx: Context): String =
        installed(ctx).joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }

    private val boundsCache = HashMap<String, BoundingBox?>()

    fun bounds(file: File): BoundingBox? = synchronized(boundsCache) {
        val key = "${file.path}:${file.lastModified()}"
        boundsCache.getOrPut(key) {
            try { MapFile(file).let { m -> m.boundingBox().also { m.close() } } } catch (e: Exception) { null }
        }
    }

    /** The installed map covering a point, if any. */
    fun covering(ctx: Context, lat: Double, lon: Double): File? =
        installed(ctx).firstOrNull { bounds(it)?.contains(lat, lon) == true }

    // ---- downloads ---------------------------------------------------------------

    private fun ids(ctx: Context): MutableSet<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", emptySet())!!.toMutableSet()

    private fun saveIds(ctx: Context, ids: Set<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("ids", ids).apply()

    var allowMobileData: Boolean = false

    fun loadSettings(ctx: Context) {
        allowMobileData = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("mobile", false)
    }

    fun setAllowMobileData(ctx: Context, on: Boolean) {
        allowMobileData = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("mobile", on).apply()
    }

    /** [path] is relative to [SERVER], e.g. `central-america/panama.map`. */
    fun download(ctx: Context, path: String, label: String) {
        val name = path.substringAfterLast('/')
        File(dir(ctx), "$name.part").delete()
        val req = DownloadManager.Request(Uri.parse(SERVER + path))
            .setTitle("My GPS offline map: $label")
            .setDescription(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(File(dir(ctx), "$name.part")))
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(allowMobileData)
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        saveIds(ctx, ids(ctx).apply { add(id.toString()) })
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(DownloadManager::class.java).remove(id) // also deletes the .part
        saveIds(ctx, ids(ctx).apply { remove(id.toString()) })
    }

    fun delete(ctx: Context, file: File) { file.delete() }

    /**
     * Current downloads; also finishes completed ones (rename + validate) and drops
     * failed ones. Returns the in-progress list plus messages about what finished.
     */
    @Synchronized
    fun reconcile(ctx: Context): Pair<List<Download>, List<String>> {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val ids = ids(ctx)
        val active = ArrayList<Download>()
        val messages = ArrayList<String>()
        for (idStr in ids.toList()) {
            val id = idStr.toLong()
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            if (c == null || !c.moveToFirst()) { c?.close(); ids.remove(idStr); continue }
            val d = c.use {
                Download(id,
                    it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)) ?: "",
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
            }
            when (d.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val part = File(dir(ctx), "${d.name}.part")
                    val target = File(dir(ctx), d.name)
                    val ok = part.exists() && runCatching { MapFile(part).close() }.isSuccess
                    if (ok) {
                        target.delete()
                        part.renameTo(target)
                        messages += "${d.name.removeSuffix(".map")} is ready to use offline"
                    } else {
                        part.delete()
                        messages += "${d.name} downloaded but is not a valid map file"
                    }
                    dm.remove(id) // forget it; the .part path no longer exists
                    ids.remove(idStr)
                }
                DownloadManager.STATUS_FAILED -> {
                    messages += "${d.name} failed to download (error ${d.reason})"
                    dm.remove(id)
                    ids.remove(idStr)
                }
                else -> active += d
            }
        }
        saveIds(ctx, ids)
        return active to messages
    }
}

/** Finishes a download even if My GPS isn't open when it completes. */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { OfflineMaps.reconcile(context) } finally { pending.finish() }
        }.start()
    }
}
