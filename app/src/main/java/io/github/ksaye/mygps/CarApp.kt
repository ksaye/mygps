package io.github.ksaye.mygps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.ln

/**
 * Android Auto entry point. Declared as a NAVIGATION app because that is the only
 * Android Auto category allowed to draw its own map on the car screen.
 */
class MyGpsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) GpsCarScreen(carContext)
            else NoPermissionScreen(carContext)
    }
}

private class NoPermissionScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("My GPS needs location. Open it on your phone once and allow precise location.").build()
}

/** The car screen: map or compass on the surface, buttons in the template. */
private class GpsCarScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    private val main = Handler(Looper.getMainLooper())
    private val surface = CarSurface(ctx)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var used = 0
    private var visible = 0
    private var compassMode = false

    init { lifecycle.addObserver(this) }

    private val location = LocationListener { surface.update(it, used, visible, live = true) }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            visible = s.satelliteCount
            used = (0 until s.satelliteCount).count { s.usedInFix(it) }
        }
    }

    // Fires on ConnectivityThread; everything the map touches must be on the main thread.
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { surface.online = ok }
        }
        override fun onLost(n: Network) { main.post { surface.online = false } }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
    }

    @Suppress("MissingPermission") // checked before this screen is created
    override fun onStart(owner: LifecycleOwner) {
        // Keeps GPS flowing while the phone is locked in its mount. If Android refuses
        // to start it from here, location still works while My GPS is allowed it.
        runCatching {
            ContextCompat.startForegroundService(carContext, Intent(carContext, CarLocationService::class.java))
        }
        surface.online = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        cm.registerDefaultNetworkCallback(network)
        runCatching {
            lm.registerGnssStatusCallback(gnss, main)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, location, Looper.getMainLooper())
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { surface.update(it, 0, 0, live = false) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        lm.removeUpdates(location)
        lm.unregisterGnssStatusCallback(gnss)
        runCatching { cm.unregisterNetworkCallback(network) }
        carContext.stopService(Intent(carContext, CarLocationService::class.java))
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val actions = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(if (compassMode) "Map" else "Compass").setOnClickListener {
                compassMode = !compassMode
                surface.setCompassMode(compassMode)
                invalidate()
            }.build())
            .addAction(Action.Builder().setTitle("Center").setOnClickListener { surface.recenter() }.build())
            .build()
        val mapActions = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_in)).setOnClickListener { surface.zoom(1.0) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_out)).setOnClickListener { surface.zoom(-1.0) }.build())
        if (carContext.carAppApiLevel >= 2 && !compassMode) mapActions.addAction(Action.PAN)
        return NavigationTemplate.Builder()
            .setActionStrip(actions)
            .setMapActionStrip(mapActions.build())
            .apply { if (carContext.carAppApiLevel >= 2) setPanModeListener { } }
            .build()
    }
}

/**
 * Draws on the car's display: a Presentation on a VirtualDisplay backed by the
 * surface Android Auto hands us, so ordinary Views (the osmdroid map, the compass
 * dial) can render there.
 */
private class CarSurface(private val ctx: CarContext) : SurfaceCallback {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapPage: MapPage? = null
    private var mapView: MapView? = null
    private var compassBox: View? = null
    private var dial: CompassDialView? = null
    private var panel: LinearLayout? = null
    private val lines = arrayOfNulls<TextView>(4)
    private var compassLines = arrayOfNulls<TextView>(4)
    private var compassMode = false
    private var lastCourse = Float.NaN
    private var pending: Triple<Location, Int, Int>? = null

    var online = true
        set(value) { field = value; mapPage?.online = value }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val s = container.surface ?: return
        release()
        val vd = ctx.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "MyGPS", container.width, container.height, container.dpi, s,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(ctx, vd.display)
        presentation = p
        val c = p.context
        val dp = c.resources.displayMetrics.density

