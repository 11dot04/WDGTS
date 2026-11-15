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

        val art = if (s.imageUrl != null) WikiRepo.image(ctx) else null
        val fg = if (art != null) WikiRepo.cutout(ctx, s) else null
        var dst: RectF? = null
        if (art != null) {
            val k = maxOf(w / art.width.toFloat(), h / art.height.toFloat())
            val dw = art.width * k
            val dh = art.height * k
            dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            val grey = Paint(Paint.FILTER_BITMAP_FLAG).also {
                it.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            drawBitmap(art, null, dst, grey)
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().also { it.color = alpha(p.surface, 150) })
        }

        // kicker: the article's own one-line description
        val ks = 3.8f * u
        val mv = Fonts.monoW(450)
        val kick = ellipsize(s.desc.ifEmpty { "Featured article" }.uppercase(), w - 2 * m, ks, mono)
        inkLabel(kick, m, m + ks, ks, p.muted, mono, tracking = 0.06f, fvar = mv)

        // headline: as large as the box allows, up to four lines, tight lead
        val top = m + ks + 5f * u
        val availH = 0.80f * h - top
        var size = 15f * u
        var fit = fitLines(s.title, w - 2 * m, 4, size, tf, wght = 300, minWdth = 80)
        var tries = 0
        while (fit.lines.size * fit.size * 0.98f > availH && tries++ < 10) {
            size *= 0.92f
            fit = fitLines(s.title, w - 2 * m, 4, size, tf, wght = 300, minWdth = 80)
        }
        fit.lines.forEachIndexed { i, l ->
            inkLabel(l, m, top + fit.size * 0.78f + i * fit.size * 0.98f, fit.size, p.accent, tf, fvar = fit.fvar)
        }

        // the subject, in front of the type
        if (fg != null && dst != null) drawBitmap(fg, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

        // foot
        val fy = h - m
        hairline(m, fy - 6.6f * u, w - m, fy - 6.6f * u, alpha(p.muted, 160), 0.35f * u)
        inkLabel("WIKIPEDIA", m, fy, ks, p.muted, mono, tracking = 0.06f, fvar = mv)
        inkLabel("FEATURED", w - m, fy, ks, p.muted, mono, Paint.Align.RIGHT, tracking = 0.06f, fvar = mv)
    }

    companion object {
        private const val ACTION_SHUFFLE = "com.wdgts.WIKI_SHUFFLE"

        fun refreshBoth(c: Context) {
            WikiWidget().refreshAll(c)
            WikiBodyWidget().refreshAll(c)
        }

        /** Fetch (off the main thread), redraw, then build the cutout on its own thread and redraw again. */
        fun sync(c: Context, reroll: Boolean) {
            val s = WikiRepo.ensure(c, reroll)
            refreshBoth(c)
            if (s != null && WikiRepo.needsCutout(c, s)) {
                thread(name = "wdgts-cutout") {
                    WikiRepo.buildCutout(c, s)
                    refreshBoth(c)
                }
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
