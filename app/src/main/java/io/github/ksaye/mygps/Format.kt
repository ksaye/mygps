package io.github.ksaye.mygps

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Formatting shared by the phone screens and the Android Auto screen. */
object Format {
    private val POINTS16 = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun dms(value: Double, pos: Char, neg: Char): String {
        val a = abs(value)
        var deg = floor(a).toInt()
        var min = floor((a - deg) * 60).toInt()
        var sec = (a - deg - min / 60.0) * 3600
        // Keep 59.9996" from printing as 60.000".
        if (sec >= 59.9995) { sec = 0.0; min++ }
        if (min == 60) { min = 0; deg++ }
        return String.format(Locale.US, "%d°%d'%06.3f\"%c", deg, min, sec, if (value < 0) neg else pos)
    }

    fun cardinal16(deg: Float) = POINTS16[((deg + 11.25f) / 22.5f).toInt().mod(16)]
}
