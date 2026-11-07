package com.wdgts.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Micrograph toolkit in the vein of the Figma set: everything sits on an 11-unit-tall grid
 * ([g] = one unit), hairline strokes, stadium pills with the text knocked out, mono type.
 * Every function draws at a given x / centre-y and returns its advance, so rows can be laid out inline.
 */
class MicroStyle(val g: Float, val ink: Int, val mono: Typeface) {
    val stroke: Float get() = 0.78f * g
    val gap: Float get() = 2.4f * g
}

enum class Glyph { PHONE, ALARM, TORCH, NOTE, WALLET, SUN_UP, SUN_DOWN, EYE, CAL, MOON, BALL, FLOWER, BELL }

private fun fillP(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = c }
private fun strokeP(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).also {
    it.style = Paint.Style.STROKE; it.color = c; it.strokeWidth = w
    it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
}

/** Mono text with its caps vertically centred on [cy]. Returns the advance. */
fun Canvas.mText(s: String, x: Float, cy: Float, st: MicroStyle, size: Float = 6.4f * st.g, color: Int = st.ink): Float =
    label(s, x, cy + 0.36f * size, size, color, st.mono)

fun mWidth(s: String, st: MicroStyle, size: Float = 6.4f * st.g): Float = measureText(s, size, st.mono)

/** Text shortened with an ellipsis, at the last whole word (or character, for a single word) that fits. */
fun ellipsize(s: String, maxW: Float, size: Float, tf: Typeface): String {
    if (measureText(s, size, tf) <= maxW) return s
    val words = s.split(" ")
    var cur = words.first()
    for (i in 1 until words.size) {
        val next = cur + " " + words[i]
        if (measureText("$next…", size, tf) > maxW) break
        cur = next
    }
    if (measureText("$cur…", size, tf) <= maxW && words.size > 1) return "$cur…"
    var t = s
    while (t.length > 1 && measureText("$t…", size, tf) > maxW) t = t.dropLast(1)
    return "$t…"
}

/** Stadium pill: filled (text knocked out of it) or outlined (text inside). */
fun Canvas.mPill(s: String, x: Float, cy: Float, st: MicroStyle, filled: Boolean, color: Int = st.ink): Float {
    val h = 11f * st.g
    val ts = 6.2f * st.g
    val tw = measureText(s, ts, st.mono)
    val w = maxOf(h, tw + 6.8f * st.g)
    val r = RectF(x, cy - h / 2, x + w, cy + h / 2)
    if (filled) {
        val layer = saveLayer(r, null)
        drawRoundRect(r, h / 2, h / 2, fillP(color))
        val clear = textPaint(ts, 0, st.mono).also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        drawText(s, x + (w - tw) / 2, cy + 0.36f * ts, clear)
        restoreToCount(layer)
    } else {
        val sw = st.stroke
        drawRoundRect(RectF(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2), h / 2, h / 2, strokeP(color, sw))
        label(s, x + (w - tw) / 2, cy + 0.36f * ts, ts, color, st.mono)
    }
    return w
}

/** Speech-bubble pill (the weather word): a filled pill with a small tail at the lower left. */
fun Canvas.mBubble(s: String, x: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    val w = mPill(s, x, cy, st, filled = true, color = color)
    val h = 11f * st.g
    val t = Path()
    t.moveTo(x + h * 0.55f, cy + h / 2 - st.g * 0.5f)
    t.lineTo(x + h * 0.12f, cy + h / 2 + st.g * 2.2f)
    t.lineTo(x + h * 1.05f, cy + h / 2 - st.g * 0.5f)
    t.close()
    drawPath(t, fillP(color))
    return w
}

/** The little "go" button: an outlined stadium with a right arrow (like Followup.svg). */
fun Canvas.mArrowPill(x: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    val h = 11f * st.g
    val w = 17f * st.g
    val sw = st.stroke
    drawRoundRect(RectF(x + sw / 2, cy - h / 2 + sw / 2, x + w - sw / 2, cy + h / 2 - sw / 2), h / 2, h / 2, strokeP(color, sw))
    val p = Path()
    p.moveTo(x + 4.6f * st.g, cy); p.lineTo(x + w - 4.6f * st.g, cy)
    p.moveTo(x + w - 7.4f * st.g, cy - 2.4f * st.g); p.lineTo(x + w - 4.6f * st.g, cy); p.lineTo(x + w - 7.4f * st.g, cy + 2.4f * st.g)
    drawPath(p, strokeP(color, sw))
    return w
}

