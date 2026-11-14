package com.wdgts.hud.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos

/**
 * The moon phase glyph, shared by the HUD and the Tithi widget: a true circle of which only the lit part is drawn (the
 * dark side is simply not there: transparent, no outline), with three craters knocked out of the lit part.
 * [elongation] is Moon - Sun in degrees (0 new, 90 first quarter, 180 full, 270 last quarter); a waxing moon is lit on the right.
 */
object MoonGlyph {
    /** Craters as (dx, dy, radius), in units of the disc's radius. */
    private val CRATERS = arrayOf(floatArrayOf(-0.28f, -0.30f, 0.17f), floatArrayOf(0.32f, 0.06f, 0.12f), floatArrayOf(-0.04f, 0.42f, 0.10f))

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

    /** [stroke], [bump] and [darkAlpha] are ignored (kept so older call sites compile): there is no outline and no dark side. */
    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, elongation: Double, ink: Int, stroke: Float = 0f, bump: Float = 0f, darkAlpha: Int = 0) {
        val layer = c.saveLayer(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy + r * 1.2f, null)
        val litPath = lit(cx, cy, r, elongation)
        c.drawPath(litPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL })
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val save = c.save()
        c.clipPath(litPath)
        for (k in CRATERS) c.drawCircle(cx + k[0] * r, cy + k[1] * r, k[2] * r, clear)
        c.restoreToCount(save)
        c.restoreToCount(layer)
    }
}
