package com.wdgts.hud.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

class HudColors(val ink: Int)

/** Lets the host app draw icons the engine has no SVG for (app icons). Draw inside a [size] square, left edge at [x], centered on [cy]. */
fun interface IconPainter {
    fun paint(key: Any, c: Canvas, x: Float, cy: Float, size: Float, ink: Int)
}

/**
 * Draws the 4-line Nothing-style HUD onto a Canvas: line 0 is the header, lines 1..3 are packed blocks.
 * [typeface] should be Geist Mono; [weight] sets the 'wght' axis if the font is variable (null = default).
 */
class HudRenderer(typeface: Typeface, private val glyphs: GlyphRegistry, weight: Int? = null) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** digit height / em and advance / em, measured from the font instead of assumed. */
    private val capRatio: Float
    private val advRatio: Float

    init {
        text.typeface = typeface
        knock.typeface = typeface
        if (weight != null) {
            text.fontVariationSettings = "'wght' $weight"
            knock.fontVariationSettings = "'wght' $weight"
        }
        text.textSize = 100f
        val r = Rect()
        text.getTextBounds("0", 0, 1, r)
        capRatio = r.height() / 100f
        advRatio = text.measureText("0") / 100f
    }

    val metrics = Metrics(
        bodyAdv = Hud.BODY_CAP * advRatio / capRatio,
        pillAdv = Hud.PILL_CAP * advRatio / capRatio,
    )

    private val flow = FlowLayout(metrics, glyphs::sizeUnits)
    private val packer = LinePacker(flow)

    fun draw(c: Canvas, widthPx: Int, heightPx: Int, content: HudContent, colors: HudColors, icons: IconPainter? = null) {
        val size = HudSizing.of(widthPx, heightPx)
        val unit = size.unit
        val maxW = widthPx / unit - 2 * Hud.PAD_H

        val lines = ArrayList<List<Placed>>(size.lines)
        val withExtra = content.header + content.headerExtra
        val headerLine = if (content.headerExtra.isNotEmpty()) flow.layout(withExtra, maxW, 2).takeIf { it.size == 1 } else null
        lines += (headerLine ?: flow.layout(content.header, maxW, 1)).firstOrNull() ?: emptyList()
        lines += packer.pack(content.blocks, size.bodyLines, maxW)

        val pitch = Hud.LINE_PITCH * unit
        val top0 = Hud.PAD_V * unit   // anchored to the top
        lines.forEachIndexed { i, line ->
            val cy = top0 + i * pitch + pitch / 2f
            for (p in line) draw(c, p.token, (Hud.PAD_H + p.x) * unit, cy, p.w * unit, unit, colors.ink, icons)
        }
    }

    private fun size(capUnits: Float, u: Float) = capUnits * u / capRatio

    private fun draw(c: Canvas, t: Token, x: Float, cy: Float, w: Float, u: Float, ink: Int, icons: IconPainter?) {
        val ph = Hud.PILL_H * u
        when (t) {
            is Text -> {
                text.textSize = size(Hud.BODY_CAP, u)
                text.color = ink
                c.drawText(t.text, x, cy + Hud.BODY_CAP * u / 2f, text)
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
            is GlyphToken -> {
                val g = glyphs.glyph(t.glyph) ?: return
                fill.color = ink
                stroke.color = ink
                g.draw(c, x, cy - g.height * u / 2f, u, fill, stroke)
            }
            is Icon -> icons?.paint(t.key, c, x, cy, Hud.ICON_W * u, ink)
            is Chip -> {
                outlineShape(c, x, cy, w, u, ink)
                icons?.paint(t.icon, c, x + Hud.CHIP_PAD_L * u, cy, Hud.CHIP_ICON * u, ink)
                text.textSize = size(Hud.PILL_CAP, u)
                text.color = ink
                c.drawText(t.text, x + (Hud.CHIP_PAD_L + Hud.CHIP_ICON + Hud.CHIP_GAP) * u, cy + Hud.PILL_CAP * u / 2f, text)
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
            is Timer -> {
                val s = Hud.STROKE * u
                stroke.strokeWidth = s
                stroke.color = ink
                val left = x + s / 2f
                val by = cy + ph / 2f - s / 2f
                val rr = 3.2f * u
                val path = Path().apply {
                    moveTo(left, cy - ph / 2f + 0.4f * u)
                    lineTo(left, by - rr)
                    cubicTo(left, by - rr * 0.45f, left + rr * 0.45f, by, left + rr, by)
                    lineTo(x + w, by)
                }
                c.drawPath(path, stroke)
                text.textSize = size(Hud.BODY_CAP, u)
                text.color = ink
                c.drawText(t.text, x + Hud.TIMER_LEAD * u, cy + Hud.BODY_CAP * u / 2f - 1.2f * u, text)
            }
            is Cursor -> {
                fill.color = ink
                val base = cy + Hud.BODY_CAP * u / 2f
                c.drawRoundRect(RectF(x, base - Hud.CURSOR_H * u, x + w, base), 0.5f * u, 0.5f * u, fill)
            }
            is Space -> Unit
        }
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
        text.textSize = size(Hud.PILL_CAP, u)
        text.color = ink
        c.drawText(label, x + (w - text.measureText(label)) / 2f, cy + Hud.PILL_CAP * u / 2f, text)
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
