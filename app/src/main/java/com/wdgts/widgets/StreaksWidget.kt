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

        /** Only the themed (monochrome) layer can be tinted cleanly; otherwise we fall back to a letter. */
        private fun monochrome(c: Context, pkg: String): Drawable? {
            if (pkg.isEmpty()) return null
            return runCatching {
                (c.packageManager.getApplicationIcon(pkg) as? AdaptiveIconDrawable)?.monochrome?.mutate()
            }.getOrNull()
        }

        private fun Canvas.icon(ctx: Context, def: StreakDef, cx: Float, cy: Float, size: Float, color: Int) {
            val d = monochrome(ctx, def.pkg)
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
            drawColor(p.surface)

            val inset = 2.5f * u
            val gap = 1.8f * u
            val cw = (w - 2 * inset) / 4f
            val ch = (h - 2 * inset) / 4f

            for (def in STREAKS) {
                val st = Prefs.streak(ctx, def.id, today, def.seed)
                val rect = RectF(
                    inset + def.col * cw + gap / 2, inset + def.row * ch + gap / 2,
                    inset + (def.col + def.w) * cw - gap / 2, inset + (def.row + def.h) * ch - gap / 2,
                )
                val fill = if (st.doneToday) p.accent else p.tile
                val fg = if (st.doneToday) p.onAccent else p.onSurface
                val rad = minOf(rect.width(), rect.height()) * 0.22f
                drawRoundRect(rect, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = fill })

                val cells = def.w * def.h
                val numColor = if (st.doneToday) p.onAccent else p.muted
                val num = st.count.toString()
                when {
                    cells >= 4 -> {      // big: icon top-left, count bottom-right
                        icon(ctx, def, rect.left + 9f * u, rect.top + 9f * u, 7f * u, fg)
                        val sz = fitSize(listOf(num), rect.width() - 10f * u, 15f * u, tf, -0.03f)
                        label(num, rect.right - 5f * u, rect.bottom - 5f * u, sz, numColor, tf, Paint.Align.RIGHT, -0.03f)
                    }
                    cells == 2 -> {      // wide: icon left, count right
                        icon(ctx, def, rect.left + rect.height() * 0.5f, rect.centerY(), rect.height() * 0.4f, fg)
                        val sz = fitSize(listOf(num), rect.width() * 0.5f, rect.height() * 0.5f, tf, -0.03f)
                        label(num, rect.right - 5f * u, rect.centerY() + sz * 0.35f, sz, numColor, tf, Paint.Align.RIGHT, -0.03f)
                    }
                    else -> icon(ctx, def, rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.42f, fg)
                }
            }
        }
    }
}
