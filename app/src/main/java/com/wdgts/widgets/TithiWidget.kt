package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.panchang.DayPanchang
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * All-typography card. Layout rules (from the KWGT original):
 *  - the UPTO line spans the full measure; the width axis is slid so it lands exactly edge to edge
 *  - month: left, in the accent colour;  tithi name: right;  "KP"/"SP" sits on the left edge of the tithi name
 *  - bottom row: DRIK PNCHNG link (its arrow lines up with the tithi column) and the NEXT button
 *  - spoiler: peek N days ahead; MINUS ONE steps back, NEXT steps forward
 */
class TithiWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        val today = LocalDate.now().toEpochDay()
        when (i.action) {
            ACTION_NEXT -> { Haptics.click(c); Prefs.setSpoilerOffset(c, Prefs.spoilerOffset(c, today) + 1, today); refreshAll(c) }
            ACTION_PREV -> { Haptics.click(c); Prefs.setSpoilerOffset(c, Prefs.spoilerOffset(c, today) - 1, today); refreshAll(c) }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> refreshAll(c)
            else -> super.onReceive(c, i)
        }
    }

    companion object {
        private const val ACTION_NEXT = "com.wdgts.TITHI_NEXT"
        private const val ACTION_PREV = "com.wdgts.TITHI_PREV"
        private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
        private val drikFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, TithiWidget::class.java)).forEach { render(c, m, it) }
        }

        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val (w, h) = Render.sizePx(c, m, id, 160, 160)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val off = Prefs.spoilerOffset(c, today.toEpochDay())
            val shown = today.plusDays(off.toLong())
            val d = Panchang.compute(shown, zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c))

            val bmp = Render.bitmap(w, h) { drawTithi(c, w, h, d, off, zone) }
            val rv = RemoteViews(c.packageName, R.layout.widget_tithi)
            rv.setImageViewBitmap(R.id.img, bmp)

            val drik = PendingIntent.getActivity(
                c, 10,
                Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.drikpanchang.com/panchang/day-panchang.html?date=" + shown.format(drikFmt))),
                FLAGS,
            )
            val next = PendingIntent.getBroadcast(c, 11, Intent(c, TithiWidget::class.java).setAction(ACTION_NEXT), FLAGS)
            val prev = PendingIntent.getBroadcast(c, 12, Intent(c, TithiWidget::class.java).setAction(ACTION_PREV), FLAGS)
            rv.setOnClickPendingIntent(R.id.zone_main, drik)
            rv.setOnClickPendingIntent(R.id.zone_a, drik)
            if (off == 0) {
                rv.setOnClickPendingIntent(R.id.zone_b, next)
                rv.setOnClickPendingIntent(R.id.zone_c, next)
            } else {
                rv.setOnClickPendingIntent(R.id.zone_b, prev)
                rv.setOnClickPendingIntent(R.id.zone_c, next)
            }
            m.updateAppWidget(id, rv)
        }

        private fun Canvas.drawTithi(ctx: Context, w: Int, h: Int, d: DayPanchang, off: Int, zone: ZoneId) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val u = minOf(w, h) / 100f
            val pad = 8.5f * u
            val left = pad
            val right = w - pad
            drawColor(p.surface)

            // ---- text block -------------------------------------------------------------
            val kp = if (d.paksha == Paksha.KRISHNA) "KP" else "SP"
            val num = String.format(Locale.US, "%02d,", d.pakshaDay)
            val endZ = Instant.ofEpochMilli(d.tithiEndMs).atZone(zone)
            val endLabel = "UPTO " + timeFmt.format(endZ).uppercase() +
                if (endZ.toLocalDate().isAfter(d.date)) " +1" else ""
            val month = d.month.uppercase()
            val tithi = d.tithiName.uppercase()

            // the UPTO line defines the measure: width axis slides until it spans left..right exactly
            val (size, fv) = fitByAxis(endLabel, right - left, 12.7f * u, tf, wght = 400, grow = true)
            val lh = size * 1.06f
            val divY = 0.80f * h
            val y4 = divY - 3.2f * u
            val y3 = y4 - lh
            val y2 = y3 - lh
            val y1 = y2 - lh

            // a month longer than the measure is condensed instead of shrunk
            val (ms, mv) = if (measureText(month, size, tf, 0f, fv) > right - left)
                fitByAxis(month, right - left, size, tf, wght = 400, wdthMax = 100) else size to fv

            label(endLabel, left, y4, size, p.faint, tf, fvar = fv)
            label(tithi, right, y3, size, p.muted, tf, Paint.Align.RIGHT, fvar = fv)
            label(month, left, y2, ms, p.accent, tf, fvar = mv)
            val tithiLeft = right - measureText(tithi, size, tf, 0f, fv)
            label(kp, tithiLeft, y1, size, p.muted, tf, fvar = fv)            // KP/SP shares the tithi name's left edge
            label(num, right, y1, size, p.accent, tf, Paint.Align.RIGHT, fvar = fv)

            // ---- festival chips, top-left ----------------------------------------------
            if (d.festivals.isNotEmpty()) {
                val cs = 4.4f * u
                val chipH = 8.2f * u
                var x = left
                fun chip(t: String) {
                    val tw = measureText(t, cs, tf, 0.02f)
                    val r = RectF(x, pad * 0.8f, x + tw + 6f * u, pad * 0.8f + chipH)
                    outlinePill(r, p.faint, 0.45f * u)
                    label(t, x + 3f * u, pad * 0.8f + chipH / 2 + cs * 0.36f, cs, p.onSurface, tf, tracking = 0.02f)
                    x = r.right + 2f * u
                }
                chip(d.festivals[0].uppercase())
                if (d.festivals.size > 1) chip("+${d.festivals.size - 1}")
            }

            // ---- divider + buttons ---------------------------------------------------------
            hairline(left, divY, right, divY, p.accent, 0.4f * u)
            val bs = 6.4f * u
            val b1 = 0.895f * h
            val b2 = 0.955f * h
            val bv = Fonts.axes(wght = 470, wdth = 100)
            val btnCy = 0.91f * h
            val ink = p.tertiary

            if (off == 0) {
                label("DRIK", left, b1, bs, ink, tf, fvar = bv)
                val pw = label("PNCHNG", left, b2, bs, ink, tf, fvar = bv)
                // the link arrow's right edge lines up with the right edge of PNCHNG
                arrow(left + pw - 2.1f * u, b1 - bs * 0.36f, 2.1f * u, ink, 0.55f * u, diagonal = true)
                nextPebbles(right, btnCy, 6.0f * u, ink, p.surface)
            } else {
                label("SPLR", left, b1, bs, ink, tf, fvar = bv)
                label("D+$off", left, b2, bs, ink, tf, fvar = bv)
                val mx = 0.50f * w
                label("MINUS", mx, b1, bs, ink, tf, fvar = bv)
                val ow = label("ONE", mx, b2, bs, ink, tf, fvar = bv)
                arrow(mx + ow + 4.2f * u, b2 - bs * 0.36f, 2.9f * u, ink, 0.55f * u, diagonal = false)
                nextHalves(right, btnCy, 5.4f * u, ink, tf, bs * 0.62f)
            }
        }

        /** Arrow: diagonal "↗" (link) or left "←" (back). [cx],[cy] is its centre, [s] the half-size. */
        private fun Canvas.arrow(cx: Float, cy: Float, s: Float, color: Int, stroke: Float, diagonal: Boolean) {
            val pnt = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = stroke
                it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            if (diagonal) {
                path.moveTo(cx - s, cy + s); path.lineTo(cx + s, cy - s)
                path.moveTo(cx - s * 0.1f, cy - s); path.lineTo(cx + s, cy - s); path.lineTo(cx + s, cy + s * 0.1f)
            } else {
                path.moveTo(cx + s, cy); path.lineTo(cx - s, cy)
                path.moveTo(cx - s * 0.1f, cy - s * 0.8f); path.lineTo(cx - s, cy); path.lineTo(cx - s * 0.1f, cy + s * 0.8f)
            }
            drawPath(path, pnt)
        }

        /**
         * "Next" button: a thin and a fat pebble, then a disc that overlaps the fat one, with one arrow
         * running across both. [rightX] is the button's right edge.
         */
        private fun Canvas.nextPebbles(rightX: Float, cy: Float, r: Float, color: Int, hole: Int) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
            val discCx = rightX - r
            val fat = RectF(rightX - 3.05f * r, cy - r, rightX - 1.6f * r, cy + r)
            drawOval(RectF(rightX - 3.85f * r, cy - r, rightX - 3.3f * r, cy + r), f)
            drawOval(fat, f)
            drawCircle(discCx, cy, r, f)
            val pnt = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.color = hole; it.strokeWidth = 0.2f * r
                it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
            }
            val x0 = fat.centerX()
            val x1 = discCx + 0.4f * r
            val path = Path()
            path.moveTo(x0, cy); path.lineTo(x1, cy)
            path.moveTo(x1 - 0.4f * r, cy - 0.4f * r); path.lineTo(x1, cy); path.lineTo(x1 - 0.4f * r, cy + 0.4f * r)
            drawPath(path, pnt)
        }

        /** Spoiler "NEXT": two overlapping half-discs and the word set vertically along the right edge (reads downward). */
        private fun Canvas.nextHalves(rightX: Float, cy: Float, r: Float, color: Int, tf: android.graphics.Typeface, ts: Float) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
            val dw = r * 0.95f
            val flatR = rightX - ts * 1.5f - dw                       // flat edge of the front half-disc
            drawArc(RectF(flatR - 0.6f * dw - dw, cy - r, flatR - 0.6f * dw + dw, cy + r), -90f, 180f, true, f)
            drawArc(RectF(flatR - dw, cy - r, flatR + dw, cy + r), -90f, 180f, true, f)
            val tx = rightX - ts * 0.5f
            save()
            rotate(90f, tx, cy)
            label("NEXT", tx, cy + ts * 0.35f, ts, color, tf, Paint.Align.CENTER, 0.04f, Fonts.axes(wght = 480))
            restore()
        }
    }
}
