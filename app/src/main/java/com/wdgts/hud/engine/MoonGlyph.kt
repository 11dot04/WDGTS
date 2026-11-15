package com.wdgts.hud.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos

/**
 * The moon phase glyph, shared by the HUD and the Tithi widget: a true circle of which only the lit part is drawn (the dark
 * side is simply not there: transparent, no outline, no craters). The pointed horns of a crescent are rounded: the lit shape
 * is made [ROUND] smaller and then grown back with a round-joined stroke, so its outer edge is still the full circle.
 * [elongation] is Moon - Sun in degrees (0 new, 90 first quarter, 180 full, 270 last quarter); a waxing moon is lit on the right.
 */
object MoonGlyph {
    /** Corner radius of the horns, as a fraction of the disc's radius. */
    private const val ROUND = 0.11f

    /** The lit region: the half facing the sun, minus (crescent) or plus (gibbous) the terminator ellipse, cut to the disc. */
    private fun lit(cx: Float, cy: Float, r: Float, elongation: Double): Path {
        val e = ((elongation % 360.0) + 360.0) % 360.0
        val waxing = e < 180.0
        val cosE = cos(Math.toRadians(e)).toFloat()
        val big = r * 1.3f
        val half = Path().apply {
            if (waxing) addRect(cx, cy - big, cx + big, cy + big, Path.Direction.CW)
            else addRect(cx - big, cy - big, cx, cy + big, Path.Direction.CW)
        }
        val term = Path().apply { addOval(RectF(cx - r * abs(cosE), cy - big, cx + r * abs(cosE), cy + big), Path.Direction.CW) }
        val shape = Path()
        shape.op(half, term, if (cosE >= 0f) Path.Op.DIFFERENCE else Path.Op.UNION)
        val disc = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val out = Path()
        out.op(shape, disc, Path.Op.INTERSECT)
        return out
    }

    /** [stroke], [bump] and [darkAlpha] are ignored (kept so older call sites compile). */
    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, elongation: Double, ink: Int, stroke: Float = 0f, bump: Float = 0f, darkAlpha: Int = 0) {
        val rho = r * ROUND
        val litPath = lit(cx, cy, r - rho, elongation)
        if (litPath.isEmpty) return
        val b = RectF()
        litPath.computeBounds(b, true)
        if (b.width() < rho * 0.6f) return                      // a hair-thin new moon: nothing to show
        c.drawPath(litPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL })
        c.drawPath(litPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = Paint.Style.STROKE; strokeWidth = 2f * rho; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
    }
}
