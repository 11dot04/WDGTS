package com.wdgts.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Flat, high-contrast colours for the sports widgets: one paper, one ink, one loud block colour.
 * Material You (default): taken from the system palette, so a themed launcher recolours them.
 * Off: fixed ink, paper and acid green, whatever the wallpaper says.
 */
class Bold private constructor(
    val paper: Int,     // the ground
    val ink: Int,       // text, rules and outlines on paper
    val soft: Int,      // secondary text on paper
    val block: Int,     // the loud flat colour
    val onBlock: Int,   // ink on the block
) {
    companion object {
        fun of(c: Context): Bold {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (!Prefs.sportsMaterialYou(c)) return if (night) Bold(
                paper = 0xFF0C0C0C.toInt(), ink = 0xFFF3F1EA.toInt(), soft = 0xFF8E8C85.toInt(),
                block = 0xFFC6F135.toInt(), onBlock = 0xFF0C0C0C.toInt(),
            ) else Bold(
                paper = 0xFFF3F1EA.toInt(), ink = 0xFF0C0C0C.toInt(), soft = 0xFF6E6C66.toInt(),
                block = 0xFFC6F135.toInt(), onBlock = 0xFF0C0C0C.toInt(),
            )
            fun k(id: Int) = c.getColor(id)
            return if (night) Bold(
                paper = k(android.R.color.system_accent2_900), ink = k(android.R.color.system_neutral1_50),
                soft = k(android.R.color.system_accent2_400), block = k(android.R.color.system_accent1_200),
                onBlock = k(android.R.color.system_accent1_900),
            ) else Bold(
                paper = k(android.R.color.system_accent2_50), ink = k(android.R.color.system_neutral1_900),
                soft = k(android.R.color.system_accent2_600), block = k(android.R.color.system_accent1_600),
                onBlock = k(android.R.color.system_accent1_0),
            )
        }
    }
}

/** A typeface plus the way its width axis is set: [fv] gives the variation settings for a width and a size. */
class Face(val tf: Typeface, val wMin: Int, val wMax: Int, val fv: (Int, Float) -> String?)

object BoldFonts {
    private val system: Typeface by lazy { Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 900, false) }
    private fun opsz(size: Float) = (size / 2.6f).toInt().coerceIn(8, 144)

    /**
     * The heaviest, widest numerals available: Roboto Flex (wght 1000, wdth up to 151, tall figures) when its file is
     * bundled, else Google Sans Flex at wght 900 / wdth 125, else the system sans (also when "System font" is on).
     */
    fun numerals(c: Context): Face {
        if (Prefs.systemFont(c)) return Face(system, 100, 100) { _, _ -> null }
        val rf = Fonts.robotoFlex(c)
        if (rf != null) {
            return Face(rf, 60, 151) { wd, sz -> "'wght' 1000, 'wdth' $wd, 'opsz' ${opsz(sz)}, 'GRAD' 0, 'YTFI' 780" }
        }
        return Face(Fonts.display(c), 75, 125) { wd, sz -> Fonts.axes(wght = 900, wdth = wd, opsz = opsz(sz)) }
    }
}

/** Widest setting of the width axis at which [text] fits [maxW]; only when the axis runs out does the size shrink. */
fun fitFace(text: String, maxW: Float, size: Float, f: Face): Pair<Float, String?> {
    fun w(wd: Int, s: Float) = measureText(text, s, f.tf, 0f, f.fv(wd, s))
    var wd = f.wMax
    while (wd > f.wMin && w(wd, size) > maxW) wd -= 4
    val wNow = w(wd, size)
    val s = if (wNow > maxW) size * maxW / wNow else size
    return s to f.fv(wd, s)
}

/** Font size at which figures are [targetH] tall. */
fun figureSize(f: Face, targetH: Float): Float {
    fun h(s: Float): Float {
        val r = Rect()
        textPaint(s, 0, f.tf, fvar = f.fv(f.wMax, s)).getTextBounds("8", 0, 1, r)
        return r.height().toFloat()
    }
    val s0 = targetH / 0.72f
    return s0 * targetH / h(s0).coerceAtLeast(1f)
}

private fun solid(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = c }

fun Canvas.flat(l: Float, t: Float, r: Float, b: Float, color: Int) = drawRect(l, t, r, b, solid(color))

/** Width a [chip] of this text will take. */
fun chipWidth(text: String, size: Float, tf: Typeface, fv: String?, tracking: Float = 0.06f): Float =
    measureText(text, size, tf, tracking, fv) + 1.8f * size

/**
 * Stadium chip with a hard offset shadow, the neo-brutalist way: [fill] with [edge] as outline and shadow.
 * [left] is its left edge, [cy] its vertical centre; the shadow falls down and to the right.
 */
fun Canvas.chip(
    text: String, left: Float, cy: Float, size: Float, tf: Typeface, fv: String?,
    fill: Int, textColor: Int, edge: Int, tracking: Float = 0.06f, flat: Boolean = false,
): RectF {
    val padX = 0.9f * size
    val r = RectF(left, cy - 1.05f * size, left + chipWidth(text, size, tf, fv, tracking), cy + 1.05f * size)
    val rad = r.height() / 2f
    val d = 0.3f * size
    if (!flat) drawRoundRect(RectF(r.left + d, r.top + d, r.right + d, r.bottom + d), rad, rad, solid(edge))
    drawRoundRect(r, rad, rad, solid(fill))
    if (!flat) drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.color = edge; it.strokeWidth = 0.2f * size
    })
    label(text, r.left + padX, cy + 0.36f * size, size, textColor, tf, Paint.Align.LEFT, tracking, fv)
    return r
}

/** The bitmap as a one-colour stamp: every visible pixel becomes [color], alpha kept. */
fun Canvas.silhouette(bmp: Bitmap, left: Float, top: Float, size: Float, color: Int) {
    val cm = ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, Color.red(color).toFloat(),
        0f, 0f, 0f, 0f, Color.green(color).toFloat(),
        0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
        0f, 0f, 0f, 1f, 0f,
    ))
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).also { it.colorFilter = ColorMatrixColorFilter(cm) }
    val s = size / maxOf(bmp.width, bmp.height)
    val dw = bmp.width * s
    val dh = bmp.height * s
    drawBitmap(bmp, null, RectF(left + (size - dw) / 2, top + (size - dh) / 2, left + (size + dw) / 2, top + (size + dh) / 2), paint)
}

/**
 * A wedge of diagonal stripes, the poster way: stripes lean right, the wedge's left edge leans with them and
 * the right side is meant to bleed off the card. [pitch] is stripe plus gap; the stripe is half of it.
 */
fun Canvas.stripeWedge(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, pitch: Float, lean: Float = 0.62f) {
    val h = y1 - y0
    val shift = h * lean
    val wedge = android.graphics.Path().apply {
        moveTo(x0 + shift, y0); lineTo(x1, y0); lineTo(x1, y1); lineTo(x0, y1); close()
    }
    save()
    clipPath(wedge)
    val paint = solid(color)
    val thick = pitch / 2f
    var x = x0 - shift
    while (x < x1) {
        val s = android.graphics.Path().apply {
            moveTo(x, y1); lineTo(x + thick, y1); lineTo(x + thick + shift, y0); lineTo(x + shift, y0); close()
        }
        drawPath(s, paint)
        x += pitch
    }
    restore()
}
