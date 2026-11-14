package com.wdgts.sports

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
 *
 * Neo-brutalist: flat paper and one loud block colour, heavy wide numerals, hard rules, stamped chips with hard
 * shadows. Colours come from [Bold] (Material You unless the setting turns it off).
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
        val f = Espn.fetch(c)
        if (f.ok) Prefs.setMatchJson(c, f.match?.toJson())      // a successful fetch with no match clears the card
        refreshAll(c)
        val cur = if (f.ok) f.match else Prefs.matchJson(c)?.let { Match.fromJson(it) }
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
        val b = Bold.of(ctx)
        val m = Prefs.matchJson(ctx)?.let { Match.fromJson(it) }
        when {
            m == null -> paintEmpty(ctx, w, h, b)
            m.state == "pre" -> paintNext(ctx, w, h, b, m)
            else -> paintScore(ctx, w, h, b, m)
        }
    }

    /** No fixture: paper, a heavy two-line message and a block strip. */
    private fun Canvas.paintEmpty(ctx: Context, w: Int, h: Int, b: Bold) {
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val face = BoldFonts.numerals(ctx)
        val mono = Fonts.geist(ctx)
        drawColor(b.paper)
        val (s, v) = fitFace("MATCH", w - 2 * pad, 26f * u, face)      // the longer line sets size and width for both
        val y1 = 0.40f * h
        label("NO", pad, y1, s, b.ink, face.tf, fvar = v)
        label("MATCH", pad, y1 + s * 0.86f, s, b.ink, face.tf, fvar = v)
        val top = 0.82f * h
        flat(0f, top, w.toFloat(), h.toFloat(), b.block)
        label("SCHEDULED", pad, (top + h) / 2f + 0.36f * 5.4f * u, 5.4f * u, b.onBlock, mono, tracking = 0.08f, fvar = Fonts.monoW(650))
    }

    /** Upcoming: a block band with the tag, the opponent in heavy caps over a crest watermark, a stamped kickoff chip. */
    private fun Canvas.paintNext(ctx: Context, w: Int, h: Int, b: Bold, m: Match) {
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val tf = Fonts.display(ctx)
        val mono = Fonts.geist(ctx)
        drawColor(b.paper)

        // opponent crest as a quiet watermark, bleeding off the bottom right
        val oppLogo = if (m.teamIsHome) m.awayLogo else m.homeLogo
        Crest.load(ctx, oppLogo)?.let {
            val s = 0.80f * minOf(w, h)
            silhouette(it, w - 0.55f * s, h - 0.70f * s, s, mix(b.paper, b.ink, 0.13f))
        }

        // band: tag left, competition right
        val bandH = 0.26f * h
        flat(0f, 0f, w.toFloat(), bandH, b.block)
        flat(0f, bandH, w.toFloat(), bandH + 1.1f * u, b.ink)
        val ts = 5f * u
        val tag = if (m.teamIsHome) Prefs.homeTag(ctx) else "AWAY"
        val ty = bandH / 2f + 0.36f * ts
        label("NEXT · $tag", pad, ty, ts, b.onBlock, mono, tracking = 0.06f, fvar = Fonts.monoW(650))
        label(leagueTag(m.league), w - pad, ty, ts, b.onBlock, mono, Paint.Align.RIGHT, 0.06f, Fonts.monoW(650))

        // our own team, small, in front of the opponent: "ARSENAL V"
        val maxW = w - 2 * pad
        val ours = (if (m.teamIsHome) m.homeName else m.awayName).uppercase()
        val os = 4.4f * u
        val oy = bandH + 1.1f * u + 0.8f * pad + 0.72f * os
        label(ellipsize("$ours V", maxW, os, mono), pad, oy, os, b.soft, mono, tracking = 0.08f, fvar = Fonts.monoW(600))

        // opponent: heavy caps, up to two lines, as large as the card allows
        val opp = (if (m.teamIsHome) m.awayName else m.homeName).uppercase()
        val wf = wrapFit(opp, maxW, 2, 17f * u, tf, wght = 860, minWdth = 60, maxWdth = 118)
        // a single long word can still be wider than the card after condensing: shrink it to fit
        val widest = wf.lines.maxOfOrNull { measureText(it, wf.size, tf, 0f, wf.fvar) } ?: 0f
        val ns = if (widest > maxW) wf.size * maxW / widest else wf.size
        var y = oy + 0.8f * os + ns * 0.78f
        wf.lines.forEach { line ->
            label(line, pad, y, ns, b.ink, tf, fvar = wf.fvar)
            y += ns * 0.92f
        }

        // kickoff chip, bottom left
        val text = kickoffText(m.kickoffMs).uppercase()
        var cs = 5.4f * u
        val cw = chipWidth(text, cs, mono, Fonts.monoW(650))
        if (cw > maxW) cs *= maxW / cw
        val cy = h - pad - 1.05f * cs
        chip(text, pad, cy, cs, mono, Fonts.monoW(650), b.block, b.onBlock, b.ink, flat = true)
    }

    /** Live or full time: crests in the corners, a status chip between them, two huge numerals, a rule and the footer. */
    private fun Canvas.paintScore(ctx: Context, w: Int, h: Int, b: Bold, m: Match) {
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val live = m.state == "in"
        val ground = if (live) b.block else b.paper
        val fg = if (live) b.onBlock else b.ink
        val soft = if (live) b.onBlock else b.soft
        val mono = Fonts.geist(ctx)
        val face = BoldFonts.numerals(ctx)
        drawColor(ground)

        // crests
        val cs = 0.21f * minOf(w, h)
        val top = 0.05f * h
        Crest.load(ctx, m.homeLogo)?.let { crest(it, pad, top, cs, fg) }
        Crest.load(ctx, m.awayLogo)?.let { crest(it, w - pad - cs, top, cs, fg) }

        // status chip between them
        val hs = m.homeScore.toIntOrNull()
        val aS = m.awayScore.toIntOrNull()
        val res = if (!live && hs != null && aS != null) {
            val d = if (m.teamIsHome) hs - aS else aS - hs
            if (d > 0) "WIN" else if (d < 0) "LOSS" else "DRAW"
        } else null
        val status = if (live) "LIVE ${m.clock.ifEmpty { "–" }}" else if (res != null) "FT · $res" else "FT"
        val gap = w - 2 * (pad + cs) - 4f * u
        var ss = 5f * u
        val sw = chipWidth(status, ss, mono, Fonts.monoW(700))
        if (sw > gap) ss *= gap / sw
        val sx = (w - chipWidth(status, ss, mono, Fonts.monoW(700))) / 2f
        chip(
            status, sx, top + cs / 2f, ss, mono, Fonts.monoW(700),
            fill = if (live) b.paper else b.block, textColor = if (live) b.ink else b.onBlock, edge = fg,
        )

        // numerals
        val yBase = 0.80f * h
        val figH = 0.44f * h
        val homeS = m.homeScore.ifEmpty { "0" }
        val awayS = m.awayScore.ifEmpty { "0" }
        val colW = 0.5f * w - pad - 6f * u
        val longest = if (homeS.length >= awayS.length) homeS else awayS
        val (ns, nv) = fitFace(longest, colW, figureSize(face, figH), face)
        label(homeS, pad, yBase, ns, fg, face.tf, Paint.Align.LEFT, 0f, nv)
        label(awayS, w - pad, yBase, ns, fg, face.tf, Paint.Align.RIGHT, 0f, nv)
        val my = yBase - figH * 0.5f
        flat(w / 2f - 3.2f * u, my - 1.2f * u, w / 2f + 3.2f * u, my + 1.2f * u, fg)

        // rule and footer
        val ruleY = 0.855f * h
        flat(0f, ruleY, w.toFloat(), ruleY + 1.1f * u, fg)
        val fy = (ruleY + 1.1f * u + h) / 2f + 0.36f * 5.4f * u
        label(m.homeAbbr.uppercase(), pad, fy, 5.4f * u, fg, mono, Paint.Align.LEFT, 0.06f, Fonts.monoW(700))
        label(m.awayAbbr.uppercase(), w - pad, fy, 5.4f * u, fg, mono, Paint.Align.RIGHT, 0.06f, Fonts.monoW(700))
        label(leagueTag(m.league), w / 2f, fy, 4.6f * u, soft, mono, Paint.Align.CENTER, 0.08f, Fonts.monoW(450))
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
