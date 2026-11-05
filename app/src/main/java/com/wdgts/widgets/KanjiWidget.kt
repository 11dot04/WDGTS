package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.data.KanjiDay
import com.wdgts.data.KanjiRepo
import com.wdgts.data.KanjiVg
import java.util.concurrent.atomic.AtomicBoolean

/** Page 1 of the stack: the kanji, meanings, readings. Tap = stroke-order animation; tap the level = cycle N5..N1. */
class KanjiWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, m, it) }          // instant, from cache
        async { sync(c) }                         // then top up from the network if stale
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_ANIMATE -> { Haptics.click(c); animate(c) }
            ACTION_LEVEL -> {
                Haptics.click(c)
                val n = Prefs.jlpt(c)
                Prefs.setJlpt(c, if (n <= 1) 5 else n - 1)
                refreshKanjiWidgets(c)
                async { sync(c) }
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                async { sync(c) }
            else -> super.onReceive(c, i)
        }
    }

    private fun animate(c: Context) {
        if (!animating.compareAndSet(false, true)) return
        async {
            try { runAnimation(c) } finally { animating.set(false); refreshAll(c) }   // refreshAll restores the static glyph
        }
    }

    companion object {
        private const val ACTION_ANIMATE = "com.wdgts.KANJI_ANIMATE"
        private const val ACTION_LEVEL = "com.wdgts.KANJI_LEVEL"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private val animating = AtomicBoolean(false)

        fun sync(c: Context) {
            KanjiRepo.ensure(c)
            refreshKanjiWidgets(c)
        }

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, KanjiWidget::class.java)).forEach { render(c, m, it) }
        }

        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val (w, h) = Render.sizePx(c, m, id, 170, 170)
            val day = KanjiRepo.cached(c)
            val bmp = Render.bitmap(w, h) { drawKanji(c, w, h, day) }

            val rv = RemoteViews(c.packageName, R.layout.widget_kanji)
            rv.setImageViewBitmap(R.id.img, bmp)
            rv.setViewVisibility(R.id.anim, View.INVISIBLE)
            val animate = PendingIntent.getBroadcast(
                c, 20, Intent(c, KanjiWidget::class.java).setAction(ACTION_ANIMATE), FLAGS)
            val level = PendingIntent.getBroadcast(
                c, 21, Intent(c, KanjiWidget::class.java).setAction(ACTION_LEVEL), FLAGS)
            rv.setOnClickPendingIntent(R.id.zone_top, animate)
            rv.setOnClickPendingIntent(R.id.zone_main, animate)
            rv.setOnClickPendingIntent(R.id.zone_level, level)
            m.updateAppWidget(id, rv)
        }

        // ---------------------------------------------------------------- static card
        private fun Canvas.drawKanji(ctx: Context, w: Int, h: Int, day: KanjiDay?) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val u = minOf(w, h) / 100f
            val pad = 5f * u
            drawColor(p.surface)

            // glyph box: centre (50%, 37%), side 38% of the short edge. The overlay in the layout matches it.
            val s = 0.38f * minOf(w, h)
            val cx = 0.5f * w
            val cy = 0.37f * h
            if (day == null) {
                label("…", cx, baselineFor(cy, s * 0.5f, tf), s * 0.5f, p.faint, tf, Paint.Align.CENTER)
                return
            }

            val hs = 6.6f * u
            val hv = Fonts.axes(wght = 380, wdth = 105)
            val grade = if (day.grade > 0) day.grade.toString() else "–"
            label("${day.strokes}S—($grade)", 0.20f * w, 0.6f * pad + hs, hs, p.muted, tf, fvar = hv)
            label("N${day.level}", 0.75f * w, 0.6f * pad + hs, hs, p.muted, tf, fvar = hv)

            val gtf = Typeface.create(Typeface.SANS_SERIF, 700, false)
            val gs = s * 0.98f
            label(day.kanji, cx, baselineFor(cy, gs, gtf), gs, p.tertiary, gtf, Paint.Align.CENTER)

            // meanings flank the glyph: 1st, 3rd.. stack on the left; 2nd, 4th.. on the right (as in the KWGT version).
            // Long ones are condensed with the width axis rather than shrunk.
            val ms = 8f * u
            val leftM = day.meanings.filterIndexed { i, _ -> i % 2 == 0 }.take(2)
            val rightM = day.meanings.filterIndexed { i, _ -> i % 2 == 1 }.take(2)
            leftM.forEachIndexed { i, t ->
                val y = 0.465f * h + (i - (leftM.size - 1) / 2f) * 0.11f * h
                val (sz, fv) = fitByAxis(t, 0.305f * w - pad, ms, tf, wght = 400, wdthMax = 100)
                label(t, 0.305f * w, y, sz, p.accent, tf, Paint.Align.RIGHT, fvar = fv)
            }
            rightM.forEachIndexed { i, t ->
                val y = 0.34f * h + i * 0.11f * h
                val (sz, fv) = fitByAxis(t, w - 0.685f * w - pad, ms, tf, wght = 400, wdthMax = 100)
                label(t, 0.685f * w, y, sz, p.accent, tf, fvar = fv)
            }

            // readings: wrap onto a second line at full size instead of shrinking
            val lineY = 0.70f * h
            val colW = 0.43f * w
            val x1 = pad
            val x2 = w - pad - colW
            hairline(x1, lineY, x1 + colW, lineY, p.faint, 0.35f * u)
            hairline(x2, lineY, x2 + colW, lineY, p.faint, 0.35f * u)
            val ls = 4.2f * u
            label("ON’YOMI", x1, lineY + 1.4f * u + ls, ls, p.muted, Fonts.MONO, tracking = 0.04f)
            label("KUN’YOMI", x2, lineY + 1.4f * u + ls, ls, p.muted, Fonts.MONO, tracking = 0.04f)
            val rs = 5.7f * u
            val ry = lineY + 13.8f * u
            for ((x, list) in listOf(x1 to day.on, x2 to day.kun)) {
                val wr = wrapFit(list.take(4).joinToString("、").ifEmpty { "—" }, colW, 2, rs, tf)
                wr.lines.forEachIndexed { i, l -> label(l, x, ry + i * wr.size * 1.18f, wr.size, p.accent, tf, fvar = wr.fvar) }
            }
        }

        // ---------------------------------------------------------------- stroke animation
        /**
         * Widgets can't run their own animations, so we push frames: each frame is a small bitmap sent
         * with a partial update (only the overlay ImageView changes). Four sub-steps per stroke,
         * scaled so the whole thing fits inside the ~10 s broadcast budget.
         */
        private fun runAnimation(c: Context) {
            val day = KanjiRepo.cached(c) ?: return
            val strokes = KanjiVg.load(c, day.kanji, allowNetwork = true) ?: return
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, KanjiWidget::class.java))
            if (ids.isEmpty()) return

            val p = Palette.of(c)
            val sub = 4
            val step = (6500L / (strokes.size * sub)).coerceIn(50L, 140L)
            val measures = strokes.map { PathMeasure(it, false) }
            for (i in strokes.indices) for (k in 1..sub) {
                if (k == 1) Haptics.tick(c)          // one tick per stroke
                val bmp = frame(strokes, measures, i, k / sub.toFloat(), 360, p)
                val rv = RemoteViews(c.packageName, R.layout.widget_kanji)
                rv.setViewVisibility(R.id.anim, View.VISIBLE)
                rv.setImageViewBitmap(R.id.anim, bmp)
                m.partiallyUpdateAppWidget(ids, rv)
                Thread.sleep(step)
            }
            Thread.sleep(900)    // hold the finished kanji briefly
        }

        /** Ghost of the full kanji, finished strokes solid, current stroke drawn up to [frac]. */
        private fun frame(
            strokes: List<Path>, measures: List<PathMeasure>, cur: Int, frac: Float, side: Int, p: Palette,
        ): Bitmap {
            val b = Bitmap.createBitmap(side, side, Bitmap.Config.RGB_565)   // opaque: hides the static glyph beneath
            val cv = Canvas(b)
            cv.drawColor(p.surface)
            val k = side / KanjiVg.BOX
            cv.scale(k, k)
            val ghost = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.strokeWidth = 5.5f
                it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND
                it.color = alpha(p.faint, 80)
            }
            val ink = Paint(ghost).also { it.color = p.tertiary }
            strokes.forEach { cv.drawPath(it, ghost) }
            for (j in 0 until cur) cv.drawPath(strokes[j], ink)
            val seg = Path()
            val pm = measures[cur]
            if (pm.getSegment(0f, pm.length * frac, seg, true)) cv.drawPath(seg, ink)
            return b
        }
    }
}
