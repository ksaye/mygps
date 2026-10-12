package io.github.ksaye.mygps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Round compass whose red needle points to north for the given heading. */
class CompassView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Direction the top of the phone faces, degrees clockwise from north. NaN = unknown. */
    var heading: Float = Float.NaN
        set(value) { field = value; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#222222")
    }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val south = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#222222") }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val needle = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        ring.strokeWidth = r * 0.07f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        if (heading.isNaN()) return

        val len = r * 0.72f
        val half = r * 0.13f
        canvas.save()
        canvas.rotate(-heading, cx, cy)
        needle.reset()
        needle.moveTo(cx, cy - len); needle.lineTo(cx + half, cy); needle.lineTo(cx - half, cy); needle.close()
        canvas.drawPath(needle, north)
        needle.reset()
        needle.moveTo(cx, cy + len); needle.lineTo(cx + half, cy); needle.lineTo(cx - half, cy); needle.close()
        canvas.drawPath(needle, south)
        canvas.drawCircle(cx, cy, half * 0.45f, hub)
        canvas.restore()
    }
}
