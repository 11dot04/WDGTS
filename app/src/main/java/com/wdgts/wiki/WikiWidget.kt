package com.wdgts.wiki

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.widgets.ImageWidget
import com.wdgts.widgets.async
import kotlin.concurrent.thread

/**
 * Page 1: the headline set big and tight, flush left, over the article's lead image. The image is greyed and
 * dimmed; if ML Kit finds a clear subject, the subject is lifted back out in colour in front of the type.
 * No clear subject: the image just stays behind. Tap = another article.
 */
class WikiWidget : ImageWidget(170) {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        super.onUpdate(c, m, ids)
        async { sync(c, false) }
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_SHUFFLE -> { Haptics.click(c); async { sync(c, true) } }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                async { sync(c, false) }
            else -> super.onReceive(c, i)
        }
    }

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACTION_SHUFFLE, 70))
    }

    /** Title and subtitle at one shared size: the largest at which both fit their boxes. */
    private class Block(val title: Fit, val sub: Fit?, val size: Float)

    private fun Canvas.solveBlock(
        title: String, sub: String, wT: Float, wS: Float, availH: Float, tf: Typeface, start: Float,
    ): Block {
        var s = start
        var last: Block? = null
        for (i in 0 until 14) {
            val t = fitLines(title, wT, 4, s, tf, wght = 640, minWdth = 80)
            val b = if (sub.isEmpty()) null else fitLines(sub, wS, 2, s, tf, wght = 420, minWdth = 80)
            val sz = minOf(t.size, b?.size ?: t.size)
            val same = sz >= s * 0.985f
            val tl = if (same) t else fitLines(title, wT, 4, sz, tf, wght = 640, minWdth = 80)
            val bl = if (b == null) null else if (same) b else fitLines(sub, wS, 2, sz, tf, wght = 420, minWdth = 80)
            val hh = (tl.lines.size + (bl?.lines?.size ?: 0)) * sz * 0.98f + (if (bl != null) 0.35f * sz else 0f)
            last = Block(tl, bl, sz)
            if (same && hh <= availH) return last
            s *= 0.93f
        }
        return last!!
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.geist(ctx)
        val u = minOf(w, h) / 100f
        val m = 6f * u
        drawColor(p.surface)
        val st = WikiRepo.cached(ctx)
        if (st == null) {
            label("W", w / 2f, baselineFor(h / 2f, 30f * u, tf), 30f * u, p.faint, tf, Paint.Align.CENTER,
                fvar = Fonts.axes(wght = 240))
            return
        }

        val art = if (st.imageUrl != null) WikiRepo.image(ctx) else null
        val fg = if (art != null) WikiRepo.cutout(ctx, st) else null
        val subj = fg?.let { SubjectFit.subjectOf(it) }

        val ks = 3.8f * u
        val sub = st.desc.replace(Regex("\\s*\\([^)]*\\)"), "").substringBefore(';').trim()
            .replaceFirstChar { it.uppercase() }
        val top = m + ks + 5f * u
        val bottom = h - m
        val avail = bottom - top - 3f * u
        val fullW = w - 2 * m
        val full = solveBlock(st.title, sub, fullW, fullW, avail, tf, 15f * u)

        // rows of title ink, as rectangles, for the overlap test
        fun rects(b: Block): List<RectF> = b.title.lines.mapIndexed { i, l ->
            val lw = measureText(l, b.size, tf, 0f, b.title.fvar)
            val ly = top + b.size * 0.78f + i * b.size * 0.98f
            RectF(m, ly - b.size * 0.62f, m + lw, ly)
        }

        // ---- decide the placement of the image (and whether the subject comes out in front of the type)
        var place: SubjectFit.Place? = null
        var narrow: Block? = null
        var cutOut = false
        if (art != null) {
            val cands = SubjectFit.places(art.width, art.height, w, h)
            if (subj == null) {
                place = cands.first { it.kf == 1f && Math.abs(it.ox - (w - it.dw) / 2f) < 1f && Math.abs(it.oy - (h - it.dh) / 2f) < 1f }
            } else {
                var best = 9f; var bestPlace: SubjectFit.Place? = null; var bestBlock = full; var bestOv = 1f
                for (c in cands) {
                    val base = SubjectFit.cost(c, subj, w, h)
                    val o1 = SubjectFit.covered(c, subj, rects(full))
                    if (base + o1 < best) { best = base + o1; bestPlace = c; bestBlock = full; bestOv = o1 }
                    // a narrower title in the free column left of the subject
                    val free = c.ox + subj.x0 * c.dw - m - 2f * u
                    if (free > 0.42f * fullW && free < fullW) {
                        val nb = solveBlock(st.title, sub, free, fullW, avail, tf, 15f * u)
                        val o2 = SubjectFit.covered(c, subj, rects(nb))
                        val sc = base + o2 + (1f - nb.size / full.size).coerceAtLeast(0f) * 0.3f
                        if (sc < best) { best = sc; bestPlace = c; bestBlock = nb; bestOv = o2 }
                    }
                }
                val bp = bestPlace!!
                if (bestOv <= 0.10f && SubjectFit.outside(bp, subj, w, h) < 0.02f) {
                    cutOut = true; place = bp; narrow = bestBlock
                } else {
                    place = SubjectFit.centred(cands, subj, w, h)        // too much overlap: no cutout, subject just centred
                }
            }
        }

        // ---- image: grey and dimmed (the scrim follows the theme, so light mode stays light)
        if (art != null && place != null) {
            with(SubjectFit) { drawPlaced(art, place, w, h, true, 9f * u) }
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().also { it.color = alpha(p.surface, 165) })
        }

        // ---- header row: drawn on top of the image, so it is always there
        val mv = Fonts.monoW(500)
        inkLabel("WIKIPEDIA", m, m + 0.72f * ks, ks, p.muted, mono, tracking = 0.06f, fvar = mv)
        inkLabel("FEATURED", w - m, m + 0.72f * ks, ks, p.muted, mono, Paint.Align.RIGHT, tracking = 0.06f, fvar = mv)

        // ---- title (flush left), then the subject in front of it, then the subtitle on top of everything
        val blk = narrow ?: full
        blk.title.lines.forEachIndexed { i, l ->
            inkLabel(l, m, top + blk.size * 0.78f + i * blk.size * 0.98f, blk.size, p.accent, tf, fvar = blk.title.fvar)
        }
        if (cutOut && fg != null && place != null) {
            drawBitmap(fg, null, place.rect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        blk.sub?.let { sf ->
            val lead = blk.size * 0.98f
            val sp = Paint(textPaint(blk.size, alpha(p.onSurface, 205), tf, Paint.Align.LEFT, 0f, sf.fvar))
            sp.setShadowLayer(2.4f * u, 0f, 0f, alpha(p.surface, 235))
            val r = android.graphics.Rect()
            sf.lines.forEachIndexed { i, l ->
                val y = bottom - (sf.lines.size - 1 - i) * lead
                sp.getTextBounds(l, 0, l.length, r)
                drawText(l, w - m - r.right, y, sp)
            }
        }
    }

    companion object {
        private const val ACTION_SHUFFLE = "com.wdgts.WIKI_SHUFFLE"

        fun refreshBoth(c: Context) {
            WikiWidget().refreshAll(c)
            WikiBodyWidget().refreshAll(c)
        }

        /**
         * Fetch (off the main thread). The article only goes on the widget once its cutout has been tried, so the
         * layout is decided once and never jumps; the previous article stays up until then.
         */
        fun sync(c: Context, reroll: Boolean) {
            val n = WikiRepo.fetch(c, reroll)
            if (n == null) { refreshBoth(c); return }
            val needs = n.story.imageUrl != null && WikiRepo.needsCutout(c, n.story)
            if (!needs) { WikiRepo.commit(c, n); refreshBoth(c); return }
            thread(name = "wdgts-cutout") {
                WikiRepo.buildCutout(c, n.story, staged = n.staged)
                WikiRepo.commit(c, n)
                refreshBoth(c)
            }
        }
    }
}

