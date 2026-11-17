package com.wdgts.wiki

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

    // ------------------------------------------------------------------ layout
    /** The subject's footprint on a coarse grid, in 0..1 coordinates of the image. */
    private class Subject(val mask: BooleanArray, val mw: Int, val mh: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun hit(u: Float, v: Float): Boolean {
            if (u < 0f || v < 0f || u >= 1f || v >= 1f) return false
            return mask[(v * mh).toInt() * mw + (u * mw).toInt()]
        }
    }

    private fun subjectOf(fg: Bitmap): Subject? {
        val mw = 48
        val mh = (48f * fg.height / fg.width).toInt().coerceIn(8, 128)
        val px = IntArray(mw * mh)
        Bitmap.createScaledBitmap(fg, mw, mh, true).getPixels(px, 0, mw, 0, 0, mw, mh)
        val mask = BooleanArray(mw * mh) { (px[it] ushr 24) > 90 }
        var x0 = mw; var x1 = -1; var y0 = mh; var y1 = -1
        for (y in 0 until mh) for (x in 0 until mw) if (mask[y * mw + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        return Subject(mask, mw, mh, x0 / mw.toFloat(), y0 / mh.toFloat(), (x1 + 1) / mw.toFloat(), (y1 + 1) / mh.toFloat())
    }

    /** Where the image sits: scale [k] and the top-left of the scaled image in widget pixels. */
    private class Place(val kf: Float, val k: Float, val ox: Float, val oy: Float, val dw: Float, val dh: Float)

    private fun places(aw: Int, ah: Int, w: Int, h: Int): List<Place> {
        val k0 = maxOf(w / aw.toFloat(), h / ah.toFloat())
        val out = ArrayList<Place>()
        for (kf in floatArrayOf(1f, 0.92f, 0.85f)) {
            val k = k0 * kf
            val dw = aw * k
            val dh = ah * k
            val xs = span(w - dw)
            val ys = span(h - dh)
            for (x in xs) for (y in ys) out += Place(kf, k, x, y, dw, dh)
        }
        return out
    }

    /** Five offsets between 0 and [d] (d is negative when the image is larger than the box, positive when smaller). */
    private fun span(d: Float): List<Float> = List(5) { d * it / 4f }

    /** Title and subtitle at one shared size: the largest at which both fit their boxes. */
    private class Block(val title: Fit, val sub: Fit?, val size: Float)

    private fun Canvas.solveBlock(
        title: String, sub: String, wT: Float, wS: Float, availH: Float, tf: Typeface, start: Float,
    ): Block {
        var s = start
        var last: Block? = null
        for (i in 0 until 14) {
            val t = fitLines(title, wT, 4, s, tf, wght = 560, minWdth = 80)
            val b = if (sub.isEmpty()) null else fitLines(sub, wS, 2, s, tf, wght = 260, minWdth = 80)
            val sz = minOf(t.size, b?.size ?: t.size)
            val same = sz >= s * 0.985f
            val tl = if (same) t else fitLines(title, wT, 4, sz, tf, wght = 560, minWdth = 80)
            val bl = if (b == null) null else if (same) b else fitLines(sub, wS, 2, sz, tf, wght = 260, minWdth = 80)
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

        // header row: the old footer's words, now where the subtitle used to be
        val ks = 3.8f * u
        val mv = Fonts.monoW(450)
        inkLabel("WIKIPEDIA", m, m + 0.72f * ks, ks, p.muted, mono, tracking = 0.06f, fvar = mv)
        inkLabel("FEATURED", w - m, m + 0.72f * ks, ks, p.muted, mono, Paint.Align.RIGHT, tracking = 0.06f, fvar = mv)

        val art = if (st.imageUrl != null) WikiRepo.image(ctx) else null
        val fg = if (art != null) WikiRepo.cutout(ctx, st) else null
        val subj = fg?.let { subjectOf(it) }

        val sub = st.desc.replace(Regex("\\s*\\([^)]*\\)"), "").substringBefore(';').trim()
            .replaceFirstChar { it.uppercase() }
        val top = m + ks + 5f * u
        val bottom = h - m
        val avail = bottom - top - 3f * u
        val fullW = w - 2 * m
        val full = solveBlock(st.title, sub, fullW, fullW, avail, tf, 15f * u)

        // ---- decide the placement of the image (and whether the subject comes out in front of the type)
        var place: Place? = null
        var narrow: Block? = null
        var cutOut = false
        if (art != null) {
            val cands = places(art.width, art.height, w, h)
            if (subj == null) {
                place = cands.first { it.kf == 1f && Math.abs(it.ox - (w - it.dw) / 2f) < 1f && Math.abs(it.oy - (h - it.dh) / 2f) < 1f }
            } else {
                fun out(c: Place): Float {
                    val bx0 = c.ox + subj.x0 * c.dw; val bx1 = c.ox + subj.x1 * c.dw
                    val by0 = c.oy + subj.y0 * c.dh; val by1 = c.oy + subj.y1 * c.dh
                    val o = maxOf(0f, -bx0) + maxOf(0f, bx1 - w) + maxOf(0f, -by0) + maxOf(0f, by1 - h)
                    return o / w
                }
                // title ink rows, to count how much of them the subject would cover
                fun overlap(c: Place, b: Block): Float {
                    var n = 0; var tot = 0
                    b.title.lines.forEachIndexed { i, l ->
                        val lw = measureText(l, b.size, tf, 0f, b.title.fvar)
                        val ly = top + b.size * 0.78f + i * b.size * 0.98f
                        for (xi in 0 until 14) for (yi in 0 until 3) {
                            val px = m + lw * (xi + 0.5f) / 14f
                            val py = ly - b.size * 0.62f + b.size * 0.62f * (yi + 0.5f) / 3f
                            tot++
                            if (subj.hit((px - c.ox) / c.dw, (py - c.oy) / c.dh)) n++
                        }
                    }
                    return if (tot == 0) 0f else n / tot.toFloat()
                }
                // free column left of the subject, for a narrower title when the photo sits right
                var best = 9f; var bestPlace: Place? = null; var bestBlock = full
                for (c in cands) {
                    val base = out(c) * 3f + (1f - c.kf) * 0.5f
                    val o1 = overlap(c, full)
                    if (base + o1 < best) { best = base + o1; bestPlace = c; bestBlock = full }
                    val free = c.ox + subj.x0 * c.dw - m - 2f * u
                    if (free > 0.42f * fullW && free < fullW) {
                        val nb = solveBlock(st.title, sub, free, fullW, avail, tf, 15f * u)
                        val o2 = overlap(c, nb) + (1f - nb.size / full.size).coerceAtLeast(0f) * 0.3f
                        if (base + o2 < best) { best = base + o2; bestPlace = c; bestBlock = nb }
                    }
                }
                val bp = bestPlace!!
                if (best - out(bp) * 3f - (1f - bp.kf) * 0.5f <= 0.10f && out(bp) < 0.02f) {
                    cutOut = true; place = bp; narrow = bestBlock
                } else {
                    // too much overlap: no cutout, just the subject centred and inside the box
                    place = cands.minByOrNull { c ->
                        val cx = c.ox + (subj.x0 + subj.x1) / 2f * c.dw
                        val cy = c.oy + (subj.y0 + subj.y1) / 2f * c.dh
                        out(c) * 3f + (1f - c.kf) * 0.5f + (Math.abs(cx - w / 2f) + Math.abs(cy - h / 2f)) / w * 0.4f
                    }!!
                }
            }
        }

        // ---- image: grey and dimmed; if it does not cover the box, the cover version is the underlay and the
        // placed one fades into it
        place?.let { pl ->
            val grey = Paint(Paint.FILTER_BITMAP_FLAG).also {
                it.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            val kc = maxOf(w / art!!.width.toFloat(), h / art.height.toFloat())
            val cw = art.width * kc
            val ch = art.height * kc
            if (pl.dw < w - 1f || pl.dh < h - 1f) {
                drawBitmap(art, null, RectF((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f), grey)
            }
            val dst = RectF(pl.ox, pl.oy, pl.ox + pl.dw, pl.oy + pl.dh)
            val gaps = pl.dw < w - 1f || pl.dh < h - 1f
            if (gaps) saveLayer(dst, null)
            drawBitmap(art, null, dst, grey)
            if (gaps) {
                val f = 9f * u
                val clear = Paint().also { it.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
                fun band(l: Float, t: Float, r: Float, b: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
                    clear.shader = android.graphics.LinearGradient(x0, y0, x1, y1, 0x00FFFFFF, -1, android.graphics.Shader.TileMode.CLAMP)
                    drawRect(l, t, r, b, clear)
                }
                if (dst.left > 0.5f) band(dst.left, dst.top, dst.left + f, dst.bottom, dst.left, 0f, dst.left + f, 0f)
                if (dst.right < w - 0.5f) band(dst.right - f, dst.top, dst.right, dst.bottom, dst.right, 0f, dst.right - f, 0f)
                if (dst.top > 0.5f) band(dst.left, dst.top, dst.right, dst.top + f, 0f, dst.top, 0f, dst.top + f)
                if (dst.bottom < h - 0.5f) band(dst.left, dst.bottom - f, dst.right, dst.bottom, 0f, dst.bottom, 0f, dst.bottom - f)
                restore()
            }
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().also { it.color = alpha(p.surface, 150) })
        }

        // ---- title (flush left), then the subject in front of it, then the subtitle on top of everything
        val blk = narrow ?: full
        blk.title.lines.forEachIndexed { i, l ->
            inkLabel(l, m, top + blk.size * 0.78f + i * blk.size * 0.98f, blk.size, p.accent, tf, fvar = blk.title.fvar)
        }
        if (cutOut && fg != null && place != null) {
            drawBitmap(fg, null, RectF(place.ox, place.oy, place.ox + place.dw, place.oy + place.dh),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
        blk.sub?.let { sf ->
            val lead = blk.size * 0.98f
            val sp = Paint(textPaint(blk.size, alpha(p.accent, 175), tf, Paint.Align.LEFT, 0f, sf.fvar))
            sp.setShadowLayer(2.2f * u, 0f, 0f, alpha(p.surface, 230))
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
