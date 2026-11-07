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

/**
 * Race weekend card: circuit layout + next session countdown, switching to LIVE while a session is on.
 * (Positions/timing during a session would need a live timing feed; this is schedule-driven.)
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
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.mono(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)
        val wk = F1Repo.cached(ctx)
        if (wk == null) {
            label("F1", w / 2f, baselineFor(h / 2f, 22f * u, tf), 22f * u, p.faint, tf, Paint.Align.CENTER)
            return
        }
        val now = System.currentTimeMillis()
        val live = wk.sessions.firstOrNull { now in it.startMs..it.startMs + it.durMin * 60_000L }
        val next = wk.sessions.firstOrNull { it.startMs > now }
        val cur = live ?: next
        val pad = 8f * u
        val track = F1Repo.track(ctx, wk)
        val wide = w >= 1.5f * h

        // what to print
        val header = "R${wk.round} · ${wk.country.uppercase()}"
        val gp = wk.name.removeSuffix(" Grand Prix").uppercase() + " GP"
        val big = when { cur == null -> "DONE"; live != null -> "LIVE"; else -> countdown(cur.startMs - now) }
        val z = cur?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.startMs), ZoneId.systemDefault()) }
        val sess = when {
            cur == null -> "WEEKEND COMPLETE"
            live != null -> cur.name
            else -> cur.name + " · " + DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()).format(z).uppercase()
        }

        // geometry: every line has a fixed slot, so the stack can never run past the card
        val tx: Float; val tw: Float
        val yHeader: Float; val yGp: Float; val yBig: Float; val ySess: Float
        val box: Float; val bx: Float; val by: Float
        if (wide) {
            box = h - 2 * pad; bx = pad; by = pad
            tx = bx + box + pad * 1.6f; tw = w - tx - pad
            yHeader = 0.20f * h; yGp = 0.40f * h; yBig = 0.71f * h; ySess = 0.89f * h
        } else {
            box = minOf(w - 2 * pad, 0.44f * h); bx = (w - box) / 2f; by = 0.125f * h
            tx = pad; tw = w - 2 * pad
            yHeader = 0.095f * h; yGp = 0.685f * h; yBig = 0.865f * h; ySess = 0.955f * h
        }

        if (track != null) drawTrack(track, bx, by, box, u, p)
        else label(wk.round.toString(), bx + box / 2f, baselineFor(by + box / 2f, box * 0.5f, tf), box * 0.5f, p.faint, tf,
            Paint.Align.CENTER, fvar = Fonts.axes(wght = 200, wdth = 110))

        label(header, tx, yHeader, 4.4f * u, p.muted, mono, tracking = 0.04f)
        val (gs, gv) = fitByAxis(gp, tw, if (wide) 9f * u else 8f * u, tf, wght = 520, wdthMax = 100)
        label(gp, tx, yGp, gs, p.accent, tf, fvar = gv)
        val (bs, bv) = fitByAxis(big, tw, if (wide) 17f * u else 14.5f * u, tf, wght = 480, wdthMax = 100)
        label(big, tx, yBig, bs, if (live != null) p.accent else p.onSurface, tf, fvar = bv)
        label(ellipsize(sess, tw, 4.2f * u, mono), tx, ySess, 4.2f * u, p.muted, mono, tracking = 0.04f)
    }

    private fun countdown(ms: Long): String {
        val m = ms / 60_000
        val d = m / 1440
        val h = (m % 1440) / 60
        val mm = m % 60
        return if (d > 0) "${d}D ${h}H" else if (h > 0) "${h}H ${mm}M" else "${mm}M"
    }

    private fun Canvas.drawTrack(pts: FloatArray, bx: Float, by: Float, box: Float, u: Float, p: Palette) {
        val path = Path()
        val n = pts.size / 2
        val m = box * 0.06f
        val s = box - 2 * m
        for (i in 0 until n) {
            val x = bx + m + pts[2 * i] * s
            val y = by + m + pts[2 * i + 1] * s
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE; it.strokeWidth = 1.9f * u; it.color = p.accent
            it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
        }
        drawPath(path, line)
        dot(bx + m + pts[0] * s, by + m + pts[1] * s, 1.9f * u, p.onSurface)   // start / finish
    }

    companion object { private const val ACTION_TICK = "com.wdgts.F1_TICK" }
}