/**
 * Page 2: the article's opening, set as one column of plain text at the largest size that fits.
 * Tap = open the article.
 */
class WikiBodyWidget : ImageWidget(170) {

    override fun bind(ctx: Context, rv: RemoteViews) {
        val url = WikiRepo.cached(ctx)?.pageUrl ?: "https://en.wikipedia.org"
        rv.setOnClickPendingIntent(
            R.id.zone_main,
            PendingIntent.getActivity(
                ctx, 71, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val mono = Fonts.geist(ctx)
        val u = minOf(w, h) / 100f
        val m = 6f * u
        drawColor(p.surface)
        val s = WikiRepo.cached(ctx)
        if (s == null) {
            label("W", w / 2f, baselineFor(h / 2f, 30f * u, tf), 30f * u, p.faint, tf, Paint.Align.CENTER,
                fvar = Fonts.axes(wght = 240))
            return
        }
        val ks = 3.8f * u
        val mv = Fonts.monoW(450)
        inkLabel(ellipsize(s.title.uppercase(), w - 2 * m - 18f * u, ks, mono), m, m + ks, ks, p.muted, mono,
            tracking = 0.06f, fvar = mv)
        inkLabel("OPEN ↗", w - m, m + ks, ks, p.muted, mono, Paint.Align.RIGHT, tracking = 0.06f, fvar = mv)

        val top = m + ks + 6f * u
        val availH = h - m - top
        val maxW = w - 2 * m
        val fv = Fonts.axes(wght = 400, wdth = 100)
        var size = 7.6f * u
        var lines = wrapLines(s.extract, maxW, size, tf, fv)
        while (lines.size * size * 1.3f > availH && size > 4.6f * u) {
            size *= 0.94f
            lines = wrapLines(s.extract, maxW, size, tf, fv)
        }
        val fit = (availH / (size * 1.3f)).toInt().coerceAtLeast(1)
        val shown = lines.take(fit).toMutableList()
        if (lines.size > fit) {
            var t = shown.last().trimEnd('.', ',', ';', ' ')
            while (t.length > 1 && measureText("$t…", size, tf, 0f, fv) > maxW) t = t.dropLast(1)
            shown[shown.lastIndex] = "$t…"
        }
        shown.forEachIndexed { i, l ->
            inkLabel(l, m, top + size + i * size * 1.3f, size, p.accent, tf, fvar = fv)
        }
    }
}
