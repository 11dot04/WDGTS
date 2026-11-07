package com.wdgts.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sin

/**
 * Material 3 Expressive wavy linear progress, drawn directly (a widget can't host the Compose or View
 * indicator). Spec proportions: 4dp track thickness, 3dp wave amplitude, 40dp wavelength, a gap between the
 * wavy active part and the straight track, a round stop dot at the end of the track.
 * [u] is the widget's unit (1% of its short edge, about 1.6dp). Returns the x where the active part ends.
 */
fun Canvas.wavyProgress(
    x: Float, cy: Float, width: Float, frac: Float, u: Float, active: Int, track: Int, phase: Float = 0f,
): Float {
    val th = 2.4f * u
    val amp = 1.7f * u
    val wl = 22f * u
    val gap = 2.2f * u
    val f = frac.coerceIn(0f, 1f)
    val end = x + width * f

    val trackP = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = th; it.strokeCap = Paint.Cap.ROUND; it.color = track
    }
    val activeP = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = th; it.strokeCap = Paint.Cap.ROUND
        it.strokeJoin = Paint.Join.ROUND; it.color = active
    }
    val ts = end + gap + th / 2
    val te = x + width - th / 2
    if (te > ts) drawLine(ts, cy, te, cy, trackP)
    drawCircle(x + width - th / 2, cy, th * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = active })   // stop dot

    val start = x + th / 2
    val stop = maxOf(start, end - th / 2)
    if (stop - start < 1.5f) {
        drawCircle(start, cy, th / 2, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = active })
    } else {
        val k = (2.0 * Math.PI / wl).toFloat()
        val path = Path()
        var px = start
        var first = true
        while (px < stop) {
            val ramp = ((px - start) / (wl * 0.5f)).coerceIn(0f, 1f)     // the wave eases in from flat
            val py = cy + amp * ramp * sin(k * (px - x) + phase)
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            px += 1f
        }
        path.lineTo(stop, cy + amp * sin(k * (stop - x) + phase))
        drawPath(path, activeP)
    }
    return end
}
