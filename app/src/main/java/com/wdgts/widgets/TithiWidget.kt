package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
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

class TithiWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_TOGGLE -> {
                val today = LocalDate.now().toEpochDay()
                Prefs.setSpoiler(c, !Prefs.spoilerOn(c, today), today)
                refreshAll(c)
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> refreshAll(c)
            else -> super.onReceive(c, i)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.wdgts.TITHI_TOGGLE"
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
            val spoiler = Prefs.spoilerOn(c, today.toEpochDay())
            val shown = if (spoiler) today.plusDays(1) else today
            val d = Panchang.compute(shown, zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c))

            val bmp = Render.bitmap(w, h) { drawTithi(c, w, h, d, spoiler, zone) }

            val rv = RemoteViews(c.packageName, R.layout.widget_tithi)
            rv.setImageViewBitmap(R.id.img, bmp)

            val drik = PendingIntent.getActivity(
                c, 10,
                Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.drikpanchang.com/panchang/day-panchang.html?date=" + shown.format(drikFmt))),
                FLAGS,
            )
            val toggle = PendingIntent.getBroadcast(
                c, 11, Intent(c, TithiWidget::class.java).setAction(ACTION_TOGGLE), FLAGS,
            )
            rv.setOnClickPendingIntent(R.id.zone_main, drik)
            if (!spoiler) {
                rv.setOnClickPendingIntent(R.id.zone_a, drik)
                rv.setOnClickPendingIntent(R.id.zone_b, toggle)
                rv.setOnClickPendingIntent(R.id.zone_c, toggle)
            } else {
                rv.setOnClickPendingIntent(R.id.zone_a, toggle)
                rv.setOnClickPendingIntent(R.id.zone_b, toggle)
                rv.setOnClickPendingIntent(R.id.zone_c, drik)
            }
            m.updateAppWidget(id, rv)
        }

        private fun Canvas.drawTithi(
            ctx: Context, w: Int, h: Int, d: DayPanchang, spoiler: Boolean, zone: ZoneId,
        ) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val u = minOf(w, h) / 100f
            val pad = 7f * u
            val right = w - pad
            drawColor(p.surface)

            // ---- right-aligned text stack, anchored just above the divider ----
            val divY = h - pad - 13f * u
            val kp = if (d.paksha == Paksha.KRISHNA) "KP" else "SP"
            val num = String.format(Locale.US, "%02d,", d.pakshaDay)
            val endZ = Instant.ofEpochMilli(d.tithiEndMs).atZone(zone)
            val endLabel = "UPTO " + timeFmt.format(endZ).uppercase() +
                if (endZ.toLocalDate().isAfter(d.date)) " +1" else ""
            val month = d.month.uppercase()
            val tithi = d.tithiName.uppercase()

            val size = fitSize(listOf("$kp $num", month, tithi, endLabel), w - 2 * pad, 13f * u, tf, -0.02f)
            val lh = size * 1.06f
            var y = divY - 3f * u
            label(endLabel, right, y, size, p.faint, tf, Paint.Align.RIGHT, -0.02f)
            y -= lh; label(tithi, right, y, size, p.muted, tf, Paint.Align.RIGHT, -0.02f)
            y -= lh; label(month, right, y, size, p.onSurface, tf, Paint.Align.RIGHT, -0.02f)
            y -= lh
            val nw = label(num, right, y, size, p.onSurface, tf, Paint.Align.RIGHT, -0.02f)
            label(kp, right - nw - size * 0.25f, y, size, p.muted, tf, Paint.Align.RIGHT, -0.02f)

            // ---- festival chips, top-left ----
            if (d.festivals.isNotEmpty()) {
                val cs = 4.4f * u
                val chipH = 8.2f * u
                var x = pad
                fun chip(t: String) {
                    val tw = measureText(t, cs, tf, 0.02f)
                    val r = RectF(x, pad, x + tw + 6f * u, pad + chipH)
                    outlinePill(r, p.faint, 0.45f * u)
                    label(t, x + 3f * u, pad + chipH / 2 + cs * 0.36f, cs, p.onSurface, tf, tracking = 0.02f)
                    x = r.right + 2f * u
                }
                chip(d.festivals[0].uppercase())
                if (d.festivals.size > 1) chip("+${d.festivals.size - 1}")
            }

            // ---- divider + bottom row ----
            hairline(pad, divY, right, divY, p.faint, 0.35f * u)
            val bs = 4.6f * u
            val b1 = divY + 3.2f * u + bs
            val b2 = b1 + bs * 1.12f
            if (!spoiler) {
                label("DRIK", pad, b1, bs, p.muted, tf, tracking = 0.04f)
                val pw = label("PNCHNG", pad, b2, bs, p.muted, tf, tracking = 0.04f)
                label("↗", pad + pw + 2f * u, b1, bs * 1.2f, p.muted, tf)
                val r = 5.5f * u
                moon(right - r, (divY + h - pad) / 2f, r, d.elongation, p.onSurface, alpha(p.faint, 120))
            } else {
                label("SPLR", pad, b1, bs, p.muted, tf, tracking = 0.04f)
                label("D+1", pad, b2, bs, p.muted, tf, tracking = 0.04f)
                label("MINUS", w * 0.5f, b1, bs, p.muted, tf, tracking = 0.04f)
                label("ONE ←", w * 0.5f, b2, bs, p.muted, tf, tracking = 0.04f)
                label("↗", right, b2, 12f * u, p.onSurface, tf, Paint.Align.RIGHT)
            }
        }
    }
}
