package com.wdgts.hud

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.hud.engine.GlyphRegistry
import com.wdgts.hud.engine.Hud
import com.wdgts.hud.engine.HudColors
import com.wdgts.hud.engine.HudHits
import com.wdgts.hud.engine.HudRenderer
import com.wdgts.hud.engine.HudSizing
import com.wdgts.hud.engine.IconPainter
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.hud.engine.smartspacer.SmartspacerIcons
import com.wdgts.hud.engine.smartspacer.SsIconKey
import com.wdgts.widgets.GlyphMask
import com.wdgts.widgets.ImageWidget
import com.wdgts.widgets.Sched
import com.wdgts.widgets.async
import java.util.WeakHashMap
import kotlin.math.max

/**
 * The Nothing-clockface HUD. What it says is decided by [HudComposer]; layout, pills and glyphs live in
 * com.wdgts.hud.engine; this class supplies the schedule, colours, tap zones and the icon painter.
 */
class HudWidget : ImageWidget() {
    override val layoutRes: Int = R.layout.widget_hud

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        super.onUpdate(c, m, ids)
        val app = c.applicationContext
        HudSmartspacerSource.start(app) { HudWidget().refreshAll(app) }
        async { Weather.refreshIfStale(c); refreshAll(c) }
        Sched.arm(c, HudWidget::class.java, ACTION_TICK, System.currentTimeMillis() + 5 * 60_000L, 70)   // fallback if the listener isn't running
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_TICK -> { refreshAll(c); Sched.arm(c, HudWidget::class.java, ACTION_TICK, System.currentTimeMillis() + 5 * 60_000L, 70) }
            else -> super.onReceive(c, i)
        }
    }

    /**
     * Tap zones: every line is a row of up to five spans (see [HudHits]) laid over the bitmap, so an arrow pill,
     * a title, a chip and a countdown can each be their own target. A widget cannot report where you touched,
     * so spans are sized in pixels to match the layout; everything is anchored to the top.
     */
    override fun bind(ctx: Context, rv: RemoteViews) {
        val g = HudGeometry.of(ctx)
        val slots = g.size.bodyLines
        val content = HudComposer.compose(ctx, slots)
        val laid = renderer(ctx).lay(g.widthPx, g.heightPx, content)
        val pad = Hud.PAD_H * laid.unit
        rv.setViewLayoutMargin(R.id.zone_l1, RemoteViews.MARGIN_TOP, g.topPx, TypedValue.COMPLEX_UNIT_PX)
        for (i in 0 until Hud.MAX_LINES) {
            val lineId = id(ctx, "zone_l${i + 1}")
            if (i >= g.size.lines) {
                rv.setViewVisibility(lineId, View.GONE)
                continue
            }
            rv.setViewVisibility(lineId, View.VISIBLE)
            rv.setViewLayoutHeight(lineId, g.pitchPx, TypedValue.COMPLEX_UNIT_PX)
            val line = laid.lines.getOrNull(i) ?: emptyList()
            val spans = HudHits.spans(line, laid.unit, pad, g.widthPx.toFloat())
            for (j in 0 until SPANS) {
                val spanId = id(ctx, "zone_l${i + 1}_s${j + 1}")
                if (j >= spans.size) {
                    rv.setViewVisibility(spanId, View.GONE)
                    continue
                }
                val s = spans[j]
                var tap = s.tap
                if (i == 0 && tap == null) tap = content.headerTap
                rv.setViewVisibility(spanId, View.VISIBLE)
                rv.setViewLayoutWidth(spanId, max(1f, s.x1 - s.x0), TypedValue.COMPLEX_UNIT_PX)
                rv.setOnClickPendingIntent(spanId, HudTapActivity.pending(ctx, tap, 100 + i * 10 + j))
            }
        }
    }

    private fun id(ctx: Context, name: String) = ctx.resources.getIdentifier(name, "id", ctx.packageName)

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        if (CARD) drawColor(p.surface)
        val ink = if (CARD) p.onSurface else Color.WHITE
        val content = HudComposer.compose(ctx, HudSizing.of(w, h).bodyLines)
        renderer(ctx).draw(this, w, h, content, HudColors(ink), iconPainter(ctx))
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverage = WeakHashMap<Bitmap, Float>()

    /** App icons (monochrome layer) and Smartspacer icons, optically sized; tinted, or cut out of a filled pill. */
    private fun iconPainter(ctx: Context) = IconPainter { key, c, x, cy, size, ink, knock ->
        val mask: Bitmap = when (key) {
            is AppIconKey -> GlyphMask.forPackage(ctx, key.pkg)
            is SsIconKey -> SmartspacerIcons.get(key)
            else -> null
        } ?: return@IconPainter
        val cov = coverage.getOrPut(mask) { GlyphMask.coverage(mask) }
        val target = size * GlyphMask.optical(cov).coerceAtMost(1.08f)   // never bigger than the box: thin artwork used to grow up to 22 %
        val k = target / max(mask.width, mask.height)
        val bw = mask.width * k
        val bh = mask.height * k
        val left = x + (size - bw) / 2f
        val top = cy - bh / 2f
        if (knock) {
            iconPaint.colorFilter = null
            iconPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        } else {
            iconPaint.xfermode = null
            iconPaint.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
        }
        c.drawBitmap(mask, null, RectF(left, top, left + bw, top + bh), iconPaint)
        iconPaint.xfermode = null
    }

    companion object {
        const val ACTION_TICK = "com.wdgts.HUD_TICK"
        private const val SPANS = 5

        /** true = draw the palette surface behind the text; false = transparent, white ink (the Nothing look). */
        private const val CARD = false

        /** 'wght' axis for a variable Geist Mono: regular body text, bold text inside pills, like the reference. */
        private const val WEIGHT_BODY = 400
        private const val WEIGHT_PILL = 650

        @Volatile private var cached: HudRenderer? = null

        private fun renderer(ctx: Context): HudRenderer = cached ?: synchronized(this) {
            cached ?: HudRenderer(typeface(ctx), GlyphRegistry(ctx), WEIGHT_BODY, WEIGHT_PILL).also { cached = it }
        }

        /** A Geist Mono file in assets/fonts (any file name containing "geist" and "mono"), else the app's mono font. */
        private fun typeface(ctx: Context): Typeface {
            val files = runCatching { ctx.assets.list("fonts") }.getOrNull().orEmpty()
            val f = files.firstOrNull { it.contains("geist", ignoreCase = true) && it.contains("mono", ignoreCase = true) }
            if (f != null) runCatching { return Typeface.createFromAsset(ctx.assets, "fonts/$f") }
            return Fonts.mono(ctx)
        }
    }
}
