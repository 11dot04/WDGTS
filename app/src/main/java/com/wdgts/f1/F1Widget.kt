package com.wdgts.f1

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.widgets.ImageWidget
import com.wdgts.widgets.Sched
import com.wdgts.widgets.async
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Race weekend, set like a page of a technical manual: hairline circuit drawing with crop marks and coordinates,
 * a light grotesk countdown with mono units, a weekend timeline, and one loud mark, the LIVE tab.
 * (Positions/timing during a session would need a live timing feed; this is schedule-driven.)
 * Colours come from [Bold] (Material You unless the setting turns it off).
 */
class F1Widget : ImageWidget(200) {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        super.onUpdate(c, m, ids)
        async { sync(c) }
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_TICK) async { sync(c) } else super.onReceive(c, i)
    }

    private fun sync(c: Context) {
        val wk = F1Repo.ensure(c)
        refreshAll(c)
        val now = System.currentTimeMillis()
        val live = wk?.sessions?.any { now in it.startMs..it.startMs + it.durMin * 60_000L } == true
        val next = wk?.sessions?.firstOrNull { it.startMs > now }
        val at = when {
            live -> now + 60_000L
            next != null && next.startMs - now < 60 * 60_000L -> now + 5 * 60_000L
            else -> now + 30 * 60_000L
        }
        Sched.arm(c, F1Widget::class.java, ACTION_TICK, at, 60)
    }

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(
            R.id.zone_main,
            PendingIntent.getActivity(
                ctx, 61, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.formula1.com/en/racing")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val b = Bold.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.geist(ctx)
        val u = minOf(w, h) / 100f
        drawColor(b.paper)
        val wk = F1Repo.cached(ctx)
        if (wk == null) {
            label("F1", w / 2f, baselineFor(h / 2f, 22f * u, tf), 22f * u, b.soft, tf, Paint.Align.CENTER,
                fvar = Fonts.axes(wght = 240))
            return
        }
        val now = System.currentTimeMillis()
        val live = wk.sessions.firstOrNull { now in it.startMs..it.startMs + it.durMin * 60_000L }
        val next = wk.sessions.firstOrNull { it.startMs > now }
        val cur = live ?: next
        val pad = 7f * u
        val track = F1Repo.track(ctx, wk)
        val wide = w >= 1.5f * h
        val micro = 3.6f * u
        val small = 4.2f * u

        // geometry: every line has a fixed slot, so the stack can never run past the card
        val box: Float; val bx: Float; val by: Float
        val tx: Float; val tw: Float
        val yHeader: Float; val yGp: Float; val yBig: Float; val yLine: Float; val ySess: Float
        if (wide) {
            box = h - 2 * pad; bx = pad; by = pad
            tx = bx + box + pad * 1.6f; tw = w - tx - pad
            yHeader = 0.16f * h; yGp = 0.40f * h; yBig = 0.70f * h; yLine = 0.795f * h; ySess = 0.90f * h
        } else {
            box = minOf(w - 2 * pad, 0.35f * h); bx = pad; by = 0.15f * h
            tx = pad; tw = w - 2 * pad
            yHeader = 0.075f * h; yGp = 0.62f * h; yBig = 0.80f * h; yLine = 0.865f * h; ySess = 0.945f * h
        }
        val hair = 0.32f * u

        // header: round left; the LIVE tab (a bookmark off the top edge) or the country right
        label("ROUND ${wk.round}", tx, yHeader + 0.36f * micro, micro, b.soft, mono, tracking = 0.1f, fvar = Fonts.monoW(450))
        if (live != null) {
            val ls = micro
            val lw = measureText("LIVE", ls, mono, 0.1f, Fonts.monoW(700))
            val tabR = tx + tw
            flat(tabR - lw - 3f * u, 0f, tabR, yHeader + 2.4f * u, b.block)
            label("LIVE", tabR - 1.5f * u, yHeader + 0.36f * ls, ls, b.onBlock, mono, Paint.Align.RIGHT, 0.1f, Fonts.monoW(700))
        } else {
            label(ellipsize(wk.country.uppercase(), tw * 0.6f, micro, mono), tx + tw, yHeader + 0.36f * micro, micro, b.soft,
                mono, Paint.Align.RIGHT, 0.1f, Fonts.monoW(450))
        }
        hairline(tx, yHeader + 3.6f * u, tx + tw, yHeader + 3.6f * u, alpha(b.soft, 170), hair)

        // circuit drawing with crop marks
        drawCropMarks(bx, by, box, u, b.soft)
        if (track != null) drawTrack(track, bx, by, box, u, b)
        else label(wk.round.toString(), bx + box / 2f, baselineFor(by + box / 2f, box * 0.5f, tf), box * 0.5f, b.soft, tf,
            Paint.Align.CENTER, fvar = Fonts.axes(wght = 200, wdth = 110))

        // tall card: the room beside the drawing holds the circuit name and its coordinates
        if (!wide) {
            val cx = bx + box + pad
            val cw = w - cx - pad
            if (cw > 14f * u) {
                var y = by + 2.2f * u
                label("CIRCUIT", cx, y, micro * 0.9f, b.soft, mono, tracking = 0.12f, fvar = Fonts.monoW(450))
                y += 5.4f * u
                wrapLines(wk.circuitName.uppercase(), cw, small * 0.9f, mono).take(2).forEach {
                    label(it, cx, y, small * 0.9f, b.ink, mono, tracking = 0.02f, fvar = Fonts.monoW(500))
                    y += 5f * u
                }
                if (live != null) {
                    // a session is on: stripes take the coordinates' place, bleeding off the right edge
                    val top = y - 0.5f * u
                    val bottom = by + box
                    if (bottom - top > 6f * u) stripeWedge(cx, top, w + 2f * u, bottom, b.block, 3.6f * u)
                } else {
                    y += 3f * u
                    coordinates(wk.lat, wk.lon).forEach {
                        label(it, cx, y, micro * 0.95f, b.soft, mono, tracking = 0.04f, fvar = Fonts.monoW(450))
                        y += 4.6f * u
                    }
                }
            }
        }

        // title: the grand prix in a light grotesk, its class underneath in mono
        val gpName = wk.name.removeSuffix(" Grand Prix")
        val (gs, gv) = fitByAxis(gpName, tw, if (wide) 9.5f * u else 9f * u, tf, wght = 300, wdthMax = 100)
        label(gpName, tx, yGp, gs, b.ink, tf, fvar = gv)
        label("GRAND PRIX", tx, yGp + 5.6f * u, micro, b.soft, mono, tracking = 0.14f, fvar = Fonts.monoW(450))

        // hero: countdown as light numerals with mono units; while a session is on, its name instead
        val heroSize = if (wide) 17f * u else 13f * u
        when {
            cur == null -> heroText("Done", tx, yBig, tw, heroSize, tf, b.ink)
            live != null -> heroText(live.name, tx, yBig, tw, heroSize, tf, b.ink)
            else -> hero(parts(cur.startMs - now), tx, yBig, tw, heroSize, tf, mono, b.ink, b.soft)
        }

        // weekend timeline, then the session line
        timeline(wk, now, tx, tx + tw, yLine, u, b)
        val z = cur?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.startMs), ZoneId.systemDefault()) }
        val sess = when {
            cur == null -> "WEEKEND COMPLETE"
            live != null -> {
                val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(live.startMs + live.durMin * 60_000L), ZoneId.systemDefault())
                "ENDS ~" + DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(end)
            }
            else -> cur.name + " · " + DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()).format(z).uppercase()
        }
        label(ellipsize(sess, tw, small, mono), tx, ySess, small, b.ink, mono, tracking = 0.06f, fvar = Fonts.monoW(500))
    }

    /** "2D 04H", "3H 05M", "42M" as number/unit pairs. */
    private fun parts(ms: Long): List<Pair<String, String>> {
        val m = (ms / 60_000).coerceAtLeast(0)
        val d = m / 1440
        val h = (m % 1440) / 60
        val mm = m % 60
        return when {
            d > 0 -> listOf(d.toString() to "D", h.toString().padStart(2, '0') to "H")
            h > 0 -> listOf(h.toString() to "H", mm.toString().padStart(2, '0') to "M")
            else -> listOf(mm.toString() to "M")
        }
    }

    private fun lightAxes(size: Float) = Fonts.axes(wght = 240, wdth = 100, opsz = (size / 2.6f).toInt().coerceIn(8, 144))

    /** Light numerals with small mono units on the same baseline, scaled down only if the row would overflow. */
    private fun Canvas.hero(
        parts: List<Pair<String, String>>, x: Float, y: Float, maxW: Float, size0: Float,
        tf: android.graphics.Typeface, mono: android.graphics.Typeface, ink: Int, soft: Int,
    ) {
        fun total(s: Float): Float {
            var t = 0f
            parts.forEachIndexed { i, (n, u) ->
                t += measureText(n, s, tf, 0f, lightAxes(s)) + s * 0.10f + measureText(u, s * 0.26f, mono, 0.06f)
                if (i < parts.lastIndex) t += s * 0.30f
            }
            return t
        }
        val s = size0 * minOf(1f, maxW / total(size0))
        var cx = x
        parts.forEachIndexed { i, (n, u) ->
            cx += label(n, cx, y, s, ink, tf, fvar = lightAxes(s)) + s * 0.10f
            cx += label(u, cx, y, s * 0.26f, soft, mono, tracking = 0.06f)
            if (i < parts.lastIndex) cx += s * 0.30f
        }
    }

    private fun Canvas.heroText(text: String, x: Float, y: Float, maxW: Float, size: Float, tf: android.graphics.Typeface, ink: Int) {
        val (s, v) = fitByAxis(text, maxW, size, tf, wght = 240, wdthMax = 100)
        label(text, x, y, s, ink, tf, fvar = v)
    }

    /** One dot per session on a hairline: done = soft, next = ink, later = ring, on now = the loud colour. */
    private fun Canvas.timeline(wk: Weekend, now: Long, x0: Float, x1: Float, y: Float, u: Float, b: Bold) {
        val ss = wk.sessions
        if (ss.isEmpty()) return
        val t0 = ss.first().startMs
        val t1 = ss.last().let { it.startMs + it.durMin * 60_000L }
        val span = (t1 - t0).coerceAtLeast(1L).toFloat()
        hairline(x0, y, x1, y, alpha(b.soft, 170), 0.3f * u)
        val nextIdx = ss.indexOfFirst { it.startMs > now }
        ss.forEachIndexed { i, s ->
            val x = x0 + (s.startMs - t0).toFloat() / span * (x1 - x0)
            val end = s.startMs + s.durMin * 60_000L
            when {
                now in s.startMs..end -> dot(x, y, 1.7f * u, b.block)
                end < now -> dot(x, y, 0.9f * u, b.soft)
                i == nextIdx -> dot(x, y, 1.5f * u, b.ink)
                else -> { dot(x, y, 1.2f * u, b.ink); dot(x, y, 0.65f * u, b.paper) }
            }
        }
    }

    private fun coordinates(lat: Double, lon: Double): List<String> = listOf(
        String.format(Locale.US, "%.4f° %s", abs(lat), if (lat >= 0) "N" else "S"),
        String.format(Locale.US, "%.4f° %s", abs(lon), if (lon >= 0) "E" else "W"),
    )

    /** Registration marks at the four corners of the drawing, like a printer's crop marks. */
    private fun Canvas.drawCropMarks(bx: Float, by: Float, box: Float, u: Float, color: Int) {
        val l = 2.4f * u
        val sw = 0.3f * u
        val o = 0.8f * u
        val x0 = bx - o; val y0 = by - o; val x1 = bx + box + o; val y1 = by + box + o
        hairline(x0, y0, x0 + l, y0, color, sw); hairline(x0, y0, x0, y0 + l, color, sw)
        hairline(x1, y0, x1 - l, y0, color, sw); hairline(x1, y0, x1, y0 + l, color, sw)
        hairline(x0, y1, x0 + l, y1, color, sw); hairline(x0, y1, x0, y1 - l, color, sw)
        hairline(x1, y1, x1 - l, y1, color, sw); hairline(x1, y1, x1, y1 - l, color, sw)
    }

    /** Hairline circuit; the start/finish line is a short tick across the first straight. */
    private fun Canvas.drawTrack(pts: FloatArray, bx: Float, by: Float, box: Float, u: Float, b: Bold) {
        val path = Path()
        val n = pts.size / 2
        val m = box * 0.08f
        val s = box - 2 * m
        fun px(i: Int) = bx + m + pts[2 * i] * s
        fun py(i: Int) = by + m + pts[2 * i + 1] * s
        for (i in 0 until n) if (i == 0) path.moveTo(px(i), py(i)) else path.lineTo(px(i), py(i))
        path.close()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE; it.strokeWidth = 0.55f * u; it.color = b.ink
            it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
        }
        drawPath(path, line)
        // start/finish: tick across the direction of travel at the first point
        var k = 1
        while (k < n - 1 && abs(px(k) - px(0)) + abs(py(k) - py(0)) < 0.5f * u) k++
        val dx = px(k) - px(0)
        val dy = py(k) - py(0)
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len
        val ny = dx / len
        val t = 2.2f * u
        hairline(px(0) - nx * t, py(0) - ny * t, px(0) + nx * t, py(0) + ny * t, b.ink, 0.6f * u)
        dot(px(0), py(0), 0.9f * u, b.ink)
    }

    companion object { private const val ACTION_TICK = "com.wdgts.F1_TICK" }
}
