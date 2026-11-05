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
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import java.time.LocalDate

/**
 * Transparent widget; the tiles rest directly on the wallpaper.
 * Pending tile: tertiary (lavender) with surface-coloured glyph and number.
 * Done tile: settles to the surface colour with accent glyphs.
 */
class StreaksWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refreshAll(c)
            else -> super.onReceive(c, i)
        }
    }

    companion object {
        private val CELLS = arrayOf(
            intArrayOf(R.id.z00, R.id.z01, R.id.z02, R.id.z03),
            intArrayOf(R.id.z10, R.id.z11, R.id.z12, R.id.z13),
            intArrayOf(R.id.z20, R.id.z21, R.id.z22, R.id.z23),
            intArrayOf(R.id.z30, R.id.z31, R.id.z32, R.id.z33),
        )

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, StreaksWidget::class.java)).forEach { render(c, m, it) }
        }

        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val (w, h) = Render.sizePx(c, m, id, 200, 200)
            val today = LocalDate.now().toEpochDay()
            val bmp = Render.bitmap(w, h) { drawStreaks(c, w, h, today) }

            val rv = RemoteViews(c.packageName, R.layout.widget_streaks)
            rv.setImageViewBitmap(R.id.img, bmp)
            for (def in STREAKS) {
                val pi = PendingIntent.getActivity(
                    c, def.id.hashCode(),
                    Intent(c, TapActivity::class.java)
                        .setData(Uri.parse("wdgts://streak/${def.id}"))
                        .putExtra("streak", def.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                for (r in def.row until def.row + def.h) for (cc in def.col until def.col + def.w) {
                    rv.setOnClickPendingIntent(CELLS[r][cc], pi)
                }
            }
            m.updateAppWidget(id, rv)
        }

        /** Only the themed (monochrome) layer can be tinted cleanly. */
        private fun monochrome(c: Context, pkg: String): Drawable? {
            if (pkg.isEmpty()) return null
            return runCatching {
                val a = c.packageManager.getApplicationIcon(pkg) as? AdaptiveIconDrawable
                (a?.monochrome ?: a?.foreground)?.mutate()      // themed layer, else the foreground as a silhouette
            }.getOrNull()
        }

        private fun Canvas.globe(cx: Float, cy: Float, r: Float, color: Int) {
            val s = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = r * 0.16f }
            drawCircle(cx, cy, r, s)
            drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), s)
            drawLine(cx - r, cy, cx + r, cy, s)
            drawLine(cx - r * 0.86f, cy - r * 0.5f, cx + r * 0.86f, cy - r * 0.5f, s)
            drawLine(cx - r * 0.86f, cy + r * 0.5f, cx + r * 0.86f, cy + r * 0.5f, s)
        }

        private fun Canvas.icon(ctx: Context, def: StreakDef, cx: Float, cy: Float, size: Float, color: Int) {
            if (def.web) { globe(cx, cy, size * 0.62f, color); return }
            val d = monochrome(ctx, AppResolver.resolve(ctx, def))
            if (d != null) {
                val s = size * 1.5f   // adaptive layers are 108dp with a 72dp visible area
                d.setTint(color)
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.draw(this)
            } else {
                val ch = def.label.ifEmpty { def.id }.take(1).uppercase()
                label(ch, cx, cy + size * 0.35f, size, color, Fonts.display(ctx), Paint.Align.CENTER)
            }
        }

        private fun Canvas.drawStreaks(ctx: Context, w: Int, h: Int, today: Long) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val u = minOf(w, h) / 100f

            // the widget is transparent; only a faint scrim sits behind the tiles
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 11f * u, 11f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = android.graphics.Color.argb(95, 0, 0, 0) })

            val inset = 8.5f * u
            val gap = 2.8f * u
            val cw = (w - 2 * inset) / 4f
            val ch = (h - 2 * inset) / 4f

            for (def in STREAKS) {
                val st = Prefs.streak(ctx, def.id, today, def.seed)
                val rect = RectF(
                    inset + def.col * cw + gap / 2, inset + def.row * ch + gap / 2,
                    inset + (def.col + def.w) * cw - gap / 2, inset + (def.row + def.h) * ch - gap / 2,
                )
                val fill = if (st.doneToday) p.surface else p.tertiary
                val ink = if (st.doneToday) p.accent else p.surface
                val rad = minOf(rect.width(), rect.height()) * 0.22f
                drawRoundRect(rect, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = fill })

                val cells = def.w * def.h
                val num = st.count.toString()
                when {
                    cells >= 4 -> {      // big: icon top-left, light-weight count bottom-right
                        icon(ctx, def, rect.left + 8.5f * u, rect.top + 8.5f * u, 10f * u, ink)
                        val (sz, fv) = fitByAxis(num, rect.width() - 10f * u, 13f * u, tf, wght = 270, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.bottom - 3.4f * u, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                    }
                    cells == 2 -> {      // wide: icon left, count right
                        icon(ctx, def, rect.left + rect.height() * 0.5f, rect.centerY(), rect.height() * 0.55f, ink)
                        val (sz, fv) = fitByAxis(num, rect.width() * 0.5f, rect.height() * 0.5f, tf, wght = 270, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.centerY() + sz * 0.35f, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                    }
                    else -> icon(ctx, def, rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.55f, ink)
                }
            }
        }
    }
}
