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
        /**
         * One grid: a single margin [m] on both sides, a centre axis through the glyph and the gutter between the
         * reading columns. Header, caption and readings hang off the margin; the two main meanings flank the glyph.
         */
        private fun Canvas.drawKanji(ctx: Context, w: Int, h: Int, day: KanjiDay?) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val mono = Fonts.geist(ctx)
            val u = minOf(w, h) / 100f
            val m = 6f * u
            drawColor(p.surface)

            // glyph box: centre (50%, 37%), side 38% of the short edge. The overlay in the layout matches it.
            val s = 0.38f * minOf(w, h)
            val cx = 0.5f * w
            val cy = 0.37f * h
            if (day == null) {
                label("…", cx, baselineFor(cy, s * 0.5f, tf), s * 0.5f, p.faint, tf, Paint.Align.CENTER)
                return
            }

            // header: strokes and school grade left, JLPT level right (the tap zone). No rule: the type is the margin.
            val hs = 6.2f * u
            val hv = Fonts.axes(wght = 380, wdth = 105)
            val grade = if (day.grade > 0) day.grade.toString() else "–"
            inkLabel("${day.strokes}S—($grade)", m, 0.104f * h, hs, p.muted, tf, fvar = hv)
            inkLabel("N${day.level}", w - m, 0.104f * h, hs, p.muted, tf, Paint.Align.RIGHT, fvar = hv)

            // The glyph and the meanings go on separate layers and are combined with XOR, so where a long meaning
            // runs into the kanji the overlap is knocked out and both stay readable.
            val glyphLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val textLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val gtf = Typeface.create(Typeface.SANS_SERIF, 700, false)
            val gs = s * 0.98f
            Canvas(glyphLayer).label(day.kanji, cx, baselineFor(cy, gs, gtf), gs, p.tertiary, gtf, Paint.Align.CENTER)

            // Seven slots, zig-zagging down five rows: right-aligned ones hang off the right margin, left-aligned
            // ones off the left. Rows 1 and 5 are free on both sides, row 3 sits beside the glyph, and rows 2 and 4
            // (slots 2 and 5) have nobody opposite, so they are the wide ones and take the long meanings.
            val rowY = floatArrayOf(0.235f, 0.340f, 0.445f, 0.550f, 0.655f)
            val slotRow = intArrayOf(0, 1, 2, 2, 3, 4, 4)
            val slotRight = booleanArrayOf(true, false, false, true, true, false, true)
            val wide = intArrayOf(1, 4)
            val half = (w - 2 * m) / 2f - 2f * u
            val beside = (cx - s / 2f + 0.12f * s) - m
            val across = (cx + 0.10f * s) - m
            fun cap(slot: Int) = when (slotRow[slot]) { 2 -> beside; 1, 3 -> across; else -> half }
            val ms = 7.4f * u
            val mv = Fonts.axes(wght = 400, wdth = 100)
            val meanings = day.meanings.take(7).toMutableList()
            fun nat(t: String) = measureText(t, ms, tf, 0f, mv)
            // long meanings swap into the wide slots
            val isLong = { i: Int -> i !in wide && nat(meanings[i]) * 0.78f > cap(i) }
            val longs = meanings.indices.filter(isLong).sortedByDescending { nat(meanings[it]) }
            val free = wide.filter { it < meanings.size }.toMutableList()
            for (i in longs) {
                val j = free.firstOrNull { nat(meanings[it]) < nat(meanings[i]) } ?: continue
                free.remove(j)
                val t = meanings[i]; meanings[i] = meanings[j]; meanings[j] = t
            }
            val tc = Canvas(textLayer)
            meanings.forEachIndexed { i, t ->
                val (size, fv) = fitByAxis(t, cap(i), ms, tf, wght = 400, wdthMax = 100)
                if (slotRight[i]) tc.inkLabel(t, w - m, rowY[slotRow[i]] * h, size, p.accent, tf, Paint.Align.RIGHT, fvar = fv)
                else tc.inkLabel(t, m, rowY[slotRow[i]] * h, size, p.accent, tf, fvar = fv)
            }
            Canvas(glyphLayer).drawBitmap(textLayer, 0f, 0f, Paint().also {
                it.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.XOR)
            })
            drawBitmap(glyphLayer, 0f, 0f, null)
            glyphLayer.recycle(); textLayer.recycle()

            // readings: two columns that share the margin and the centre axis, wrapping instead of shrinking
            val gut = 5f * u
            val colW = (w - 2 * m - gut) / 2f
            val x1 = m
            val x2 = m + colW + gut
            val lineY = 0.715f * h
            hairline(x1, lineY, x1 + colW, lineY, p.faint, 0.35f * u)
            hairline(x2, lineY, x2 + colW, lineY, p.faint, 0.35f * u)
            val ls = 3.8f * u
            inkLabel("ON’YOMI", x1, lineY + 1.4f * u + ls, ls, p.muted, mono, tracking = 0.06f, fvar = Fonts.monoW(450))
            inkLabel("KUN’YOMI", x2, lineY + 1.4f * u + ls, ls, p.muted, mono, tracking = 0.06f, fvar = Fonts.monoW(450))
            val rs = 6.2f * u
            val ry = lineY + 13.4f * u
            for ((x, list) in listOf(x1 to day.on, x2 to day.kun)) {
                val f = fitLines(list.take(4).joinToString("、").ifEmpty { "—" }, colW, 2, rs, tf)
                f.lines.forEachIndexed { i, l -> inkLabel(l, x, ry + i * f.size * 1.2f, f.size, p.accent, tf, fvar = f.fvar) }
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
