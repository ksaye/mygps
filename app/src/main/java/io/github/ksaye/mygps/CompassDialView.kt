package io.github.ksaye.mygps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Full-size compass card. The card turns so the current heading sits under the
 * fixed red index at the top, like a hand-held or marine compass.
 */
class CompassDialView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var heading: Float = Float.NaN
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#2E3B9E")
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444") }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444"); textAlign = Paint.Align.CENTER
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222"); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCCCCC") }
    private val index = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square, as large as the space allows.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4 * density
        val dial = r * 0.9f

        // Fixed index triangle above the card.
        index.reset()
        index.moveTo(cx, cy - r + r * 0.1f)
        index.lineTo(cx - r * 0.05f, cy - r)
        index.lineTo(cx + r * 0.05f, cy - r)
        index.close()
        canvas.drawPath(index, red)

        canvas.drawCircle(cx, cy, dial, face)
        rim.strokeWidth = r * 0.025f
        canvas.drawCircle(cx, cy, dial, rim)

        // Lubber cross in the middle stays fixed with the phone.
        cross.strokeWidth = density
        canvas.drawLine(cx - dial * 0.25f, cy, cx + dial * 0.25f, cy, cross)
        canvas.drawLine(cx, cy - dial * 0.25f, cx, cy + dial * 0.25f, cross)

        if (heading.isNaN()) return
        canvas.save()
        canvas.rotate(-heading, cx, cy)

        number.textSize = r * 0.09f
        letter.textSize = r * 0.16f
        for (deg in 0 until 360 step 5) {
            val major = deg % 30 == 0
            val mid = deg % 10 == 0
            tick.strokeWidth = if (major) r * 0.014f else r * 0.007f
            val len = when { major -> r * 0.1f; mid -> r * 0.07f; else -> r * 0.04f }
            canvas.drawLine(cx, cy - dial + r * 0.02f, cx, cy - dial + len, tick)
            if (major) {
                val label = when (deg) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null }
                if (label != null) {
                    canvas.drawText(label, cx, cy - dial + r * 0.29f,
                        if (deg == 0) red.apply {
                            textSize = letter.textSize; textAlign = Paint.Align.CENTER
                            typeface = Typeface.DEFAULT_BOLD
                        } else letter)
                } else {
                    canvas.drawText(deg.toString(), cx, cy - dial + r * 0.21f, number)
                }
            }
            canvas.rotate(5f, cx, cy)
        }
        canvas.restore()

        // North needle on the card, so north is obvious at a glance.
        canvas.save()
        canvas.rotate(-heading, cx, cy)
        index.reset()
        index.moveTo(cx, cy - dial * 0.5f)
        index.lineTo(cx + dial * 0.06f, cy)
        index.lineTo(cx - dial * 0.06f, cy)
        index.close()
        canvas.drawPath(index, red)
        index.reset()
        index.moveTo(cx, cy + dial * 0.5f)
        index.lineTo(cx + dial * 0.06f, cy)
        index.lineTo(cx - dial * 0.06f, cy)
        index.close()
        canvas.drawPath(index, tick)
        canvas.drawCircle(cx, cy, dial * 0.025f, face)
        canvas.restore()
    }
}
