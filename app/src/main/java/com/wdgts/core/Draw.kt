package com.wdgts.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos

object Fonts {
    private var cache: Typeface? = null

    /** Drop a font at app/src/main/assets/fonts/display.ttf to override. */
    fun display(c: Context): Typeface = cache ?: (
        runCatching { Typeface.createFromAsset(c.assets, "fonts/display.ttf") }.getOrNull()
            ?: Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 450, false)
        ).also { cache = it }
}

fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

fun textPaint(
    size: Float, color: Int, tf: Typeface,
    align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f,
) = Paint(Paint.ANTI_ALIAS_FLAG).also {
    it.textSize = size; it.color = color; it.typeface = tf
    it.textAlign = align; it.letterSpacing = tracking
}

/** Draws text and returns its width. */
fun Canvas.label(
    s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface,
    align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f,
): Float {
    val p = textPaint(size, color, tf, align, tracking)
    drawText(s, x, y, p)
    return p.measureText(s)
}

fun measureText(s: String, size: Float, tf: Typeface, tracking: Float = 0f): Float =
    textPaint(size, 0, tf, tracking = tracking).measureText(s)

/** Largest size <= maxSize at which every line fits maxW. */
fun fitSize(lines: List<String>, maxW: Float, maxSize: Float, tf: Typeface, tracking: Float = 0f): Float {
    val w = lines.maxOf { measureText(it, maxSize, tf, tracking) }
    return if (w <= maxW) maxSize else maxSize * maxW / w
}

fun Canvas.hairline(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
    drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color; it.strokeWidth = width })
}

fun Canvas.outlinePill(r: RectF, color: Int, stroke: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = stroke
    }
    drawRoundRect(r, r.height() / 2, r.height() / 2, p)
}

/** Moon phase disc from sun-moon elongation in degrees (0 = new, 180 = full). */
fun Canvas.moon(cx: Float, cy: Float, r: Float, elongation: Double, lit: Int, dark: Int) {
    drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = dark })
    val cosE = cos(Math.toRadians(elongation)).toFloat()
    val waxing = elongation < 180.0
    val limb = RectF(cx - r, cy - r, cx + r, cy + r)
    val rx = r * abs(cosE)
    val term = RectF(cx - rx, cy - r, cx + rx, cy + r)
    val path = Path()
    if (waxing) {
        path.arcTo(limb, -90f, 180f)                       // right limb, top -> bottom
        path.arcTo(term, 90f, if (cosE > 0) -180f else 180f) // terminator back to top
    } else {
        path.arcTo(limb, -90f, -180f)                      // left limb
        path.arcTo(term, 90f, if (cosE > 0) 180f else -180f)
    }
    path.close()
    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = lit })
}
