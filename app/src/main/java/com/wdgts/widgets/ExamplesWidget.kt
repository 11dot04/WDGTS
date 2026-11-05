package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.data.KanjiDay
import com.wdgts.data.KanjiRepo

/** Page 2 of the stack: ten example words for the day's kanji, tap to step through. */
class ExamplesWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_NEXT) {
            Haptics.tick(c)
            val d = KanjiRepo.cached(c)
            if (d != null && d.examples.isNotEmpty()) {
                Prefs.setExIndex(c, d.kanji, (Prefs.exIndex(c, d.kanji) + 1) % d.examples.size)
            }
            refreshAll(c)
        } else super.onReceive(c, i)
    }

    companion object {
        private const val ACTION_NEXT = "com.wdgts.EXAMPLE_NEXT"

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, ExamplesWidget::class.java)).forEach { render(c, m, it) }
        }

        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val (w, h) = Render.sizePx(c, m, id, 170, 170)
            val day = KanjiRepo.cached(c)
            val idx = day?.let { Prefs.exIndex(c, it.kanji) } ?: 0
            val bmp = Render.bitmap(w, h) { drawExample(c, w, h, day, idx) }
            val rv = RemoteViews(c.packageName, R.layout.widget_examples)
            rv.setImageViewBitmap(R.id.img, bmp)
            rv.setOnClickPendingIntent(
                R.id.zone_main,
                PendingIntent.getBroadcast(
                    c, 30, Intent(c, ExamplesWidget::class.java).setAction(ACTION_NEXT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            m.updateAppWidget(id, rv)
        }

        private fun Canvas.drawExample(ctx: Context, w: Int, h: Int, day: KanjiDay?, idx: Int) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val u = minOf(w, h) / 100f
            drawColor(p.surface)

            val ex = day?.examples?.getOrNull(idx)
            if (day == null || ex == null) {
                label("…", 0.5f * w, baselineFor(0.5f * h, 14f * u, tf), 14f * u, p.faint, tf, Paint.Align.CENTER)
                return
            }

            label("[${idx + 1}]", 0.075f * w, 0.14f * h, 7.5f * u, p.accent, Fonts.MONO)

            // M3 Expressive badge: a different shape for every kanji (bun, gem, cookie, clover...)
            val br = 7.5f * u
            drawShape(M3Shapes.pick(day.kanji.codePointAt(0).toLong()), 0.88f * w, 0.12f * h, br * 2f, p.accent)
            val ks = br * 1.05f
            label(day.kanji, 0.88f * w, baselineFor(0.12f * h, ks, tf), ks, p.surface, tf, Paint.Align.CENTER)

            val rw = wrapFit(ex.reading, 0.85f * w, 1, 6.4f * u, tf)
            label(ex.reading, 0.075f * w, 0.345f * h, rw.size, p.muted, tf, fvar = rw.fvar)

            // the word keeps its size: the width axis condenses it before the size ever drops
            val (ws, wv) = fitByAxis(ex.written, 0.86f * w, 18.5f * u, tf, wght = 300, wdthMax = 100)
            label(ex.written, 0.075f * w, 0.545f * h, ws, p.accent, tf, fvar = wv)

            // gloss: right-aligned, wraps onto up to three lines at full size
            val g = wrapFit(ex.gloss, 0.84f * w, 3, 8.6f * u, tf, wght = 420)
            g.lines.forEachIndexed { i, l ->
                label(l, 0.93f * w, 0.655f * h + i * g.size * 1.22f, g.size, p.accent, tf, Paint.Align.RIGHT, fvar = g.fvar)
            }

            val dr = 2.1f * u
            dot(0.806f * w, 0.885f * h, dr, p.tertiary)
            dot(0.908f * w, 0.885f * h, dr, p.tertiary)
            dot(0.857f * w, 0.937f * h, dr, p.tertiary)
        }
    }
}
