package com.wdgts.hud.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moon phase glyph, shared by the HUD and the Tithi widget. Same vocabulary as the HUD weather glyphs: a
 * slightly bumpy, cookie-like disc, a thin ring for the dark side, the lit part solid with craters knocked out of it,
 * and a playful tilt. [elongation] is Moon - Sun in degrees (0 new, 90 first quarter, 180 full, 270 last quarter).
 */
object MoonGlyph {
    private const val LOBES = 8
    private const val BUMP = 0.03f
    private const val TILT = -14f

    /** Craters as (dx, dy, radius), in units of the disc's radius. */
    private val CRATERS = arrayOf(floatArrayOf(-0.30f, -0.34f, 0.17f), floatArrayOf(0.32f, 0.04f, 0.12f), floatArrayOf(-0.06f, 0.42f, 0.10f))

    private fun silhouette(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        val n = 72
        for (i in 0..n) {
            val a = (2.0 * Math.PI * i / n).toFloat()
            val rr = r * (1f + BUMP * cos(LOBES * a))
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    /** The lit region: the half facing the sun, minus (crescent) or plus (gibbous) the terminator ellipse, cut to the disc. */
    private fun lit(cx: Float, cy: Float, r: Float, elongation: Double, sil: Path): Path {
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
        val out = Path()
        out.op(shape, sil, Path.Op.INTERSECT)
        return out
    }

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, elongation: Double, ink: Int, stroke: Float) {
        val layer = c.saveLayer(cx - r * 1.3f, cy - r * 1.3f, cx + r * 1.3f, cy + r * 1.3f, null)
        c.rotate(TILT, cx, cy)
        val rr = r - stroke / 2f
        val sil = silhouette(cx, cy, rr)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val litPath = lit(cx, cy, rr, elongation, sil)
        c.drawPath(sil, ring)
        c.drawPath(litPath, fill)
        val save = c.save()
        c.clipPath(litPath)
        for (k in CRATERS) c.drawCircle(cx + k[0] * rr, cy + k[1] * rr, k[2] * rr, clear)
        c.restoreToCount(save)
        c.restoreToCount(layer)
    }
}
