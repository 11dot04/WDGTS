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
    private var monoCache: Typeface? = null

    /** Drop a mono face (Google Sans Code / Space Mono / JetBrains Mono...) at assets/fonts/mono.ttf; else system monospace. */
    fun mono(c: Context): Typeface = monoCache
        ?: (runCatching { Typeface.createFromAsset(c.assets, "fonts/mono.ttf") }.getOrNull() ?: MONO).also { monoCache = it }

    private var geistCache: Typeface? = null

    /** Geist Mono as the HUD finds it (any file in assets/fonts with "geist" and "mono" in its name), else [mono]. */
    fun geist(c: Context): Typeface = geistCache ?: run {
        val files = runCatching { c.assets.list("fonts") }.getOrNull().orEmpty()
        val f = files.firstOrNull { it.contains("geist", ignoreCase = true) && it.contains("mono", ignoreCase = true) }
        (f?.let { runCatching { Typeface.createFromAsset(c.assets, "fonts/$it") }.getOrNull() } ?: mono(c)).also { geistCache = it }
    }

    /** 'wght' setting for a variable Geist Mono (ignored by a static file). */
    fun monoW(w: Int) = "'wght' $w"

    private var robotoCache: Typeface? = null
    private var robotoTried = false

    /** Roboto Flex (assets/fonts/roboto_flex.ttf, fetched by CI), or null when the file is missing. */
    fun robotoFlex(c: Context): Typeface? {
        if (!robotoTried) {
            robotoTried = true
            robotoCache = runCatching { Typeface.createFromAsset(c.assets, "fonts/roboto_flex.ttf") }.getOrNull()
        }
        return robotoCache
    }

    /** Google Sans Flex axes: wght 100-900, wdth 75-125, opsz 8-144, GRAD -200-150, ROND 0-100, slnt -10-0. */
    fun axes(wght: Int = 450, wdth: Int = 100, rond: Int = 0, opsz: Int = 14, grad: Int = 0) =
        "'wght' $wght, 'wdth' $wdth, 'ROND' $rond, 'opsz' $opsz, 'GRAD' $grad"

    /**
     * Drop Google Sans Flex (OFL) at app/src/main/assets/fonts/display.ttf.
     * The CI workflow fetches it automatically; without it we fall back to the system sans.
     */
    private val system: Typeface by lazy { Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 450, false) }
    private var flexLoaded = false

    /** The widgets' face: the system sans when the setting asks for it, otherwise Google Sans Flex. */
    fun display(c: Context): Typeface = if (Prefs.systemFont(c)) { fvar = null; system } else flex(c)

    /** Google Sans Flex always (the music widgets keep it whatever the setting says). */
    fun flex(c: Context): Typeface {
        cache?.let { if (flexLoaded && fvar == null) fvar = axes(); return it }
        val flex = runCatching { Typeface.createFromAsset(c.assets, "fonts/display.ttf") }.getOrNull()
        if (flex != null) { fvar = axes(); flexLoaded = true }
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
fun fitSize(
    lines: List<String>, maxW: Float, maxSize: Float, tf: Typeface,
    tracking: Float = 0f, fvar: String? = null,
): Float {
    val w = lines.maxOf { measureText(it, maxSize, tf, tracking, fvar) }
    return if (w <= maxW) maxSize else maxSize * maxW / w
}

/** Greedy wrap; breaks after spaces and after 、 , so readings like か.わる、か.わり wrap too. */
fun wrapLines(text: String, maxW: Float, size: Float, tf: Typeface, fvar: String? = null): List<String> {
    val tokens = Regex("[^ 、,]+[,、]? ?").findAll(text).map { it.value }.toList()
    val out = ArrayList<String>()
    var cur = ""
    for (t in tokens) {
        val cand = cur + t
        if (cur.isNotEmpty() && measureText(cand.trimEnd(), size, tf, 0f, fvar) > maxW) { out += cur.trimEnd(); cur = t }
        else cur = cand
    }
    if (cur.isNotEmpty()) out += cur.trimEnd()
    return out
}

class Wrapped(val lines: List<String>, val size: Float, val fvar: String)

/**
 * Long text: first condense with the width axis, then wrap onto up to [maxLines] lines at the SAME size,
 * and only shrink as a last resort. The width axis is then opened back up as far as the lines allow.
 */
fun wrapFit(
    text: String, maxW: Float, maxLines: Int, size: Float, tf: Typeface,
    wght: Int = 420, minWdth: Int = 80, maxWdth: Int = 100,
): Wrapped {
    var s = size
    while (true) {
        fun fv(wdth: Int) = Fonts.axes(wght = wght, wdth = wdth, opsz = (s / 2.6f).toInt().coerceIn(8, 144))
        val lines = wrapLines(text, maxW, s, tf, fv(minWdth))
        if (lines.size <= maxLines || s < size * 0.55f) {
            var wd = maxWdth
            while (wd > minWdth && lines.any { measureText(it, s, tf, 0f, fv(wd)) > maxW }) wd -= 5
            return Wrapped(lines, s, fv(wd))
        }
        s *= 0.93f
    }
}

fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return android.graphics.Color.argb(
        ch(android.graphics.Color.alpha(a), android.graphics.Color.alpha(b)),
        ch(android.graphics.Color.red(a), android.graphics.Color.red(b)),
        ch(android.graphics.Color.green(a), android.graphics.Color.green(b)),
        ch(android.graphics.Color.blue(a), android.graphics.Color.blue(b)),
    )
}

