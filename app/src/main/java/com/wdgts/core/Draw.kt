package com.wdgts.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

object Fonts {
    private var cache: Typeface? = null

    /** Variation settings applied whenever text is drawn with the bundled variable font. */
    @Volatile var fvar: String? = null
    val loaded: Typeface? get() = cache
    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

    /** Google Sans Flex axes: wght 100-900, wdth 75-125, opsz 8-144, GRAD -200-150, ROND 0-100, slnt -10-0. */
    fun axes(wght: Int = 450, wdth: Int = 100, rond: Int = 0, opsz: Int = 14, grad: Int = 0) =
        "'wght' $wght, 'wdth' $wdth, 'ROND' $rond, 'opsz' $opsz, 'GRAD' $grad"

    /**
     * Drop Google Sans Flex (OFL) at app/src/main/assets/fonts/display.ttf.
     * The CI workflow fetches it automatically; without it we fall back to the system sans.
     */
    fun display(c: Context): Typeface {
        cache?.let { return it }
        val flex = runCatching { Typeface.createFromAsset(c.assets, "fonts/display.ttf") }.getOrNull()
        if (flex != null) fvar = axes()
        val tf = flex ?: Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 450, false)
        cache = tf
        return tf
    }
}

fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

fun textPaint(
    size: Float, color: Int, tf: Typeface,
    align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f, fvar: String? = null,
) = Paint(Paint.ANTI_ALIAS_FLAG).also {
    it.textSize = size; it.color = color; it.typeface = tf
    it.textAlign = align; it.letterSpacing = tracking
    val v = fvar ?: if (tf === Fonts.loaded) Fonts.fvar else null
    if (v != null) it.fontVariationSettings = v
}

private fun isJapanese(s: String) = s.any { it.code >= 0x3040 }

private fun paintFor(
    s: String, size: Float, color: Int, tf: Typeface, align: Paint.Align, tracking: Float, fvar: String?,
) = textPaint(size, color, tf, align, tracking, fvar).also {
    if (isJapanese(s)) it.textLocale = Locale.JAPAN   // pick Japanese glyph forms, not Chinese
}

/** Draws text and returns its width. */
fun Canvas.label(
    s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface,
    align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f, fvar: String? = null,
): Float {
    val p = paintFor(s, size, color, tf, align, tracking, fvar)
    drawText(s, x, y, p)
    return p.measureText(s)
}

fun measureText(s: String, size: Float, tf: Typeface, tracking: Float = 0f, fvar: String? = null): Float =
    paintFor(s, size, 0, tf, Paint.Align.LEFT, tracking, fvar).measureText(s)

/** Baseline that vertically centres text of [size] on [cy]. */
fun baselineFor(cy: Float, size: Float, tf: Typeface): Float {
    val fm = textPaint(size, 0, tf).fontMetrics
    return cy - (fm.ascent + fm.descent) / 2f
}

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

fun Canvas.dot(cx: Float, cy: Float, r: Float, color: Int) {
    drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color })
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
        path.arcTo(limb, -90f, 180f)
        path.arcTo(term, 90f, if (cosE > 0) -180f else 180f)
    } else {
        path.arcTo(limb, -90f, -180f)
        path.arcTo(term, 90f, if (cosE > 0) 180f else -180f)
    }
    path.close()
    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = lit })
}