/** Right-aligned "└ 7MIN" bracket timer. Returns its width. */
fun Canvas.mBracket(s: String, rightX: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    val ts = 6.2f * st.g
    val tw = measureText(s, ts, st.mono)
    val x = rightX - tw - 4.6f * st.g
    val p = Path()
    p.moveTo(x, cy - 3.8f * st.g); p.lineTo(x, cy + 3.4f * st.g); p.lineTo(x + 2.8f * st.g, cy + 3.4f * st.g)
    drawPath(p, strokeP(color, st.stroke))
    label(s, x + 4.6f * st.g, cy + 0.36f * ts, ts, color, st.mono)
    return tw + 4.6f * st.g
}

/** Battery with fill level and the percentage after it. */
fun Canvas.mBattery(pct: Int, charging: Boolean, x: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    val w = 15f * st.g
    val h = 8f * st.g
    val sw = st.stroke
    val body = RectF(x + sw / 2, cy - h / 2 + sw / 2, x + w - sw / 2, cy + h / 2 - sw / 2)
    drawRoundRect(body, 1.8f * st.g, 1.8f * st.g, strokeP(color, sw))
    drawRoundRect(RectF(x + w + 0.2f * st.g, cy - 1.8f * st.g, x + w + 1.4f * st.g, cy + 1.8f * st.g), 0.6f * st.g, 0.6f * st.g, fillP(color))
    val inner = RectF(body.left + 1.6f * st.g, body.top + 1.6f * st.g, body.right - 1.6f * st.g, body.bottom - 1.6f * st.g)
    inner.right = inner.left + inner.width() * (pct.coerceIn(0, 100) / 100f)
    if (inner.width() > 0.5f) drawRoundRect(inner, 0.8f * st.g, 0.8f * st.g, fillP(color))
    var adv = w + 2.6f * st.g
    if (charging) {
        val b = Path()
        b.moveTo(x + w * 0.56f, cy - 3f * st.g); b.lineTo(x + w * 0.38f, cy + 0.4f * st.g); b.lineTo(x + w * 0.52f, cy + 0.4f * st.g)
        b.lineTo(x + w * 0.44f, cy + 3f * st.g); b.lineTo(x + w * 0.64f, cy - 0.6f * st.g); b.lineTo(x + w * 0.5f, cy - 0.6f * st.g); b.close()
        drawPath(b, fillP(0xFF000000.toInt()))
    }
    adv += mText("$pct", x + adv, cy, st, color = color)
    return adv
}

/** ° plus a circled C, like the clockface's "16°(C)". */
fun Canvas.mTemp(t: Int, x: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    var a = mText("$t°", x, cy, st, 7.4f * st.g, color)
    val r = 4.2f * st.g
    drawCircle(x + a + 1.6f * st.g + r, cy, r - st.stroke / 2, strokeP(color, st.stroke))
    mText("C", x + a + 1.6f * st.g + r - mWidth("C", st, 5.2f * st.g) / 2, cy, st, 5.2f * st.g, color)
    a += 1.6f * st.g + 2 * r
    return a
}

