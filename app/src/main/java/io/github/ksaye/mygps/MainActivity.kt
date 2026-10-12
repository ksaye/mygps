package io.github.ksaye.mygps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var compass: CompassView
    private lateinit var heading: TextView
    private lateinit var cardinal: TextView
    private lateinit var lat: TextView
    private lateinit var lon: TextView
    private lateinit var sats: TextView
    private lateinit var accuracy: TextView
    private lateinit var ttff: TextView
    private lateinit var lastFix: TextView
    private lateinit var speed: TextView
    private lateinit var altitude: TextView
    private lateinit var plusCode: TextView
    private lateinit var dial: CompassDialView
    private lateinit var bigHeading: TextView
    private lateinit var bigCardinal: TextView
    private lateinit var northMode: TextView
    private lateinit var calibrate: TextView
    private lateinit var mapReadout: TextView
    private lateinit var mapPage: MapPage
    private lateinit var pages: List<View>
    private var tab = 0
    private var trueNorth = true
    private var haveDeclination = false

    private var lastLocation: Location? = null
    private var decimal = false

    // Time to first fix: the GNSS engine reports it via onFirstFix, but only when
    // it cold-starts for us. If another app already had the GPS running it never
    // fires, so we also time it ourselves from the moment we asked for updates.
    private var requestedAt = 0L
    private var ttffMillis = -1

    // Mean-sea-level altitude from the most recent $GxGGA sentence.
    @Volatile private var nmeaMslMeters = Double.NaN
    @Volatile private var nmeaMslAt = 0L

    // Smoothed compass as a unit vector, so averaging across 359/0 works.
    private var headX = 0.0
    private var headY = 0.0
    private var headValid = false
    private var lastCompassDraw = 0L
    private var declination = 0f
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapPage.configure(this)
        setContentView(R.layout.activity_main)
        // Android 15+ draws edge to edge: pad the blue bars so the clock and the
        // back/home/recents buttons sit on them, not on our title and tabs.
        val header = findViewById<View>(R.id.header)
        val nav = findViewById<View>(R.id.nav)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.setPadding(header.paddingLeft, bars.top, header.paddingRight, 0)
            header.minimumHeight = (64 * resources.displayMetrics.density).toInt() + bars.top
            nav.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)

        status = findViewById(R.id.status)
        compass = findViewById(R.id.compass)
        heading = findViewById(R.id.heading)
        cardinal = findViewById(R.id.cardinal)
        lat = findViewById(R.id.lat)
        lon = findViewById(R.id.lon)
        sats = findViewById(R.id.sats)
        accuracy = findViewById(R.id.accuracy)
        ttff = findViewById(R.id.ttff)
        lastFix = findViewById(R.id.lastFix)
        speed = findViewById(R.id.speed)
        altitude = findViewById(R.id.altitude)
        plusCode = findViewById(R.id.plusCode)
        dial = findViewById(R.id.dial)
        bigHeading = findViewById(R.id.bigHeading)
        bigCardinal = findViewById(R.id.bigCardinal)
        northMode = findViewById(R.id.northMode)
        calibrate = findViewById(R.id.calibrate)
        mapReadout = findViewById(R.id.mapReadout)
        mapPage = MapPage(findViewById(R.id.map), findViewById(R.id.recenter), findViewById(R.id.mapSource))
        findViewById<View>(R.id.offlineMaps).setOnClickListener {
            startActivity(Intent(this, OfflineMapsActivity::class.java).apply {
                lastLocation?.let { putExtra("lat", it.latitude); putExtra("lon", it.longitude) }
            })
        }

        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        trueNorth = prefs.getBoolean("trueNorth", true)
        northMode.setOnClickListener {
            trueNorth = !trueNorth
            prefs.edit().putBoolean("trueNorth", trueNorth).apply()
            showNorthMode()
        }
        showNorthMode()

        pages = listOf(findViewById(R.id.dataPage), findViewById(R.id.mapPage), findViewById(R.id.compassPage))
        listOf(R.id.tabData, R.id.tabMap, R.id.tabCompass).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener {
                selectTab(i)
                prefs.edit().putInt("tab", i).apply()
            }
        }
        selectTab(prefs.getInt("tab", 0))

        findViewById<View>(R.id.coordsCard).setOnClickListener {
            decimal = !decimal
            lastLocation?.let { showPosition(it) }
        }
        findViewById<View>(R.id.copy).setOnClickListener { copyCoordinates() }
        findViewById<View>(R.id.share).setOnClickListener { share() }
        val version = findViewById<TextView>(R.id.version)
        version.text = "My GPS ${BuildConfig.VERSION_NAME}"
        version.append(" · Check for updates")
        version.setOnClickListener { checkForUpdate(force = true) }
        checkForUpdate(force = false)
        status.setOnClickListener {
            if (!hasPermission()) requestPermission()
            else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    override fun onStart() {
        super.onStart()
        startNetworkWatch()
        if (hasPermission()) startGps() else requestPermission()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Only for its accuracy reports: that's how Android says "needs calibrating".
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        mapPage.onResume()
        mapPage.refreshOffline()
        Thread { OfflineMaps.reconcile(this) }.start()
    }

    override fun onPause() {
        super.onPause()
        mapPage.onPause()
    }

    // ---- network: offline maps take over whenever there's no working internet --------

    private val network = object : ConnectivityManager.NetworkCallback() {
        // Fires on ConnectivityThread; the map may only be touched on the main thread.
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { mapPage.online = ok }
        }
        override fun onLost(n: Network) { main.post { mapPage.online = false } }
    }

    private fun startNetworkWatch() {
        val cm = getSystemService(ConnectivityManager::class.java)
        mapPage.online = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        cm.registerDefaultNetworkCallback(network)
    }

    // ---- tabs -------------------------------------------------------------------

    private fun selectTab(i: Int) {
        tab = i.coerceIn(0, 2)
        pages.forEachIndexed { n, v -> v.visibility = if (n == tab) View.VISIBLE else View.GONE }
        TAB_ICONS.forEachIndexed { n, icon ->
            val color = if (n == tab) 0xFFFFFFFF.toInt() else 0xFF9FA8DA.toInt()
            findViewById<android.widget.ImageView>(icon).setColorFilter(color)
            findViewById<TextView>(TAB_TEXTS[n]).setTextColor(color)
        }
        if (tab == 1) lastLocation?.let { mapPage.update(it) }
    }

    override fun onStop() {
        super.onStop()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        stopGps()
        sensorManager.unregisterListener(this)
    }

    // ---- self-update ------------------------------------------------------------

    private fun checkForUpdate(force: Boolean) = Updater.check(this, force) { release ->
        val banner = findViewById<TextView>(R.id.update)
        banner.text = "My GPS ${release.version} is available. Tap to update."
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            Updater.install(this, release) { msg ->
                if (msg != null) banner.text = msg else banner.visibility = View.GONE
            }
        }
    }

    // ---- permissions ----------------------------------------------------------

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() = requestPermissions(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermission()) startGps()
        else showStatus("Precise location permission is needed. Tap here to grant it.")
    }

    // ---- GPS ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showStatus("Location is turned off. Tap here to open location settings.")
            return
        }
        showStatus(null)
        requestedAt = SystemClock.elapsedRealtime()
        ttffMillis = -1
        ttff.text = "--"
        locationManager.registerGnssStatusCallback(gnssCallback, main)
        locationManager.addNmeaListener(nmeaListener, main)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        // Something to look at while the receiver acquires.
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            showPosition(it)
            mapPage.update(it, live = false)
            lastFix.text = timeOf(it) + "*"
        }
    }

    private fun stopGps() {
        locationManager.removeUpdates(this)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        locationManager.removeNmeaListener(nmeaListener)
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var used = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) used++
            sats.text = "$used/${s.satelliteCount}"
        }

        override fun onFirstFix(ttffMillis: Int) = showTtff(ttffMillis)

        override fun onStopped() { sats.text = "--" }
    }

    private val nmeaListener = OnNmeaMessageListener { msg, _ ->
        // $GPGGA,time,lat,N,lon,W,quality,numSats,hdop,ALTITUDE,M,geoidSep,M,...
        if (msg.length > 6 && msg.startsWith("GGA", 3)) {
            val f = msg.split(',')
            if (f.size > 10 && f[6].isNotEmpty() && f[6] != "0") {
                f[9].toDoubleOrNull()?.let {
                    nmeaMslMeters = it
                    nmeaMslAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (ttffMillis < 0) showTtff((SystemClock.elapsedRealtime() - requestedAt).toInt())
        showPosition(location)
        lastFix.text = timeOf(location)

        accuracy.text = if (location.hasAccuracy())
            String.format(Locale.getDefault(), "%.1f ft", location.accuracy * FEET) else "--"
        speed.text = if (location.hasSpeed())
            String.format(Locale.getDefault(), "%.1f mph", location.speed * MPH) else "--"
        altitude.text = altitudeText(location)
        mapPage.refreshOffline() // cheap when nothing changed; picks up a just-finished download
        mapPage.update(location)
        mapReadout.text = listOfNotNull(
            if (location.hasSpeed()) speed.text else null,
            if (location.hasAccuracy()) "±" + accuracy.text else null,
            altitude.text.toString().removeSuffix(" AMSL"),
        ).joinToString("  ·  ")

        declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(),
            location.altitude.toFloat(), location.time).declination
        if (!haveDeclination) { haveDeclination = true; showNorthMode() }
    }

    override fun onProviderDisabled(provider: String) =
        showStatus("Location is turned off. Tap here to open location settings.")

    override fun onProviderEnabled(provider: String) { stopGps(); startGps() }

    private fun showTtff(ms: Int) {
        ttffMillis = ms
        ttff.text = if (ms >= 1000) "${ms / 1000} s ${ms % 1000} ms" else "$ms ms"
    }

    /** Mean sea level: GGA sentence, else Android 14's own MSL, else the raw WGS84 ellipsoid height. */
    private fun altitudeText(loc: Location): String {
        val nmeaFresh = !nmeaMslMeters.isNaN() && SystemClock.elapsedRealtime() - nmeaMslAt < 5000
        val msl = when {
            nmeaFresh -> nmeaMslMeters
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            else -> null
        }
        return when {
            msl != null -> String.format(Locale.getDefault(), "%.0f ft AMSL", msl * FEET)
            loc.hasAltitude() -> String.format(Locale.getDefault(), "%.0f ft WGS84", loc.altitude * FEET)
            else -> "--"
        }
    }

    // ---- position display ------------------------------------------------------

    private fun showPosition(loc: Location) {
        lastLocation = loc
        if (decimal) {
            lat.text = String.format(Locale.US, "%.6f°", loc.latitude)
            lon.text = String.format(Locale.US, "%.6f°", loc.longitude)
        } else {
            lat.text = dms(loc.latitude, 'N', 'S')
            lon.text = dms(loc.longitude, 'E', 'W')
        }
        plusCode.text = PlusCode.encode(loc.latitude, loc.longitude)
    }

    private fun dms(value: Double, pos: Char, neg: Char) = Format.dms(value, pos, neg)

    private fun timeOf(loc: Location): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(loc.time))

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- compass ----------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val az = orientation[0].toDouble()
        if (!headValid) { headX = cos(az); headY = sin(az); headValid = true }
        headX += (cos(az) - headX) * SMOOTHING
        headY += (sin(az) - headY) * SMOOTHING

        val now = SystemClock.elapsedRealtime()
        // Smooth turning on the compass page; the small compass doesn't need it.
        if (now - lastCompassDraw < if (tab == 2) 33 else 100) return
        lastCompassDraw = now
        val magnetic = ((Math.toDegrees(atan2(headY, headX)) + 360) % 360).toFloat()
        // Magnetic north -> true north once we know where we are.
        val trueDeg = (magnetic + declination + 360) % 360
        val deg = if (trueNorth) trueDeg else magnetic
        compass.heading = deg
        heading.text = "${deg.toInt()}°"
        cardinal.text = POINTS[((deg + 22.5f) / 45f).toInt() % 8]
        mapPage.compassHeading = trueDeg
        if (tab == 2) {
            dial.heading = deg
            bigHeading.text = "${deg.toInt()}°"
            bigCardinal.text = Format.cardinal16(deg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
            calibrate.visibility = if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) View.VISIBLE else View.GONE
    }

    private fun showNorthMode() {
        northMode.text = when {
            !trueNorth -> "Magnetic north  (tap for true north)"
            haveDeclination -> String.format(Locale.US, "True north, declination %.1f° %s  (tap for magnetic)",
                abs(declination), if (declination >= 0) "E" else "W")
            else -> "True north once GPS has a fix  (tap for magnetic)"
        }
    }

    // ---- copy / share -----------------------------------------------------------

    private fun coordText(loc: Location) =
        String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)

    private fun copyCoordinates() {
        val loc = lastLocation ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Coordinates", coordText(loc)))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val loc = lastLocation ?: run {
            Toast.makeText(this, "No fix yet", Toast.LENGTH_SHORT).show(); return
        }
        val c = coordText(loc).replace(" ", "")
        val text = "${coordText(loc)}\n${PlusCode.encode(loc.latitude, loc.longitude)}\n" +
            "https://maps.google.com/?q=$c"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share location"))
    }

    companion object {
        private const val FEET = 3.28084
        private const val MPH = 2.236936
        private const val SMOOTHING = 0.2
        private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        private val TAB_ICONS = intArrayOf(R.id.tabDataIcon, R.id.tabMapIcon, R.id.tabCompassIcon)
        private val TAB_TEXTS = intArrayOf(R.id.tabDataText, R.id.tabMapText, R.id.tabCompassText)
    }
}
