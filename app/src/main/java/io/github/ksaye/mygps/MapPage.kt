package io.github.ksaye.mygps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.view.MotionEvent
import android.view.View
import android.app.Application
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.mapsforge.MapsForgeTileModuleProvider
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * OpenStreetMap page: you-are-here arrow with an accuracy circle, the trail since
 * the app opened, and follow mode that switches off when you pan the map.
 */
class MapPage(private val map: MapView, private val recenter: View, private val source: TextView) {

    private val ctx = map.context
    private var offlineSignature: String? = null
    private var offlineSource: MapsForgeTileSource? = null
    private var offlineFiles: List<File> = emptyList()
    private var usingOffline: Boolean? = null

    private var follow = true
    private var last: Location? = null
    private val here = HereOverlay()
    private val trail = Polyline(map).apply {
        outlinePaint.color = Color.parseColor("#3D4FE0")
        outlinePaint.strokeWidth = 5 * map.resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
    }

    /** Heading for the arrow, degrees true. Compass while slow, GPS course when moving. */
    var compassHeading = Float.NaN
        set(value) { field = value; if (map.isShown) map.invalidate() }

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(17.0)
        map.maxZoomLevel = 20.0
        // Open where we last were, so the map is useful before GPS (or any network).
        ctx.getSharedPreferences("map", Context.MODE_PRIVATE).let { p ->
            if (p.contains("lat")) map.controller.setCenter(
                GeoPoint(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble()))
        }
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { chooseSource(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean = false
        })
        map.overlays.add(trail)
        map.overlays.add(here)
        map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE) setFollow(false)
            false
        }
        recenter.setOnClickListener { recenter() }
        setFollow(true)
    }

    fun recenter() {
        setFollow(true)
        last?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude)) }
    }

    fun zoomIn() = map.controller.zoomIn()
    fun zoomOut() = map.controller.zoomOut()

    /** Drag from the car screen: stop following, like a pan on the phone. */
    fun panBy(dx: Float, dy: Float) {
        setFollow(false)
        map.scrollBy(dx.toInt(), dy.toInt())
    }

    private fun setFollow(on: Boolean) {
        follow = on
        recenter.alpha = if (on) 0.5f else 1f
    }

    /** Re-reads the installed offline maps (after a download or delete). */
    fun refreshOffline() {
        val world = worldMap(ctx)
        val sig = OfflineMaps.signature(ctx) + "|" + world.length()
        if (sig == offlineSignature) return
        offlineSignature = sig
        offlineSource?.dispose()
        val files = OfflineMaps.installed(ctx)
        offlineFiles = files
        // Cache name is tied to the file set, so tiles rendered before a neighbouring
        // map was added don't linger.
        offlineSource = runCatching {
            OfflineTileSource.create("offline-" + sig.hashCode().toUInt().toString(16), files, world)
        }.getOrNull()
        usingOffline = null
        chooseSource()
    }

    /** Set from the network callback; a captive portal (ship Wi-Fi) counts as offline. */
    var online = true
        set(value) { if (field != value) { field = value; chooseSource() } }

    /**
     * With internet: online OSM tiles, with the offline maps drawing any tile that
     * fails to download (weak signal). Without: offline maps only, so nothing waits
     * on downloads that can't happen. The world map means offline always covers.
     */
    private fun chooseSource() {
        val c = map.mapCenter
        val src = offlineSource
        val offline = src != null && !online
        if (offline != usingOffline) {
            usingOffline = offline
            map.setTileProvider(when {
                offline -> MapsForgeTileProvider(SimpleRegisterReceiver(ctx), src!!, null)
                src != null -> OnlineWithFallback(ctx, src)
                else -> MapTileProviderBasic(ctx, TileSourceFactory.MAPNIK)
            })
        }
        if (!offline) { source.text = "Online map"; return }
        // Runs on every scroll frame: cached file list, cached bounds, no disk access.
        val covering = offlineFiles.firstOrNull { OfflineMaps.bounds(it)?.contains(c.latitude, c.longitude) == true }
        source.text = if (covering != null)
            "Offline map · " + covering.name.removeSuffix(".map").replace('-', ' ') + " · no internet"
        else "Offline world map · no internet"
    }

    /** Online OSM tiles first; the offline renderer is the last resort for a tile. */
    private class OnlineWithFallback(ctx: Context, offline: MapsForgeTileSource) :
        MapTileProviderBasic(ctx, TileSourceFactory.MAPNIK) {
        init {
            mTileProviderList.add(MapsForgeTileModuleProvider(SimpleRegisterReceiver(ctx), offline,
                org.osmdroid.tileprovider.modules.SqlTileWriter()))
            // Labels can span tiles; Mapsforge asks for neighbours to be redrawn.
            offline.addTileRefresher { t ->
                expireInMemoryCache(org.osmdroid.util.MapTileIndex.getTileIndex(t.zoomLevel.toInt(), t.tileX, t.tileY))
            }
        }
    }

    fun update(loc: Location, live: Boolean = true) {
        val first = last == null
        last = loc
        if (live) ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
            .putFloat("lat", loc.latitude.toFloat()).putFloat("lon", loc.longitude.toFloat()).apply()
        val p = GeoPoint(loc.latitude, loc.longitude)
        // Only extend the trail with fixes good enough to draw a line through.
        if (live && (!loc.hasAccuracy() || loc.accuracy < 50)) {
            trail.addPoint(p)
            if (trail.actualPoints.size > MAX_TRAIL) trail.setPoints(trail.actualPoints.drop(MAX_TRAIL / 10))
        }
        if (first) { map.controller.setCenter(p); chooseSource() }
        else if (follow && map.isShown) map.controller.animateTo(p)
        map.invalidate()
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()

    private inner class HereOverlay : Overlay() {
        private val density = map.resources.displayMetrics.density
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333D4FE0") }
        private val haloEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#883D4FE0"); style = Paint.Style.STROKE; strokeWidth = density
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D4FE0") }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val arrow = Path()
        private val pt = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val loc = last ?: return
            mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()

            if (loc.hasAccuracy()) {
                val mPerPx = TileSystem.GroundResolution(loc.latitude, mapView.zoomLevelDouble)
                val radius = (loc.accuracy / mPerPx).toFloat()
                if (radius > 12 * density) {
                    c.drawCircle(x, y, radius, halo)
                    c.drawCircle(x, y, radius, haloEdge)
                }
            }

            val bearing = when {
                loc.hasSpeed() && loc.speed > 1.5f && loc.hasBearing() -> loc.bearing
                !compassHeading.isNaN() -> compassHeading
                else -> Float.NaN
            }
            val r = 9 * density
            c.drawCircle(x, y, r + 3 * density, white)
            if (bearing.isNaN()) {
                c.drawCircle(x, y, r, dot)
                return
            }
            // Map is north-up, so rotate the arrow by the heading itself.
            c.save()
            c.rotate(bearing - mapView.mapOrientation, x, y)
            arrow.reset()
            arrow.moveTo(x, y - r * 1.5f)
            arrow.lineTo(x + r, y + r)
            arrow.lineTo(x, y + r * 0.45f)
            arrow.lineTo(x - r, y + r)
            arrow.close()
            c.drawPath(arrow, dot)
            c.restore()
        }
    }

    companion object {
        private const val MAX_TRAIL = 5000

        /** The bundled world map (coastlines, borders, cities), copied out of the APK once. */
        fun worldMap(context: Context): File {
            val f = File(File(context.filesDir, "base").apply { mkdirs() }, "world.map")
            val size = context.assets.openFd("world.map").use { it.length }
            if (f.length() != size) {
                val tmp = File(f.parentFile, "world.map.tmp")
                context.assets.open("world.map").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(f)
            }
            return f
        }

        /** Must run before the MapView is inflated. */
        fun configure(context: Context) {
            MapsForgeTileSource.createInstance(context.applicationContext as Application)
            Configuration.getInstance().apply {
                // OSM's tile servers require an app-identifying user agent.
                userAgentValue = "${context.packageName}/${BuildConfig.VERSION_NAME}"
                osmdroidBasePath = File(context.filesDir, "osmdroid")
                osmdroidTileCache = File(context.cacheDir, "tiles")
            }
        }
    }
}