/** 9-unit glyphs on the same hairline grid. Returns the advance (11 units). */
fun Canvas.mGlyph(gl: Glyph, x: Float, cy: Float, st: MicroStyle, color: Int = st.ink): Float {
    val g = st.g
    val cx = x + 4.5f * g
    val sw = st.stroke
    val line = strokeP(color, sw)
    val fill = fillP(color)
    when (gl) {
        Glyph.PHONE -> {
            drawArc(RectF(cx - 4.2f * g, cy - 4.2f * g, cx + 4.2f * g, cy + 4.2f * g), 112f, 136f, false, strokeP(color, 1.5f * g))
            drawCircle(cx - 2.6f * g, cy + 3.2f * g, 1.3f * g, fill); drawCircle(cx + 2.6f * g, cy + 3.2f * g, 1.3f * g, fill)
        }
        Glyph.ALARM -> {
            drawCircle(cx, cy + 0.4f * g, 3.4f * g, line)
            drawLine(cx, cy + 0.4f * g, cx, cy - 1.8f * g, line); drawLine(cx, cy + 0.4f * g, cx + 1.6f * g, cy + 1.4f * g, line)
            drawCircle(cx - 3.4f * g, cy - 3f * g, 1.1f * g, fill); drawCircle(cx + 3.4f * g, cy - 3f * g, 1.1f * g, fill)
        }
        Glyph.TORCH -> {
            val head = Path()
            head.moveTo(cx - 2.8f * g, cy - 4.2f * g); head.lineTo(cx + 2.8f * g, cy - 4.2f * g)
            head.lineTo(cx + 1.7f * g, cy - 1.2f * g); head.lineTo(cx - 1.7f * g, cy - 1.2f * g); head.close()
            drawPath(head, line)
            drawRoundRect(RectF(cx - 1.7f * g, cy - 1.2f * g, cx + 1.7f * g, cy + 4.2f * g), 0.8f * g, 0.8f * g, line)
            drawCircle(cx, cy + 0.6f * g, 0.7f * g, fill)
        }
        Glyph.NOTE -> {
            drawCircle(cx - 1.4f * g, cy + 2.8f * g, 1.9f * g, fill)
            val p = Path(); p.moveTo(cx + 0.5f * g, cy + 2.8f * g); p.lineTo(cx + 0.5f * g, cy - 3.8f * g); p.lineTo(cx + 3.4f * g, cy - 2f * g)
            drawPath(p, line)
        }
        Glyph.WALLET -> {
            drawRoundRect(RectF(cx - 4.2f * g, cy - 2.8f * g, cx + 4.2f * g, cy + 3.2f * g), 1.4f * g, 1.4f * g, line)
            drawLine(cx - 4.2f * g, cy - 0.8f * g, cx + 4.2f * g, cy - 0.8f * g, line)
            drawCircle(cx + 2.2f * g, cy + 1.4f * g, 0.8f * g, fill)
        }
        Glyph.SUN_UP, Glyph.SUN_DOWN -> {
            drawArc(RectF(cx - 2.8f * g, cy - 1.6f * g, cx + 2.8f * g, cy + 4f * g), 180f, 180f, false, line)
            drawLine(cx - 4.4f * g, cy + 1.2f * g, cx + 4.4f * g, cy + 1.2f * g, line)
            drawLine(cx, cy - 2.4f * g, cx, cy - 1.2f * g, line)
            drawLine(cx - 3.4f * g, cy - 1.2f * g, cx - 2.6f * g, cy - 0.4f * g, line); drawLine(cx + 3.4f * g, cy - 1.2f * g, cx + 2.6f * g, cy - 0.4f * g, line)
            val d = if (gl == Glyph.SUN_UP) -1f else 1f
            val a = Path(); a.moveTo(cx - 1.6f * g, cy + 3.8f * g + d * 0.9f * g); a.lineTo(cx, cy + 3.8f * g - d * 0.9f * g); a.lineTo(cx + 1.6f * g, cy + 3.8f * g + d * 0.9f * g)
            drawPath(a, line)
        }
        Glyph.EYE -> {
            drawOval(RectF(cx - 4.4f * g, cy - 2.6f * g, cx + 4.4f * g, cy + 2.6f * g), line)
            drawCircle(cx, cy, 1.4f * g, fill)
        }
        Glyph.CAL -> {
            drawRoundRect(RectF(cx - 3.8f * g, cy - 3.4f * g, cx + 3.8f * g, cy + 3.8f * g), 1.2f * g, 1.2f * g, line)
            drawLine(cx - 3.8f * g, cy - 1f * g, cx + 3.8f * g, cy - 1f * g, line)
            drawLine(cx - 1.6f * g, cy - 4.4f * g, cx - 1.6f * g, cy - 2.6f * g, line); drawLine(cx + 1.6f * g, cy - 4.4f * g, cx + 1.6f * g, cy - 2.6f * g, line)
        }
        Glyph.MOON -> {
            drawCircle(cx, cy, 3.8f * g, line)
            drawArc(RectF(cx - 3.8f * g, cy - 3.8f * g, cx + 3.8f * g, cy + 3.8f * g), 90f, 180f, true, fill)
        }
        Glyph.BALL -> {
            drawCircle(cx, cy, 4f * g, line); drawCircle(cx, cy, 1.5f * g, fill)
            for (a in 0 until 5) {
                val t = Math.toRadians(a * 72.0 - 90.0)
                drawLine(cx + (1.5f * g * Math.cos(t)).toFloat(), cy + (1.5f * g * Math.sin(t)).toFloat(),
                    cx + (3.9f * g * Math.cos(t)).toFloat(), cy + (3.9f * g * Math.sin(t)).toFloat(), line)
            }
        }
        Glyph.FLOWER -> drawShape(M3Shapes.Cookie12, cx, cy, 9f * g, color)
        Glyph.BELL -> {
            val p = Path(); p.moveTo(cx - 3.4f * g, cy + 2.2f * g); p.quadTo(cx - 3.4f * g, cy - 3.8f * g, cx, cy - 3.8f * g)
            p.quadTo(cx + 3.4f * g, cy - 3.8f * g, cx + 3.4f * g, cy + 2.2f * g); p.close()
            drawPath(p, line); drawCircle(cx, cy + 3.8f * g, 1f * g, fill)
        }
    }
    return 11f * g
}
