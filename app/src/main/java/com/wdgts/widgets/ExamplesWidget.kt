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

        /**
         * Margin [mx] on both sides. Everything on the left (index, reading, word) shares one ink edge; everything on
         * the right (badge, gloss, dots) shares the other. The index and the badge share one centre line.
         */
        private fun Canvas.drawExample(ctx: Context, w: Int, h: Int, day: KanjiDay?, idx: Int) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val mono = Fonts.geist(ctx)
            val u = minOf(w, h) / 100f
            drawColor(p.surface)

            val ex = day?.examples?.getOrNull(idx)
            if (day == null || ex == null) {
                label("…", 0.5f * w, baselineFor(0.5f * h, 14f * u, tf), 14f * u, p.faint, tf, Paint.Align.CENTER)
                return
            }
            val mx = 0.075f * w
            val right = w - mx
            val hy = 0.125f * h                       // centre line of the header row

            val ns = 6.6f * u
            inkLabel("[${idx + 1}]", mx, hy + 0.36f * ns, ns, p.accent, mono, fvar = Fonts.monoW(500))

            // M3 Expressive badge: a different shape for every kanji (bun, gem, cookie, clover...)
            val br = 7.5f * u
            drawShape(M3Shapes.pick(day.kanji.codePointAt(0).toLong()), right - br, hy, br * 2f, p.accent)
            val ks = br * 1.05f
            label(day.kanji, right - br, baselineFor(hy, ks, tf), ks, p.surface, tf, Paint.Align.CENTER)

            // the word keeps its size: the width axis condenses it before the size ever drops
            val (ws, wv) = fitByAxis(ex.written, w - 2 * mx, 18.5f * u, tf, wght = 300, wdthMax = 100)
            inkLabel(ex.written, mx, 0.545f * h, ws, p.accent, tf, fvar = wv)

            // furigana sit over the kanji they read, not in a row at the left
            val rs = 6.4f * u
            val rv = Fonts.axes(wght = 400, wdth = 100)
            var lastEnd = mx
            for (pc in rubyPieces(ex.written, ex.reading)) {
                val x0 = mx + measureText(ex.written.substring(0, pc.from), ws, tf, 0f, wv)
                val x1 = mx + measureText(ex.written.substring(0, pc.to), ws, tf, 0f, wv)
                val rw = measureText(pc.text, rs, tf, 0f, rv)
                val left = ((x0 + x1) / 2f - rw / 2f).coerceAtLeast(lastEnd + 0.5f * rs).coerceAtMost(right - rw)
                inkLabel(pc.text, left, 0.345f * h, rs, p.muted, tf, fvar = rv)
                lastEnd = left + rw
            }

            // gloss: right-aligned on the same edge as the badge, wraps onto up to three lines at full size
            val g = fitLines(ex.gloss, 0.80f * w, 3, 8f * u, tf, wght = 400)
            g.lines.forEachIndexed { i, l ->
                inkLabel(l, right, 0.655f * h + i * g.size * 1.2f, g.size, p.accent, tf, Paint.Align.RIGHT, fvar = g.fvar)
            }

            // pager dots, the right one on the right edge too
            val dr = 2.1f * u
            val xr = right - dr
            val xl = xr - 10.2f * u
            dot(xl, 0.885f * h, dr, p.tertiary)
            dot(xr, 0.885f * h, dr, p.tertiary)
            dot((xl + xr) / 2f, 0.937f * h, dr, p.tertiary)
        }

        /** A piece of reading and the span [from, to) of the written word it sits over. */
        private class Ruby(val text: String, val from: Int, val to: Int)

        private fun isKanji(c: Char) = c in '\u4E00'..'\u9FFF' || c == '々' || c in '\u3400'..'\u4DBF'
        private fun hira(c: Char) = if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar() else c
        private fun isSmall(c: Char) = c in "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

        /**
         * Splits the reading over the word's kanji. Kana inside the word (okurigana) anchor the match; each run of
         * kanji gets what lies between. A run of several kanji is split per kanji when its reading divides evenly
         * into morae (賦課 ふ|か, 憂鬱 ゆう|うつ), otherwise one ruby is centred over the whole run.
         * If the reading can't be matched, one ruby goes over the whole word.
         */
        private fun rubyPieces(written: String, reading: String): List<Ruby> {
            val rd = reading.map { hira(it) }.joinToString("")
            val runs = mutableListOf<Triple<Int, Int, Boolean>>()          // from, to, isKanjiRun
            var i = 0
            while (i < written.length) {
                val k = isKanji(written[i]); var j = i
                while (j < written.length && isKanji(written[j]) == k) j++
                runs += Triple(i, j, k); i = j
            }
            val re = Regex(runs.joinToString("", "^", "$") { (a, b, k) ->
                if (k) "(.+?)" else Regex.escape(written.substring(a, b).map { hira(it) }.joinToString(""))
            })
            val m = re.matchEntire(rd) ?: return if (runs.any { it.third }) {
                val first = runs.first { it.third }.first
                val last = runs.last { it.third }.second
                listOf(Ruby(reading, first, last))
            } else emptyList()
            val out = mutableListOf<Ruby>()
            var g = 1
            for ((a, b, k) in runs) {
                if (!k) continue
                val txt = m.groupValues[g++]
                val n = b - a
                val morae = txt.indices.filter { it == 0 || !isSmall(txt[it]) }
                if (n > 1 && morae.size % n == 0) {
                    val per = morae.size / n
                    for (q in 0 until n) {
                        val s0 = morae[q * per]
                        val e0 = if ((q + 1) * per < morae.size) morae[(q + 1) * per] else txt.length
                        out += Ruby(txt.substring(s0, e0), a + q, a + q + 1)
                    }
                } else out += Ruby(txt, a, b)
            }
            return out
        }
    }
}
