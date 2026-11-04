package com.wdgts.sports

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * Next match / live score / result for one team (ESPN team id in settings; 359 = Arsenal).
 * Polls every minute while live, every few minutes around kickoff, otherwise every half hour.
 */
class TeamWidget : ImageWidget() {

    override fun onUpdate(c: Context, m: android.appwidget.AppWidgetManager, ids: IntArray) {
        super.onUpdate(c, m, ids)
        async { sync(c) }
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_TICK) async { sync(c) } else super.onReceive(c, i)
    }

    private fun sync(c: Context) {
        val m = Espn.fetch(c)
        if (m != null) Prefs.setMatchJson(c, m.toJson())
        refreshAll(c)
        val cur = m ?: Prefs.matchJson(c)?.let { Match.fromJson(it) }
        Sched.arm(c, TeamWidget::class.java, ACTION_TICK, nextWake(cur), 40)
    }

    private fun nextWake(m: Match?): Long {
        val now = System.currentTimeMillis()
        return when {
            m == null -> now + 30 * 60_000L
            m.state == "in" -> now + 60_000L
            m.state == "pre" -> {
                val d = m.kickoffMs - now
                when {
                    d <= 0 -> now + 60_000L
                    d < 20 * 60_000L -> now + 2 * 60_000L
                    else -> minOf(now + 30 * 60_000L, m.kickoffMs - 15 * 60_000L)
                }
            }
            else -> if (now - m.kickoffMs < 4 * 3_600_000L) now + 10 * 60_000L else now + 60 * 60_000L
        }
    }

    override fun bind(ctx: Context, rv: RemoteViews) {
        val m = Prefs.matchJson(ctx)?.let { Match.fromJson(it) } ?: return
        val pi = PendingIntent.getActivity(
            ctx, 41,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.espn.com/soccer/match/_/gameId/${m.id}")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        rv.setOnClickPendingIntent(R.id.zone_main, pi)
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)
        val m = Prefs.matchJson(ctx)?.let { Match.fromJson(it) }
        if (m == null) {
            label("…", w / 2f, baselineFor(h / 2f, 14f * u, tf), 14f * u, p.faint, tf, Paint.Align.CENTER)
            return
        }
        val home = Crest.load(ctx, m.homeLogo)
        val away = Crest.load(ctx, m.awayLogo)

        if (m.state == "pre") {
            // ---- upcoming: same composition as the static KWGT card ----
            val ours = if (m.teamIsHome) home else away
            ours?.let { crest(it, 0.11f * w, 0.07f * h, 0.38f * w, p.accent) }
            val tag = if (m.teamIsHome) Prefs.homeTag(ctx) else "AWAY"
            label("NEXT • $tag", 0.07f * w, 0.70f * h, 6f * u, p.muted, tf, fvar = Fonts.axes(wght = 600))

            val opp = if (m.teamIsHome) m.awayName else m.homeName
            val iconS = 6.2f * u
            stadium(0.07f * w, 0.82f * h - iconS * 0.9f, iconS, p.accent)
            val ox = 0.07f * w + iconS * 1.5f
            val (os, ov) = fitByAxis(opp, w - ox - 6f * u, 8.6f * u, tf, wght = 420, wdthMax = 100)
            label(opp, ox, 0.82f * h, os, p.accent, tf, fvar = ov)
            val (ts, tv) = fitByAxis(kickoffText(m.kickoffMs), w - 12f * u, 7.8f * u, tf, wght = 400, wdthMax = 100)
            label(kickoffText(m.kickoffMs), 0.07f * w, 0.92f * h, ts, p.accent, tf, fvar = tv)
            return
        }

        // ---- live or finished: home crest left, away crest right, big score ----
        val live = m.state == "in"
        val cs = 0.26f * minOf(w, h)
        val pad = 0.07f * w
        home?.let { crest(it, pad, 0.07f * h, cs, p.accent) }
        away?.let { crest(it, w - pad - cs, 0.07f * h, cs, p.accent) }
        val status = if (live) "LIVE • ${m.clock.ifEmpty { "–" }}" else "FT"
        label(status, w / 2f, 0.20f * h, 5.2f * u, if (live) p.accent else p.muted, Fonts.MONO, Paint.Align.CENTER, 0.06f)

        val score = "${m.homeScore.ifEmpty { "0" }}–${m.awayScore.ifEmpty { "0" }}"
        val (ss, sv) = fitByAxis(score, w * 0.84f, 30f * u, tf, wght = 560, wdthMax = 125)
        label(score, w / 2f, baselineFor(0.58f * h, ss, tf), ss, p.onSurface, tf, Paint.Align.CENTER, fvar = sv)

        label(m.homeAbbr.uppercase(), pad, 0.86f * h, 5.6f * u, p.muted, Fonts.MONO, tracking = 0.06f)
        label(m.awayAbbr.uppercase(), w - pad, 0.86f * h, 5.6f * u, p.muted, Fonts.MONO, Paint.Align.RIGHT, 0.06f)
        label(leagueTag(m.league), w / 2f, 0.86f * h, 5.0f * u, p.faint, Fonts.MONO, Paint.Align.CENTER, 0.08f)
    }

    /** Crest drawn as a one-colour duotone (luminance -> [tint]) so it matches the card. */
    private fun Canvas.crest(bmp: Bitmap, left: Float, top: Float, size: Float, tint: Int) {
        val r = android.graphics.Color.red(tint) / 255f
        val g = android.graphics.Color.green(tint) / 255f
        val b = android.graphics.Color.blue(tint) / 255f
        val cm = ColorMatrix(floatArrayOf(
            r * 0.299f, r * 0.587f, r * 0.114f, 0f, 0f,
            g * 0.299f, g * 0.587f, g * 0.114f, 0f, 0f,
            b * 0.299f, b * 0.587f, b * 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).also { it.colorFilter = ColorMatrixColorFilter(cm) }
        val s = size / maxOf(bmp.width, bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        drawBitmap(bmp, null, RectF(left + (size - dw) / 2, top + (size - dh) / 2, left + (size + dw) / 2, top + (size + dh) / 2), paint)
    }

    private fun Canvas.stadium(x: Float, y: Float, s: Float, color: Int) {
        val f = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
        val path = Path()
        path.addRoundRect(RectF(x, y + s * 0.35f, x + s, y + s), s * 0.3f, s * 0.3f, Path.Direction.CW)
        drawPath(path, f)
        for (i in 0..2) drawRect(x + s * (0.06f + i * 0.34f), y, x + s * (0.26f + i * 0.34f), y + s * 0.3f, f)
    }

    private fun leagueTag(l: String) = when (l) {
        "eng.1" -> "PL"; "uefa.champions" -> "UCL"; "eng.fa" -> "FA CUP"; "eng.league_cup" -> "EFL CUP"; else -> l.uppercase()
    }

    private fun kickoffText(ms: Long): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        val soon = ms - System.currentTimeMillis() < 6 * 24 * 3_600_000L
        return DateTimeFormatter.ofPattern(if (soon) "EEEE HH:mm" else "EEE d MMM HH:mm", Locale.getDefault()).format(z)
    }

    companion object { private const val ACTION_TICK = "com.wdgts.TEAM_TICK" }
}
