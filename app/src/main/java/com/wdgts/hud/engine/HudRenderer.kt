package com.wdgts.hud.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

class HudColors(val ink: Int)

/**
 * Lets the host app draw icons the engine has no SVG for (app icons, Smartspacer icons). Draw inside a [size]
 * square, left edge at [x], centered on [cy]. With [knock] the icon must be cut out of what is already drawn
 * (use DST_OUT) instead of being painted in [ink].
 */
fun interface IconPainter {
    fun paint(key: Any, c: Canvas, x: Float, cy: Float, size: Float, ink: Int, knock: Boolean)
}

/** A finished layout: where every token sits, in design units. Used for drawing and for the tap spans. */
class Laid(val unit: Float, val size: HudSizing.Size, val lines: List<List<Placed>>)

/**
 * Draws the Nothing-style HUD onto a Canvas: line 0 is the header, the rest are packed blocks, anchored to the top.
 * [typeface] should be Geist Mono. [bodyWeight] and [pillWeight] set the 'wght' axis of a variable font
 * (body text lighter, text inside pills bold, like the reference).
 */
class HudRenderer(
    typeface: Typeface,
    private val glyphs: GlyphRegistry,
    bodyWeight: Int? = null,
    pillWeight: Int? = null,
) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val outlineKnock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** digit height / em and advance / em, measured from the font instead of assumed. */
    private val capRatio: Float
    private val advRatio: Float
    /** advance of a double-width character / em, measured through the system's fallback font (Geist Mono has no CJK). */
    private val wideRatio: Float

    init {
        for (p in listOf(text, pillText, knock)) p.typeface = typeface
        if (bodyWeight != null) text.fontVariationSettings = "'wght' $bodyWeight"
        if (pillWeight != null) {
            pillText.fontVariationSettings = "'wght' $pillWeight"
            knock.fontVariationSettings = "'wght' $pillWeight"
        }
        text.textSize = 100f
        val r = Rect()
        text.getTextBounds("0", 0, 1, r)
        capRatio = r.height() / 100f
        advRatio = text.measureText("0") / 100f
        text.textSize = 100f * Hud.WIDE_SCALE
        wideRatio = (text.measureText("あ") / 100f).takeIf { it > advRatio } ?: (advRatio * 2f)
    }

    val metrics = Metrics(
        bodyAdv = Hud.BODY_CAP * advRatio / capRatio,
        pillAdv = Hud.PILL_CAP * advRatio / capRatio,
        bodyWide = Hud.BODY_CAP * wideRatio / capRatio,
        pillWide = Hud.PILL_CAP * wideRatio / capRatio,
    )

    private val flow = FlowLayout(metrics, glyphs::sizeUnits)
    private val packer = LinePacker(flow)

    /** Lays the content out for a widget of this pixel size, without drawing. */
    fun lay(widthPx: Int, heightPx: Int, content: HudContent): Laid {
        val size = HudSizing.of(widthPx, heightPx)
        val maxW = widthPx / size.unit - 2 * Hud.PAD_H
        val lines = ArrayList<List<Placed>>(size.lines)
        val withExtra = content.header + content.headerExtra
        val headerLine = if (content.headerExtra.isNotEmpty()) flow.layout(withExtra, maxW, 2).takeIf { it.size == 1 } else null
        lines += (headerLine ?: flow.layout(content.header, maxW, 1)).firstOrNull() ?: emptyList()
        lines += packer.pack(content.blocks, size.bodyLines, maxW)
        return Laid(size.unit, size, lines)
    }

    fun draw(c: Canvas, widthPx: Int, heightPx: Int, content: HudContent, colors: HudColors, icons: IconPainter? = null) {
        val laid = lay(widthPx, heightPx, content)
        val unit = laid.unit
        val pitch = Hud.LINE_PITCH * unit
        val top0 = Hud.PAD_V * unit   // anchored to the top
        laid.lines.forEachIndexed { i, line ->
            val cy = top0 + i * pitch + pitch / 2f
            for (p in line) draw(c, p.token, (Hud.PAD_H + p.x) * unit, cy, p.w * unit, unit, colors.ink, icons)
        }
    }

    private fun size(capUnits: Float, u: Float) = capUnits * u / capRatio

    /**
     * Draws [s] at [baseSize]; runs of double-width characters (CJK, kana, emoji) are drawn at [Hud.WIDE_SCALE]
     * of it so the fallback font's taller glyphs sit with the Latin caps. Returns the width drawn.
     */
    private fun drawRuns(c: Canvas, s: String, x: Float, y: Float, p: Paint, baseSize: Float): Float {
        var cx = x
        var i = 0
        while (i < s.length) {
            val start = i
            val wide = Metrics.isWide(s.codePointAt(i))
            while (i < s.length && Metrics.isWide(s.codePointAt(i)) == wide) i += Character.charCount(s.codePointAt(i))
            val run = s.substring(start, i)
            p.textSize = if (wide) baseSize * Hud.WIDE_SCALE else baseSize
            c.drawText(run, cx, y, p)
            cx += p.measureText(run)
        }
        return cx - x
    }

    private fun runsWidth(s: String, p: Paint, baseSize: Float): Float {
        var w = 0f
        var i = 0
        while (i < s.length) {
            val start = i
            val wide = Metrics.isWide(s.codePointAt(i))
            while (i < s.length && Metrics.isWide(s.codePointAt(i)) == wide) i += Character.charCount(s.codePointAt(i))
            p.textSize = if (wide) baseSize * Hud.WIDE_SCALE else baseSize
            w += p.measureText(s.substring(start, i))
        }
        return w
    }

    private fun draw(c: Canvas, t: Token, x: Float, cy: Float, w: Float, u: Float, ink: Int, icons: IconPainter?) {
        val ph = Hud.PILL_H * u
        when (t) {
            is Text -> {
                text.color = ink
                drawRuns(c, t.text, x, cy + Hud.BODY_CAP * u / 2f, text, size(Hud.BODY_CAP, u))
            }
            is NumeralPill -> {
                val shape = Path().apply {
                    addRoundRect(RectF(x, cy - ph / 2f, x + w, cy + ph / 2f), ph / 2f, ph / 2f, Path.Direction.CW)
                }
                knockout(c, shape, t.text, x, cy, w, ph, u, ink, centered = true)
            }
            is Bubble -> knockout(c, bubble(x, cy - ph / 2f, w, u), t.word, x, cy, w, ph, u, ink, centered = false)
            is MonthPill -> outline(c, t.text, x, cy, w, u, ink)
            is OutlinePill -> outline(c, t.text, x, cy, w, u, ink)
            is CountRing -> outline(c, t.text, x, cy, w, u, ink)
            is IconPill -> {
                val shape = Path().apply {
                    addRoundRect(RectF(x, cy - ph / 2f, x + w, cy + ph / 2f), ph / 2f, ph / 2f, Path.Direction.CW)
                }
                val layer = c.saveLayer(x - 2f, cy - ph, x + w + 2f, cy + ph, null)
                fill.color = ink
                c.drawPath(shape, fill)
                val s = Hud.ICONPILL_ICON * u
                icons?.paint(t.icon, c, x + (w - s) / 2f, cy, s, ink, true)
                c.restoreToCount(layer)
            }
            is Badge -> badge(c, t, x, cy, w, u, ink)
            is GlyphToken -> {
                val g = glyphs.glyph(t.glyph) ?: return
                fill.color = ink
                stroke.color = ink
                g.draw(c, x, cy - g.height * u / 2f, u, fill, stroke)
            }
            is Icon -> icons?.paint(t.key, c, x, cy, Hud.ICON_W * u, ink, false)
            is Chip -> {
                outlineShape(c, x, cy, w, u, ink)
                icons?.paint(t.icon, c, x + Hud.CHIP_PAD_L * u, cy, Hud.CHIP_ICON * u, ink, false)
                pillText.textSize = size(Hud.PILL_CAP, u)
                pillText.color = ink
                c.drawText(t.text, x + (Hud.CHIP_PAD_L + Hud.CHIP_ICON + Hud.CHIP_GAP) * u, cy + Hud.PILL_CAP * u / 2f, pillText)
            }
            is DotRow -> {
                val d = Hud.DOT * u
                stroke.strokeWidth = Hud.STROKE * u
                stroke.color = ink
                fill.color = ink
                for (n in 0 until t.total) {
                    val cx = x + n * (Hud.DOT + Hud.DOT_GAP) * u + d / 2f
                    if (n < t.done) c.drawCircle(cx, cy, d / 2f, fill) else c.drawCircle(cx, cy, (d - Hud.STROKE * u) / 2f, stroke)
                }
            }
            is Timer -> timer(c, t, x, cy, w, u, ink)
            is ArtStrip -> artStrip(c, t, x, cy, w, u)
            is ProgressMark -> progress(c, t, x, cy, u, ink)
            is MoonPhase -> MoonGlyph.draw(c, x + w / 2f, cy, w / 2f, t.elongation.toDouble(), ink, Hud.STROKE * u)
            is Cursor -> {
                fill.color = ink
                val base = cy + Hud.BODY_CAP * u / 2f
                c.drawRoundRect(RectF(x, base - Hud.CURSOR_H * u, x + w, base), 0.4f * u, 0.4f * u, fill)
            }
            is Space -> Unit
        }
    }

    /**
     * ⌊ with the number above a long underline, measured from the reference: a stem about twice as thick as the
     * underline, running straight down, then a generous corner that thins into the underline (a filled shape, not a
     * stroked line), ending in a round cap.
     */
    private fun timer(c: Canvas, t: Timer, x: Float, cy: Float, w: Float, u: Float, ink: Int) {
        val ph = Hud.PILL_H * u
        val stem = Hud.TIMER_STEM_W * u
        val line = Hud.TIMER_LINE_W * u
        val bottom = cy + ph / 2f                      // lower edge of the underline
        val baseline = bottom - line / 2f - Hud.TIMER_GAP * u
        val cap = Hud.BODY_CAP * Hud.TIMER_TEXT_SCALE * u
        val top = baseline - cap
        val rr = Hud.TIMER_RADIUS * u                  // outer radius of the corner
        val cyc = bottom - rr                          // where the stem starts to turn
        val endX = x + w
        val k = 0.5523f
        val rxi = rr - stem                            // inner curve: an ellipse quarter from stem width to line width
        val ryi = rr - line
        val p = Path().apply {
            moveTo(x, top)
            lineTo(x, cyc)
            arcTo(RectF(x, cyc - rr, x + 2f * rr, cyc + rr), 180f, -90f)
            lineTo(endX - line / 2f, bottom)
            arcTo(RectF(endX - line, bottom - line, endX, bottom), 90f, -180f)
            lineTo(x + rr, bottom - line)
            cubicTo(x + rr - k * rxi, cyc + ryi, x + stem, cyc + k * ryi, x + stem, cyc)
            lineTo(x + stem, top)
            close()
        }
        fill.color = ink
        c.drawPath(p, fill)
        text.color = ink
        text.textSize = size(Hud.BODY_CAP * Hud.TIMER_TEXT_SCALE, u)
        c.drawText(t.text, x + Hud.TIMER_LEAD * u, baseline, text)
    }

    /** The cover cropped to a landscape strip as tall as a pill, centred on the subject (rounded as one piece). */
    private fun artStrip(c: Canvas, t: ArtStrip, x: Float, cy: Float, w: Float, u: Float) {
        val h = Hud.PILL_H * u
        val top = cy - h / 2f
        val dst = RectF(x, top, x + w, top + h)
        val bmp = t.art as? Bitmap ?: return
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
        val aspect = dst.width() / dst.height()
        var sw = bmp.width.toFloat()
        var sh = sw / aspect
        if (sh > bmp.height) { sh = bmp.height.toFloat(); sw = sh * aspect }
        val l = (t.focusX * bmp.width - sw / 2f).coerceIn(0f, bmp.width - sw).toInt()
        val tp = (t.focusY * bmp.height - sh / 2f).coerceIn(0f, bmp.height - sh).toInt()
        val save = c.save()
        c.clipPath(Path().apply { addRoundRect(dst, 0.9f * u, 0.9f * u, Path.Direction.CW) })
        c.drawBitmap(bmp, Rect(l, tp, l + sw.toInt(), tp + sh.toInt()), dst, bitmapPaint)
        c.restoreToCount(save)
    }

    /**
     * Four overlapping rings. Ring n is completely filled once the track passes n x 25 % and never partly. An empty
     * ring's outline is cut away wherever it crosses a filled ring, and shows everywhere else.
     */
    private fun progress(c: Canvas, t: ProgressMark, x: Float, cy: Float, u: Float, ink: Int) {
        val d = Hud.PROGRESS_D * u
        val r = d / 2f
        val s = Hud.STROKE * u
        val n = HudRules.ringsFilled(t.fraction)
        val cx = FloatArray(Hud.PROGRESS_RINGS) { x + r + it * Hud.PROGRESS_STEP * u }
        val filled = Path().apply { for (i in 0 until n) addCircle(cx[i], cy, r, Path.Direction.CW) }
        val layer = c.saveLayer(x - 2f, cy - r - 2f, x + Hud.PROGRESS_W * u + 2f, cy + r + 2f, null)
        fill.color = ink
        if (n > 0) c.drawPath(filled, fill)
        stroke.strokeWidth = s
        stroke.color = ink
        outlineKnock.strokeWidth = s
        for (i in n until Hud.PROGRESS_RINGS) {
            if (n > 0) {
                val a = c.save()
                c.clipOutPath(filled)
                c.drawCircle(cx[i], cy, r - s / 2f, stroke)
                c.restoreToCount(a)
                val b = c.save()
                c.clipPath(filled)
                c.drawCircle(cx[i], cy, r - s / 2f, outlineKnock)     // cut the outline out of the filled ring
                c.restoreToCount(b)
            } else {
                c.drawCircle(cx[i], cy, r - s / 2f, stroke)
            }
        }
        c.restoreToCount(layer)
    }

    private fun burst(cx: Float, cy: Float, r: Float, spikes: Int = 12, inner: Float = 0.8f): Path {
        val p = Path()
        for (i in 0 until spikes * 2) {
            val a = Math.PI * i / spikes - Math.PI / 2
            val rr = if (i % 2 == 0) r else r * inner
            val px = cx + (rr * cos(a)).toFloat()
            val py = cy + (rr * sin(a)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        return p
    }

    private fun clover(cx: Float, cy: Float, r: Float): Path = Path().apply {
        val lobe = r * 0.56f
        val off = r * 0.44f
        addCircle(cx - off, cy, lobe, Path.Direction.CW)
        addCircle(cx + off, cy, lobe, Path.Direction.CW)
        addCircle(cx, cy - off, lobe, Path.Direction.CW)
        addCircle(cx, cy + off, lobe, Path.Direction.CW)
    }

    /** Burst or clover with a short text knocked out; the text shrinks as it gets longer. */
    private fun badge(c: Canvas, t: Badge, x: Float, cy: Float, w: Float, u: Float, ink: Int) {
        val cx = x + w / 2f
        val r = (if (t.shape == BadgeShape.CIRCLE) Hud.PILL_H else Hud.BADGE_D) * u / 2f
        val layer = c.saveLayer(x - 4f, cy - r - 4f, x + w + 4f, cy + r + 4f, null)
        fill.color = ink
        when (t.shape) {
            BadgeShape.BURST -> {
                fill.pathEffect = CornerPathEffect(r * 0.14f)
                val path = burst(cx, cy, r * 1.07f, if (w > Hud.BADGE_D * u + 1f) 14 else 12, 0.82f)
                if (w > Hud.BADGE_D * u + 1f) path.transform(android.graphics.Matrix().apply { setScale(w / (2f * r * 1.07f), 1f, cx, cy) })
                c.drawPath(path, fill)
                fill.pathEffect = null
            }
            BadgeShape.CLOVER -> c.drawPath(clover(cx, cy, r), fill)
            BadgeShape.CIRCLE -> c.drawCircle(cx, cy, w / 2f, fill)
        }
        val k = if (t.shape == BadgeShape.CIRCLE) Hud.BADGE_CIRCLE_TEXT else when (t.text.length) { 0, 1 -> 1f; 2 -> 0.82f; 3 -> 0.62f; else -> 0.5f }
        knock.textSize = size(Hud.PILL_CAP, u) * k
        val capPx = Hud.PILL_CAP * u * k
        c.drawText(t.text, cx - knock.measureText(t.text) / 2f, cy + capPx / 2f, knock)
        c.restoreToCount(layer)
    }

    private fun outlineShape(c: Canvas, x: Float, cy: Float, w: Float, u: Float, ink: Int) {
        val ph = Hud.PILL_H * u
        val s = Hud.STROKE * u
        stroke.strokeWidth = s
        stroke.color = ink
        val r = RectF(x + s / 2f, cy - ph / 2f + s / 2f, x + w - s / 2f, cy + ph / 2f - s / 2f)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, stroke)
    }

    private fun outline(c: Canvas, label: String, x: Float, cy: Float, w: Float, u: Float, ink: Int) {
        outlineShape(c, x, cy, w, u, ink)
        pillText.color = ink
        val base = size(Hud.PILL_CAP, u)
        drawRuns(c, label, x + (w - runsWidth(label, pillText, base)) / 2f, cy + Hud.PILL_CAP * u / 2f, pillText, base)
    }

    /** Fills [shape] with ink and punches [label] out of it so whatever is behind shows through. */
    private fun knockout(
        c: Canvas, shape: Path, label: String,
        x: Float, cy: Float, w: Float, ph: Float, u: Float, ink: Int, centered: Boolean,
    ) {
        val layer = c.saveLayer(x - 2f, cy - ph, x + w + 2f, cy + ph, null)
        fill.color = ink
        c.drawPath(shape, fill)
        knock.textSize = size(Hud.PILL_CAP, u)
        val tx = if (centered) x + (w - knock.measureText(label)) / 2f else x + Hud.BUBBLE_PAD_L * u
        c.drawText(label, tx, cy + Hud.PILL_CAP * u / 2f, knock)
        c.restoreToCount(layer)
    }

    /** Speech-bubble pill, geometry copied from the Specs sprite (tail at bottom-left). */
    private fun bubble(x: Float, top: Float, w: Float, u: Float): Path {
        val ww = w / u
        fun px(v: Float) = x + v * u
        fun py(v: Float) = top + v * u
        val r = 5.1836f
        val k = 2.863f
        val h = Hud.PILL_H
        return Path().apply {
            moveTo(px(r), py(0f))
            lineTo(px(ww - r), py(0f))
            cubicTo(px(ww - r + k), py(0f), px(ww), py(r - k), px(ww), py(r))
            cubicTo(px(ww), py(r + k), px(ww - r + k), py(h), px(ww - r), py(h))
            lineTo(px(r), py(h))
            cubicTo(px(4.3529f), py(h), px(3.568f), py(10.1716f), px(2.872f), py(9.8242f))
            lineTo(px(0.668f), py(10.0244f))
            cubicTo(px(0.2635f), py(10.0612f), px(-0.0243f), py(9.6396f), px(0.1572f), py(9.2764f))
            lineTo(px(0.8105f), py(7.9678f))
            cubicTo(px(0.2976f), py(7.1636f), px(0f), py(6.2082f), px(0f), py(r))
            cubicTo(px(0f), py(r - k), px(r - k), py(0f), px(r), py(0f))
            close()
        }
    }
}