private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }

fun Canvas.playIcon(cx: Float, cy: Float, r: Float, color: Int) {
    val p = Path()
    p.moveTo(cx - r * 0.6f, cy - r); p.lineTo(cx + r, cy); p.lineTo(cx - r * 0.6f, cy + r); p.close()
    drawPath(p, fillPaint(color))
}

fun Canvas.pauseIcon(cx: Float, cy: Float, r: Float, color: Int) {
    val w = r * 0.55f
    val f = fillPaint(color)
    drawRoundRect(RectF(cx - r * 0.9f, cy - r, cx - r * 0.9f + w, cy + r), w / 3, w / 3, f)
    drawRoundRect(RectF(cx + r * 0.9f - w, cy - r, cx + r * 0.9f, cy + r), w / 3, w / 3, f)
}

fun Canvas.skipIcon(cx: Float, cy: Float, r: Float, color: Int, forward: Boolean) {
    val d = if (forward) 1f else -1f
    val p = Path()
    p.moveTo(cx - d * r * 0.7f, cy - r * 0.9f); p.lineTo(cx + d * r * 0.5f, cy); p.lineTo(cx - d * r * 0.7f, cy + r * 0.9f); p.close()
    val f = fillPaint(color)
    drawPath(p, f)
    val bx = cx + d * r * 0.75f
    drawRoundRect(RectF(bx - r * 0.12f, cy - r * 0.9f, bx + r * 0.12f, cy + r * 0.9f), r * 0.1f, r * 0.1f, f)
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

/**
 * Variable-font fitting: slide the width axis (wdth) so [text] lands exactly on [targetW] at [size];
 * only if the axis range runs out do we change the size. Returns (size, variation settings).
 * Google Sans Flex: wdth 75-125. Text never shrinks to a tiny size just because it is long.
 */
fun fitByAxis(
    text: String, targetW: Float, size: Float, tf: Typeface,
    wght: Int = 450, wdthMin: Int = 75, wdthMax: Int = 125, rond: Int = 0, grow: Boolean = false,
): Pair<Float, String> {
    fun fv(wdth: Int, sz: Float) = Fonts.axes(wght = wght, wdth = wdth, rond = rond, opsz = (sz / 2.6f).toInt().coerceIn(8, 144))
    fun width(wdth: Int, sz: Float) = measureText(text, sz, tf, 0f, fv(wdth, sz))
    val wMin = width(wdthMin, size)
    val wMax = width(wdthMax, size)
    return when {
        wMax - wMin < 0.5f -> {                       // no width axis (e.g. CJK fallback): only ever shrink, unless [grow]
            val s = if (grow || targetW < wMax) size * targetW / wMax else size
            s to fv(100, s)
        }
        targetW <= wMin -> { val s = size * targetW / wMin; s to fv(wdthMin, s) }
        targetW >= wMax -> size to fv(wdthMax, size)
        else -> {
            var lo = wdthMin
            var hi = wdthMax
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (width(mid, size) < targetW) lo = mid else hi = mid
            }
            size to fv(hi, size)
        }
    }
}
