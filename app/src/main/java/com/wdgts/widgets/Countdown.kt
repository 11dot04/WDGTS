package com.wdgts.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.wdgts.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

object Countdown {
    class Span(val total: Int, val elapsed: Int) {
        val frac: Float get() = if (total <= 0) 1f else elapsed / total.toFloat()
        val left: Int get() = total - elapsed
    }

    /** Inclusive of both end dates, like the KWGT version (3 Aug - 1 Nov = 91 days). */
    fun span(c: Context): Span {
        val s = Prefs.cdStart(c)
        val e = Prefs.cdEnd(c)
        val today = LocalDate.now().toEpochDay()
        val total = (e - s + 1).toInt().coerceAtLeast(1)
        return Span(total, (today - s + 1).toInt().coerceIn(0, total))
    }

    private val ONES = arrayOf(
        "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven",
        "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen",
    )
    private val TENS = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    fun words(n: Int): String = when {
        n < 20 -> ONES[n]
        n < 100 -> TENS[n / 10] + if (n % 10 > 0) "-" + ONES[n % 10] else ""
        n < 1000 -> ONES[n / 100] + " Hundred" + if (n % 100 > 0) " " + words(n % 100) else ""
        else -> n.toString()
    }

    /** "Ninety-One Days" -> ["Ninety-", "One Days"] */
    fun titleLines(n: Int): List<String> {
        val t = words(n) + if (n == 1) " Day" else " Days"
        val i = t.indexOf('-')
        if (i >= 0) return listOf(t.substring(0, i + 1), t.substring(i + 1))
        val j = t.indexOf(' ')
        return if (j > 0) listOf(t.substring(0, j), t.substring(j + 1)) else listOf(t)
    }

    fun fmt(epochDay: Long, pattern: String): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}

/** Swiss-editorial countdown: big type, hairlines, a four-dot quarter meter, date range bleeding off the bottom. */
class CountdownBrutalWidget : ImageWidget() {
    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val sp = Countdown.span(ctx)
        drawColor(p.surface)

        hairline(0.45f * w, 0f, 0.93f * w, 0.69f * h, p.faint, 0.3f * u)
        hairline(0.04f * w, 0.83f * h, w.toFloat(), 0.19f * h, p.faint, 0.3f * u)

        // title: duration in words, two lines
        val ts = 8.4f * u
        val tv = Fonts.axes(wght = 360, wdth = 100)
        Countdown.titleLines(sp.total).forEachIndexed { i, line ->
            label(line, pad, pad + ts + i * ts * 1.08f, ts, p.onSurface, tf, fvar = tv)
        }

        // percent + days left
        val pct = "${(sp.frac * 100).roundToInt()}%"
        label(pct, w - pad, pad + 10.5f * u, 10.5f * u, p.onSurface, tf, Paint.Align.RIGHT,
            fvar = Fonts.axes(wght = 640, wdth = 100))
        label("${sp.left}D LEFT", w - pad, pad + 10.5f * u + 5.4f * u, 4.2f * u, p.muted, Fonts.MONO, Paint.Align.RIGHT, 0.04f)

        // four dots in a diamond: one fills per quarter of progress
        val filled = floor(sp.frac * 4f).toInt().coerceIn(0, 4)
        val cx = 0.65f * w
        val cy = 0.35f * h
        val d = 3.6f * u
        val pts = listOf(0f to -d, d to 0f, 0f to d, -d to 0f)
        pts.forEachIndexed { i, (dx, dy) -> dot(cx + dx, cy + dy, 1.7f * u, if (i < filled) p.onSurface else p.faint) }

        // quote
        val qs = 4.6f * u
        val lines = wrapLines(Prefs.cdQuote(ctx), 0.56f * w, qs, tf)
        lines.take(3).forEachIndexed { i, l -> label(l, 0.36f * w, 0.69f * h + i * qs * 1.25f, qs, p.onSurface, tf) }

        // date range, bleeding off the bottom edge; width axis stretches it to the full measure
        val range = Countdown.fmt(Prefs.cdStart(ctx), "d.MM.yy") + "—" + Countdown.fmt(Prefs.cdEnd(ctx), "d.MM")
        val (size, fv) = fitByAxis(range, w - 2 * pad, 17f * u, tf, wght = 420, grow = true)
        label(range, pad, h - 0.5f * u, size, p.onSurface, tf, fvar = fv)
    }
}

/** A random M3 Expressive shape (stable per countdown) that fills from the bottom as the date approaches. */
class CountdownShapeWidget : ImageWidget() {
    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        val sp = Countdown.span(ctx)
        drawColor(p.surface)

        val poly = M3Shapes.pick(Prefs.cdStart(ctx) * 31 + Prefs.cdEnd(ctx))
        val size = 0.80f * minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f

        val num = sp.left.toString()
        val ns = fitSize(listOf(num), size * 0.62f, 30f * u, tf, fvar = Fonts.axes(wght = 620, wdth = 100))
        val nv = Fonts.axes(wght = 620, wdth = 100)
        val ny = baselineFor(cy - 2f * u, ns, tf)
        val unit = if (sp.left == 1) "DAY" else "DAYS"

        drawShape(poly, cx, cy, size, alpha(p.faint, 110))                  // track
        label(num, cx, ny, ns, p.onSurface, tf, Paint.Align.CENTER, fvar = nv)
        label(unit, cx, ny + 6.5f * u, 4.6f * u, p.muted, Fonts.MONO, Paint.Align.CENTER, 0.08f)

        // fill level rises from the bottom of the shape; text flips colour inside the fill
        val fillTop = cy + size / 2f - size * sp.frac
        save()
        clipRect(0f, fillTop, w.toFloat(), h.toFloat())
        drawShape(poly, cx, cy, size, p.accent)
        label(num, cx, ny, ns, p.onAccent, tf, Paint.Align.CENTER, fvar = nv)
        label(unit, cx, ny + 6.5f * u, 4.6f * u, p.onAccent, Fonts.MONO, Paint.Align.CENTER, 0.08f)
        restore()

        label("${(sp.frac * 100).roundToInt()}%", 7f * u, 7f * u + 5f * u, 5f * u, p.muted, Fonts.MONO, tracking = 0.04f)
        label(
            Countdown.fmt(Prefs.cdEnd(ctx), "d MMM").uppercase(), w - 7f * u, h - 6f * u, 4.6f * u,
            p.muted, Fonts.MONO, Paint.Align.RIGHT, 0.06f,
        )
    }
}
