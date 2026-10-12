package io.github.ksaye.mygps

import kotlin.math.floor

/**
 * Open Location Code ("plus code") encoder, 11 characters (~3.5 m x 2.8 m).
 * Integer arithmetic throughout so a coordinate on a cell boundary can't round
 * into the neighbouring cell.
 */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val PAIR_PRECISION = 8000L // 1/8000 degree after five pairs
    private const val GRID_ROWS = 5
    private const val GRID_COLS = 4

    fun encode(latitude: Double, longitude: Double): String {
        val lat = latitude.coerceIn(-90.0, 90.0)
        var lng = longitude
        while (lng < -180) lng += 360
        while (lng >= 180) lng -= 360

        var latVal = floor((lat + 90) * PAIR_PRECISION * GRID_ROWS).toLong()
        var lngVal = floor((lng + 180) * PAIR_PRECISION * GRID_COLS).toLong()
        // Latitude 90 exactly belongs to the cell below it.
        latVal = latVal.coerceAtMost(180L * PAIR_PRECISION * GRID_ROWS - 1)

        val sb = StringBuilder()
        sb.append(ALPHABET[((latVal % GRID_ROWS) * GRID_COLS + lngVal % GRID_COLS).toInt()])
        latVal /= GRID_ROWS
        lngVal /= GRID_COLS
        repeat(5) {
            sb.append(ALPHABET[(lngVal % 20).toInt()])
            sb.append(ALPHABET[(latVal % 20).toInt()])
            latVal /= 20
            lngVal /= 20
        }
        val code = sb.reverse().toString()
        return code.substring(0, 8) + "+" + code.substring(8)
    }
}
