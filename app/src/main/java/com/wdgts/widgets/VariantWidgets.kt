package com.wdgts.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.net.Uri
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.data.KanjiRepo
import com.wdgts.hud.engine.MoonGlyph
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.numberFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Second design language for three existing widgets: the same data and the same Material You colours, drawn as
 * oversized grotesk type that is cropped by the tile, small mono captions, hairlines and a single accent.
 */

/** The size and variation string for a numeral that must stay within [maxW] x [maxH] (never grows past [maxH]). */
private fun giant(text: String, maxW: Float, maxH: Float, tf: Typeface, wght: Int): Pair<Float, String> =
    fitByAxis(text, maxW, maxH / 0.72f, tf, wght = wght, wdthMin = 75, wdthMax = 125)

// ============================================================================================
// Countdown · Giant: the days left as one huge number that the bottom edge crops, a hairline that is the progress
// bar, and mono captions.
// ============================================================================================
class CountdownGiantWidget : ImageWidget() {
    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.mono(ctx)
        val mv = Fonts.monoW(450)
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val sp = Countdown.span(ctx)
        drawColor(p.surface)

        // captions
        val cs = 3.5f * u
        label(if (sp.left == 1) "DAY LEFT" else "DAYS LEFT", pad, pad + cs, cs, p.muted, mono, tracking = 0.08f, fvar = mv)
        label("${(sp.frac * 100).roundToInt()}%", w - pad, pad + cs, cs, p.muted, mono, Paint.Align.RIGHT, 0.08f, mv)

        // the hairline is the whole span; the heavier accent stretch is what has elapsed
        val ry = pad + cs + 3.2f * u
        hairline(pad, ry, w - pad, ry, p.faint, 0.35f * u)
        val done = pad + (w - 2 * pad) * sp.frac
        hairline(pad, ry, done, ry, p.accent, 1.0f * u)

        label("→ " + Countdown.fmt(Prefs.cdEnd(ctx), "d MMM yyyy").uppercase(), pad, ry + 3.2f * u + cs, cs, p.muted, mono,
            tracking = 0.08f, fvar = mv)
        label("DAY ${sp.elapsed}/${sp.total}", w - pad, ry + 3.2f * u + cs, cs, p.muted, mono, Paint.Align.RIGHT, 0.08f, mv)

        // the number: as large as the free height allows, spanning the width, sunk a little below the bottom edge
        val num = sp.left.toString()
        val top = ry + 3.2f * u + cs + 4f * u
        val (size, fv) = giant(num, w - 1.2f * pad, (h - top) * 1.08f, tf, 640)
        label(num, 0.5f * pad, h + 0.05f * size, size, p.onSurface, tf, fvar = fv)
    }
}

// ============================================================================================
// Panchang · Editorial: the tithi's number as the headline, the tithi and month set under a hairline, the paksha,
// the moon and the end time as mono captions.
// ============================================================================================
class TithiEditorialWidget : ImageWidget() {
    private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
    private val drikFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

