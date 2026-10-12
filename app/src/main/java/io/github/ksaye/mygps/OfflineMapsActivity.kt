package io.github.ksaye.mygps

import android.app.AlertDialog
import android.app.DownloadManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL

/** Manage downloaded maps and browse download.mapsforge.org for more. */
class OfflineMapsActivity : AppCompatActivity() {

    private data class Entry(val name: String, val isDir: Boolean, val size: String)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var installedBox: LinearLayout
    private lateinit var downloadsBox: LinearLayout
    private lateinit var browseBox: LinearLayout
    private lateinit var browsePath: TextView
    private var path = ""          // relative to OfflineMaps.SERVER, "" or ending in '/'
    private var lat = Double.NaN
    private var lon = Double.NaN

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OfflineMaps.loadSettings(this)
        lat = intent.getDoubleExtra("lat", Double.NaN)
        lon = intent.getDoubleExtra("lon", Double.NaN)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = TextView(this).apply {
            text = "Offline maps"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(getColor(R.color.bar))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (64 * dp).toInt()
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        content.addView(note("Downloaded maps draw with no network at all. When the area on " +
            "screen is covered by one, the map uses it; elsewhere it uses the online map."))

        content.addView(section("On this phone"))
        installedBox = card().also { content.addView(it) }
        downloadsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(downloadsBox)

        content.addView(section("Download a map"))
        content.addView(Switch(this).apply {
            text = "Allow mobile data (roaming charges may apply). Off = Wi-Fi only."
            textSize = 15f
            isChecked = OfflineMaps.allowMobileData
            setPadding(px(4), px(4), px(4), px(8))
            setOnCheckedChangeListener { _, on -> OfflineMaps.setAllowMobileData(this@OfflineMapsActivity, on) }
        })
        browsePath = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.muted)) }
        content.addView(browsePath)
        browseBox = card().also { content.addView(it) }

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.setPadding(px(20), bars.top, px(8), 0)
            header.minimumHeight = px(64) + bars.top
            content.setPadding(px(12) + bars.left, px(8), px(12) + bars.right, px(24) + bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (path.isEmpty()) finish() else browse(path.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })
            }
        })

        browse("")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    // ---- installed + in-progress -------------------------------------------------

    private fun refresh() {
        main.removeCallbacksAndMessages(null)
        Thread {
            val (active, messages) = OfflineMaps.reconcile(this)
            val files = OfflineMaps.installed(this)
            val here = if (lat.isNaN()) null else OfflineMaps.covering(this, lat, lon)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                messages.forEach { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                showInstalled(files, here)
                showDownloads(active)
                if (active.isNotEmpty()) main.postDelayed({ refresh() }, 1000)
            }
        }.start()
    }

    private fun showInstalled(files: List<java.io.File>, here: java.io.File?) {
        installedBox.removeAllViews()
        if (files.isEmpty()) {
            installedBox.addView(note("None yet. Pick a country or state below — on Wi-Fi, before you lose signal."))
            return
        }
        for (f in files) {
            val sub = Formatter.formatShortFileSize(this, f.length()) +
                if (f == here) "  ·  covers where you are" else ""
            installedBox.addView(row(pretty(f.name), sub, "Delete") {
                AlertDialog.Builder(this)
                    .setMessage("Delete the ${pretty(f.name)} offline map?")
                    .setPositiveButton("Delete") { _, _ -> OfflineMaps.delete(this, f); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
    }

    private fun showDownloads(active: List<OfflineMaps.Download>) {
        downloadsBox.removeAllViews()
        for (d in active) {
            val box = card()
            val status = when (d.status) {
                DownloadManager.STATUS_PENDING -> "Starting…"
                DownloadManager.STATUS_PAUSED -> when (d.reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped, will retry"
                    else -> "Paused"
                }
                else -> if (d.total > 0) "${Formatter.formatShortFileSize(this, d.done)} of " +
                    Formatter.formatShortFileSize(this, d.total) else "Downloading…"
            }
            box.addView(row(pretty(d.name), status, "Cancel") { OfflineMaps.cancel(this, d.id); refresh() })
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                isIndeterminate = d.total <= 0
                if (d.total > 0) progress = (d.done * 1000 / d.total).toInt()
            })
            downloadsBox.addView(box)
        }
    }

    // ---- server browser ----------------------------------------------------------

    private fun browse(p: String) {
        path = p
        browsePath.text = "mapsforge.org / " + p.trimEnd('/').replace("/", " / ").replace('-', ' ')
        browseBox.removeAllViews()
        browseBox.addView(note("Loading…"))
        Thread {
            val result = runCatching { list(p) }
            runOnUiThread {
                if (path != p || isFinishing) return@runOnUiThread
                browseBox.removeAllViews()
                result.onFailure {
                    browseBox.addView(note("Couldn't reach mapsforge.org (${it.message}). Needs a connection; Wi-Fi is best."))
                }
                result.onSuccess { entries ->
                    if (p.isNotEmpty()) browseBox.addView(row("‹ Back", null, null) { onBackPressedDispatcher.onBackPressed() })
                    for (e in entries) {
                        if (e.isDir) browseBox.addView(row(pretty(e.name) + "  ›", null, null) { browse(p + e.name) })
                        else browseBox.addView(row(pretty(e.name), e.size, null) { confirmDownload(p + e.name, e) })
                    }
                }
            }
        }.start()
    }

    private fun confirmDownload(fullPath: String, e: Entry) {
        val over = if (OfflineMaps.allowMobileData) "Wi-Fi or mobile data" else "Wi-Fi only"
        AlertDialog.Builder(this)
            .setTitle(pretty(e.name))
            .setMessage("Download ${e.size}B? ($over)\n\nIt keeps going in the background; you can leave this screen.")
            .setPositiveButton("Download") { _, _ ->
                OfflineMaps.download(this, fullPath, pretty(e.name))
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Parses the server's Apache directory listing. */
    private fun list(p: String): List<Entry> {
        val conn = URL(OfflineMaps.SERVER + p).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val html = conn.inputStream.bufferedReader().readText()
            return ROW.findAll(html).mapNotNull { m ->
                val href = m.groupValues[1]
                when {
                    href.endsWith("/") && !href.startsWith("/") && !href.startsWith("?") ->
                        Entry(href, true, "")
                    href.endsWith(".map") -> Entry(href, false, m.groupValues[2].trim())
                    else -> null
                }
            }.toList()
        } finally { conn.disconnect() }
    }

    // ---- small view helpers --------------------------------------------------------

    private fun px(v: Int) = (v * dp).toInt()

    private fun pretty(name: String) = name.removeSuffix(".part").removeSuffix(".map").trimEnd('/')
        .split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTextColor(getColor(R.color.label))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(px(4), px(18), px(4), px(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor("#555555"))
        setPadding(px(4), px(8), px(4), px(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        elevation = dp
        setPadding(px(12), px(4), px(12), px(4))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) }
    }

    private fun row(title: String, sub: String?, action: String?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(52)
            setPadding(0, px(6), 0, px(6))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(getColor(R.color.label)) })
        if (sub != null) texts.addView(TextView(this).apply { text = sub; textSize = 14f; setTextColor(getColor(R.color.muted)) })
        r.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (action != null) {
            r.addView(Button(this).apply { text = action; setOnClickListener { onClick() } })
        } else {
            r.isClickable = true
            r.setBackgroundResource(android.R.drawable.list_selector_background)
            r.setOnClickListener { onClick() }
        }
        return r
    }

    companion object {
        private val ROW = Regex("""<a href="([^"]+)">[^<]*</a>\s*</td><td[^>]*>[^<]*</td><td[^>]*>\s*([^<]*?)\s*</td>""")
    }
}
