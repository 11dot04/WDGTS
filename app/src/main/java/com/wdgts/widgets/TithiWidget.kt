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
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.panchang.DayPanchang
import com.wdgts.panchang.Panchang
import com.wdgts.hud.engine.MoonGlyph
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.numberFor
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

        /** The picture for the in-app preview: today's tithi, no spoiler offset. Same drawing as the widget. */
        fun preview(c: Context, w: Int, h: Int): android.graphics.Bitmap {
            val zone = ZoneId.systemDefault()
            val d = Panchang.compute(LocalDate.now(zone), zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c))
            return Render.bitmap(w, h) { drawTithi(c, w, h, d, 0, zone) }
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
            val num = String.format(Locale.US, "%02d,", d.numberFor(Prefs.shukla16(ctx)))
            val endZ = Instant.ofEpochMilli(d.tithiEndMs).atZone(zone)
            val endLabel = "UPTO " + timeFmt.format(endZ).uppercase()
            val nextDay = endZ.toLocalDate().isAfter(d.date)
            val month = d.month.uppercase()
            val tithi = d.tithiName.uppercase()

            // the UPTO line defines the measure: width axis slides until it spans left..right exactly.
            // When it ends tomorrow, "AM"/"PM" shrinks and gets a solid "+ONE" sticker stacked under it, flush right:
            // the two together are exactly as tall as the time's capitals, so the line keeps its height.
            val full = 11.9f * u                                                       // KWGT: cap height 41 px on a 486 px card
            val cap = Rect().also { textPaint(full, 0, tf).getTextBounds("0", 0, 1, it) }.height().toFloat() / full
            val timeText = endLabel.substringBeforeLast(' ')                           // "UPTO 06:06"
            val ampm = endLabel.substringAfterLast(' ')                                // "AM"
            val ampmSize = full * 0.5f
            val stickerSize = full * 0.3f
            // the sticker is exactly as wide as AM; its text is condensed (then shrunk) to sit inside it
            val colW = if (nextDay) measureText(ampm, ampmSize, tf, 0f) else 0f
            val (sSize, sv) = if (nextDay) fitByAxis("+ONE", colW - 0.7f * stickerSize, stickerSize, tf, wght = 700, wdthMax = 100)
            else stickerSize to null
            val gapCol = if (nextDay) 0.35f * full else 0f
            val (size, fv) = fitByAxis(if (nextDay) timeText else endLabel, right - left - colW - gapCol, full, tf, wght = 400, grow = true)
            val lh = size * 1.16f                                                       // and a baseline pitch of ~67 px
            val divY = 0.80f * h
            val y4 = divY - 3.2f * u
            val y3 = y4 - lh
            val y2 = y3 - lh
            val y1 = y2 - lh

            // a month longer than the measure is condensed instead of shrunk
            val (ms, mv) = if (measureText(month, size, tf, 0f, fv) > right - left)
                fitByAxis(month, right - left, size, tf, wght = 400, wdthMax = 100) else size to fv

            if (!nextDay) label(endLabel, left, y4, size, p.faint, tf, fvar = fv)
            else {
                label(timeText, left, y4, size, p.faint, tf, fvar = fv)
                val colRight = right
                val colLeft = colRight - colW
                val capTop = y4 - cap * size
                label(ampm, colLeft, capTop + cap * ampmSize, ampmSize, p.faint, tf)
                // the sticker fills what is left of the capital height under AM, down to the baseline
                val gap = 0.07f * size
                val sTop = capTop + cap * ampmSize + gap
                val r = RectF(colLeft, sTop, colRight, y4)
                drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.faint })
                val tw = measureText("+ONE", sSize, tf, 0f, sv)
                val scap = Rect().also { textPaint(sSize, 0, tf, fvar = sv).getTextBounds("E", 0, 1, it) }.height().toFloat()
                label("+ONE", r.centerX() - tw / 2f, r.centerY() + scap / 2f, sSize, p.surface, tf, fvar = sv)
            }
            label(tithi, right, y3, size, p.muted, tf, Paint.Align.RIGHT, fvar = fv)
            label(month, left, y2, ms, p.accent, tf, fvar = mv)
            val tithiLeft = right - measureText(tithi, size, tf, 0f, fv)
            label(kp, tithiLeft, y1, size, p.muted, tf, fvar = fv)            // KP/SP shares the tithi name's left edge
            // the moon phase sits between SP/KP and the number, centred in the gap
            val spEnd = tithiLeft + measureText(kp, size, tf, 0f, fv)
            val numStart = right - measureText(num, size, tf, 0f, fv)
            val moonR = size * 0.42f
            val moonCy = y1 - size * 0.35f
            if (numStart - spEnd > moonR * 2f + 4f * u) {
                MoonGlyph.draw(this, (spEnd + numStart) / 2f, moonCy, moonR, d.elongation, p.onSurface)      // between SP/KP and the number
            } else if (tithiLeft - left > moonR * 2f + 2f * u) {
                MoonGlyph.draw(this, tithiLeft - 1.4f * u - moonR, moonCy, moonR, d.elongation, p.onSurface)  // no room there: before SP/KP
            }
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
                arrow(left + pw + 0.1f * u - 1.78f * u, b1 - bs * 0.40f, 1.78f * u, ink, 0.77f * u, diagonal = true)
                nextPebbles(right, btnCy, 6.0f * u, ink, p.surface)
            } else {
                label("SPLR", left, b1, bs, ink, tf, fvar = bv)
                label("D+$off", left, b2, bs, ink, tf, fvar = bv)
                val mx = 0.52f * w
                val mw = label("MINUS", mx, b1, bs, ink, tf, fvar = bv)
                val ow = label("ONE", mx, b2, bs, ink, tf, fvar = bv)
                // the back arrow ends where MINUS ends and sits on the middle of ONE's capitals; as long as the room after ONE allows
                val capBox = android.graphics.Rect()
                textPaint(bs, 0, tf, fvar = bv).getTextBounds("ONE", 0, 3, capBox)
                val capMid = b2 + (capBox.top + capBox.bottom) / 2f
                val room = (mw - ow - 1.6f * u).coerceIn(3.2f * u, 4.2f * u)           // shaft length
                val ah = room / (2f * 1.41421356f)
                arrow(mx + mw - ah * 1.41421356f - 0.35f * u, capMid, ah, ink, 0.7f * u, diagonal = false)
                nextHalves(right, btnCy, 5.76f * u, ink, tf, 5.0f * u)
            }
        }

        /** Arrow: diagonal "↗" (link) or left "←" (back). [cx],[cy] is its centre, [s] the half-size. */
        /**
         * The card's arrow, measured on the KWGT version: a square box, the shaft running corner to corner and a head of two
         * arms as long as the box side (so the stem is short, as long as an arm). [diagonal] false = the same arrow turned to point left.
         */
        private fun Canvas.arrow(cx: Float, cy: Float, s: Float, color: Int, stroke: Float, diagonal: Boolean) {
            val pnt = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = stroke
                it.strokeCap = Paint.Cap.BUTT; it.strokeJoin = Paint.Join.MITER; it.strokeMiter = 6f
            }
            val path = Path()
            path.moveTo(cx - s, cy + s); path.lineTo(cx + s, cy - s)
            path.moveTo(cx - s, cy - s); path.lineTo(cx + s, cy - s); path.lineTo(cx + s, cy + s)
            save()
            if (!diagonal) rotate(-135f, cx, cy)
            drawPath(path, pnt)
            restore()
        }

        /**
         * "Next" button: a thin and a fat pebble, then a disc that overlaps the fat one, with one arrow
         * running across both. [rightX] is the button's right edge.
         */
        private fun Canvas.nextPebbles(rightX: Float, cy: Float, r: Float, color: Int, hole: Int) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
            val discCx = rightX - r
            val thin = RectF(rightX - 3.49f * r, cy - r, rightX - 3.07f * r, cy + r)     // measured: centre -3.28r, half-width 0.21r
            val fat = RectF(rightX - 3.04f * r, cy - r, rightX - 2.00f * r, cy + r)      // centre -2.52r, half-width 0.52r
            drawOval(thin, f)
            drawOval(fat, f)
            drawCircle(discCx, cy, r, f)
            // one thin arrow runs from the thin pebble's centre through the fat one into the disc, open head
            val pnt = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.color = hole; it.strokeWidth = 0.12f * r
                it.strokeCap = Paint.Cap.BUTT; it.strokeJoin = Paint.Join.MITER; it.strokeMiter = 6f
            }
            val x0 = thin.centerX()
            val x1 = discCx + 0.62f * r
            val path = Path()
            path.moveTo(x0, cy); path.lineTo(x1, cy)
            path.moveTo(x1 - 0.5f * r, cy - 0.5f * r); path.lineTo(x1, cy); path.lineTo(x1 - 0.5f * r, cy + 0.5f * r)
            drawPath(path, pnt)
        }

        /** Spoiler "NEXT": two overlapping half-discs and the word set vertically along the right edge (reads downward). */
        private fun Canvas.nextHalves(rightX: Float, cy: Float, r: Float, color: Int, tf: android.graphics.Typeface, ts: Float) {
            // two identical half-rings, bulging right; fitted to the KWGT card
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color; it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.BUTT }
            val w = r * 0.31f
            ring.strokeWidth = w
            val flat2 = rightX - 2f * r + r * 0.214f           // the front ring's rightmost point is 4.5u from the edge
            val flat1 = flat2 - r * 0.9f
            for (fl in floatArrayOf(flat1, flat2)) drawArc(RectF(fl - r + w / 2f, cy - r + w / 2f, fl + r - w / 2f, cy + r - w / 2f), -90f, 180f, false, ring)
            val unit = r / 5.76f
            val tx = rightX - 3.1f * unit                      // the word, reading upward, in the last 5.5u
            // sized so that its length runs from the top edge of the arcs to their bottom edge
            val axes = Fonts.axes(wght = 480)
            val per = measureText("NEXT", 100f, tf, 0.02f, axes) / 100f
            val fit = 2f * r / per
            save()
            rotate(-90f, tx, cy)
            label("NEXT", tx, cy + fit * 0.35f, fit, color, tf, Paint.Align.CENTER, 0.02f, axes)
            restore()
        }
    }
}