    override fun bind(ctx: Context, rv: RemoteViews) {
        val url = "https://www.drikpanchang.com/panchang/day-panchang.html?date=" + LocalDate.now().format(drikFmt)
        rv.setOnClickPendingIntent(
            R.id.zone_main,
            PendingIntent.getActivity(ctx, 13, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.mono(ctx)
        val mv = Fonts.monoW(450)
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        val zone = ZoneId.systemDefault()
        val d = Panchang.compute(LocalDate.now(zone), zone, Prefs.lat(ctx), Prefs.lon(ctx), Prefs.purnimanta(ctx))
        drawColor(p.surface)

        // captions: paksha on the left, the moon on the right
        val cs = 3.5f * u
        val paksha = if (d.paksha == Paksha.KRISHNA) "KRISHNA PAKSHA" else "SHUKLA PAKSHA"
        label(paksha, pad, pad + cs, cs, p.muted, mono, tracking = 0.08f, fvar = mv)
        MoonGlyph.draw(this, w - pad - 2.6f * u, pad + cs * 0.5f, 2.6f * u, d.elongation, p.onSurface)

        // the headline number, as large as the middle band allows, cropped a little by the left margin's bleed
        val num = d.numberFor(Prefs.shukla16(ctx)).toString()
        val bandTop = pad + cs + 4f * u
        val bandBottom = 0.70f * h
        val (size, fv) = giant(num, w - 1.2f * pad, bandBottom - bandTop, tf, 640)
        label(num, 0.5f * pad, bandBottom, size, p.onSurface, tf, fvar = fv)

        val ry = 0.74f * h
        hairline(pad, ry, w - pad, ry, p.faint, 0.35f * u)

        // tithi and month, then the end time
        val fs = 9.2f * u
        val lead = fs * 1.08f
        val base2 = h - pad
        val base1 = base2 - lead
        val room = w - 2 * pad
        val (s1, v1) = fitByAxis(d.tithiName.uppercase(), room, fs, tf, wght = 420)
        val (s2, v2) = fitByAxis(d.month.uppercase(), room, fs, tf, wght = 420)
        label(d.tithiName.uppercase(), pad, base1, s1, p.onSurface, tf, fvar = v1)
        label(d.month.uppercase(), pad, base2, s2, p.accent, tf, fvar = v2)

        val endZ = Instant.ofEpochMilli(d.tithiEndMs).atZone(zone)
        val plus = if (endZ.toLocalDate().isAfter(d.date)) " +1D" else ""
        label("UPTO", w - pad, base1 - 2.4f * u, cs, p.muted, mono, Paint.Align.RIGHT, 0.08f, mv)
        label(timeFmt.format(endZ).uppercase() + plus, w - pad, base2 - 0.6f * u, cs, p.muted, mono, Paint.Align.RIGHT, 0.08f, mv)
    }
}

// ============================================================================================
// Kanji · Giant: the glyph far larger than the tile, cropped by it, with the first meaning knocked through it
// in grotesk and the readings in mono underneath. Tap searches the kanji on Jisho.
// ============================================================================================
class KanjiGiantWidget : ImageWidget() {
    override fun bind(ctx: Context, rv: RemoteViews) {
        val k = KanjiRepo.cached(ctx)?.kanji ?: return
        rv.setOnClickPendingIntent(
            R.id.zone_main,
            PendingIntent.getActivity(
                ctx, 14, Intent(Intent.ACTION_VIEW, Uri.parse("https://jisho.org/search/" + Uri.encode("$k #kanji"))),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.geist(ctx)
        val mv = Fonts.monoW(450)
        val u = minOf(w, h) / 100f
        val pad = 7f * u
        drawColor(p.surface)
        val day = KanjiRepo.cached(ctx)
        if (day == null) {
            label("…", w / 2f, h / 2f, 12f * u, p.faint, tf, Paint.Align.CENTER)
            return
        }

        // glyph and text go on separate layers and are combined with XOR: where the meaning crosses the glyph the
        // overlap is knocked out, so both stay readable even though the glyph is enormous
        val glyphLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val textLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gtf = Typeface.create(Typeface.SANS_SERIF, 700, false)
        val gs = 1.38f * minOf(w, h)
        val gc = Canvas(glyphLayer)
        gc.label(day.kanji, 0.60f * w, baselineFor(0.50f * h, gs, gtf), gs, p.tertiary, gtf, Paint.Align.CENTER)

        val tc = Canvas(textLayer)
        val notes = Regex("radical|\\bno\\.|stroke|counter for|kokuji|\\(", RegexOption.IGNORE_CASE)
        val meaning = (day.meanings.firstOrNull { !notes.containsMatchIn(it) } ?: day.meanings.firstOrNull() ?: "").uppercase()
        val cs = 3.4f * u
        tc.inkLabel("N${day.level}  ${day.strokes}S" + if (day.grade > 0) "  G${day.grade}" else "", pad, pad + cs, cs, p.accent, mono,
            tracking = 0.08f, fvar = mv)
        val on = day.on.firstOrNull().orEmpty()
        val kun = day.kun.firstOrNull().orEmpty()
        val reading = listOf(on, kun).filter { it.isNotEmpty() }.joinToString("  ·  ")
        val readBase = h - pad
        val fit = fitLines(meaning, w - 2 * pad, 2, 11f * u, tf)
        val lead = fit.size * 1.02f
        val lastBase = readBase - cs - 4.2f * u
        val firstBase = lastBase - (fit.lines.size - 1) * lead
        fit.lines.forEachIndexed { i, l -> tc.inkLabel(l, pad, firstBase + i * lead, fit.size, p.accent, tf, fvar = fit.fvar) }
        if (reading.isNotEmpty()) {
            val rs = ellipsize(reading, w - 2 * pad, cs, mono)
            tc.inkLabel(rs, pad, readBase, cs, p.accent, mono, tracking = 0.06f, fvar = mv)
        }
        gc.drawBitmap(textLayer, 0f, 0f, Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR) })
        drawBitmap(glyphLayer, 0f, 0f, null)
        glyphLayer.recycle(); textLayer.recycle()
    }
}