        val root = FrameLayout(c).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val map = MapView(c)
        mapView = map
        root.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Compass mode: dial on the left, readouts on the right (car screens are wide).
        val cbox = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            visibility = View.GONE
        }
        val d = CompassDialView(c)
        dial = d
        cbox.addView(d, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val sizes = floatArrayOf(44f, 26f, 18f, 18f)
        for (i in 0..3) {
            compassLines[i] = TextView(c).apply { textSize = sizes[i]; setTextColor(Color.WHITE) }
            col.addView(compassLines[i])
        }
        cbox.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = (24 * dp).toInt() })
        compassBox = cbox
        root.addView(cbox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Readout card over the map.
        val pnl = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD202124"))
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        val psizes = floatArrayOf(34f, 18f, 15f, 14f)
        for (i in 0..3) {
            lines[i] = TextView(c).apply { textSize = psizes[i]; setTextColor(Color.WHITE) }
            pnl.addView(lines[i])
        }
        lines[0]!!.text = "Waiting for GPS…"
        panel = pnl
        root.addView(pnl, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val sourceLabel = TextView(c).apply {
            textSize = 12f; setTextColor(Color.parseColor("#333333")); setBackgroundColor(Color.parseColor("#B3FFFFFF"))
        }
        root.addView(sourceLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        p.setContentView(root)
        p.show()

        // The phone's compass is meaningless in a car; the arrow follows GPS course.
        mapPage = MapPage(map, View(c), sourceLabel).apply {
            online = this@CarSurface.online
            refreshOffline()
            onResume()
        }
        setCompassMode(compassMode)
        pending?.let { (loc, u, v) -> update(loc, u, v, live = false) }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Keep the readouts clear of Android Auto's own buttons.
        panel?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = visibleArea.left + 8
            lp.topMargin = visibleArea.top + 8
            it.layoutParams = lp
        }
        compassBox?.setPadding(visibleArea.left, visibleArea.top,
            (compassBox!!.width - visibleArea.right).coerceAtLeast(0),
            (compassBox!!.height - visibleArea.bottom).coerceAtLeast(0))
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) { mapPage?.panBy(distanceX, distanceY) }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor > 0) zoom(ln(scaleFactor.toDouble()) / ln(2.0))
    }

    private fun release() {
        mapPage?.onPause()
        mapPage = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    fun zoom(steps: Double) {
        val m = mapView ?: return
        m.controller.setZoom((m.zoomLevelDouble + steps).coerceIn(3.0, m.maxZoomLevel))
    }

    fun recenter() { mapPage?.recenter() }

    fun setCompassMode(on: Boolean) {
        compassMode = on
        mapView?.visibility = if (on) View.GONE else View.VISIBLE
        panel?.visibility = if (on) View.GONE else View.VISIBLE
        compassBox?.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun update(loc: Location, used: Int, visible: Int, live: Boolean) {
        pending = Triple(loc, used, visible)
        val page = mapPage ?: return
        page.update(loc, live)

        // Course only means something while moving; hold the last one when stopped.
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) lastCourse = loc.bearing
        page.compassHeading = Float.NaN
        val mph = if (loc.hasSpeed()) loc.speed * 2.236936 else Double.NaN
        val speed = if (mph.isNaN()) "-- mph" else String.format(Locale.US, "%.0f mph", mph)
        val course = if (lastCourse.isNaN()) "Course --" else "${lastCourse.toInt()}° ${Format.cardinal16(lastCourse)}"
        val alt = if (loc.hasAltitude()) {
            val m = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else loc.altitude
            String.format(Locale.US, "%.0f ft", m * 3.28084)
        } else "-- ft"
        val pos = Format.dms(loc.latitude, 'N', 'S') + "  " + Format.dms(loc.longitude, 'E', 'W')
        val fix = (if (visible > 0) "$used/$visible sats" else "GPS") +
            (if (loc.hasAccuracy()) String.format(Locale.US, " · ±%.0f ft", loc.accuracy * 3.28084) else "") +
            (if (!live) " · last known" else "")

        lines[0]?.text = speed
        lines[1]?.text = "$course · $alt"
        lines[2]?.text = pos
        lines[3]?.text = fix

        dial?.heading = if (lastCourse.isNaN()) 0f else lastCourse
        compassLines[0]?.text = if (lastCourse.isNaN()) "--°" else "${lastCourse.toInt()}° ${Format.cardinal16(lastCourse)}"
        compassLines[1]?.text = "$speed · $alt"
        compassLines[2]?.text = pos
        compassLines[3]?.text = "Course from GPS (needs movement) · $fix"
    }
}

/** Foreground service so GPS keeps updating for the car while the phone is locked. */
class CarLocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("car", "Android Auto", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "car")
            .setSmallIcon(R.drawable.ic_place)
            .setContentTitle("My GPS is showing your location in the car")
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, 1, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
