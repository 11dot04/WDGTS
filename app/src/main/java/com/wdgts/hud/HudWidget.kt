package com.wdgts.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.provider.CallLog
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.SettingsActivity
import com.wdgts.core.*
import com.wdgts.hud.engine.GlyphRegistry
import com.wdgts.hud.engine.HudColors
import com.wdgts.hud.engine.HudRenderer
import com.wdgts.hud.engine.HudSizing
import com.wdgts.hud.engine.IconPainter
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.widgets.GlyphMask
import com.wdgts.widgets.ImageWidget
import com.wdgts.widgets.Sched
import com.wdgts.widgets.async
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * The Nothing-clockface HUD: four lines, the first is the date, the other three go to whatever is most relevant
 * (see [HudComposer]). Layout, pills, bubbles and glyphs live in com.wdgts.hud.engine; this class supplies the
 * schedule, colours, tap intents and the app-icon painter.
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
            ACTION_DISMISS -> { Haptics.tick(c); HudSignals.lookDismissedAt = System.currentTimeMillis(); refreshAll(c) }
            ACTION_TICK -> { refreshAll(c); Sched.arm(c, HudWidget::class.java, ACTION_TICK, System.currentTimeMillis() + 5 * 60_000L, 70) }
            else -> super.onReceive(c, i)
        }
    }

    private val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun launch(c: Context, pkg: String, code: Int): PendingIntent? =
        c.packageManager.getLaunchIntentForPackage(pkg)?.let { PendingIntent.getActivity(c, code, it, FLAGS) }

    private fun local(c: Context, action: String, code: Int): PendingIntent {
        val settings = PendingIntent.getActivity(c, code, Intent(c, SettingsActivity::class.java), FLAGS)
        return when {
            action == HudSources.CALLS ->
                PendingIntent.getActivity(c, code, Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE), FLAGS)
            action == HudSources.DISMISS_LOOK -> broadcast(c, ACTION_DISMISS, code)
            action == HudSources.DRIK -> PendingIntent.getActivity(c, code, Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.drikpanchang.com/panchang/day-panchang.html?date=" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)))), FLAGS)
            action.startsWith(HudSources.APP_PREFIX) -> launch(c, action.removePrefix(HudSources.APP_PREFIX), code) ?: settings
            else -> settings
        }
    }

    /** Local taps are handled here; anything else is a Smartspacer target and goes through [HudTapActivity]. */
    private fun tapIntent(c: Context, tap: TapRef?, code: Int): PendingIntent = when {
        tap == null -> local(c, HudSources.SETTINGS, code)
        tap.targetId == HudSources.LOCAL -> local(c, tap.actionId ?: HudSources.SETTINGS, code)
        else -> HudTapActivity.pending(c, tap, code)
    }

    /**
     * One tap zone per line: a widget cannot report where you touched. Zones are sized in pixels to match the
     * bitmap's lines, anchored to the top; lines the widget is too short for get no zone.
     */
    override fun bind(ctx: Context, rv: RemoteViews) {
        val g = HudGeometry.of(ctx)
        val slots = g.size.bodyLines
        val taps = HudComposer.lineTaps(HudComposer.compose(ctx, slots), slots)
        val zones = listOf(R.id.zone_l1, R.id.zone_l2, R.id.zone_l3, R.id.zone_l4, R.id.zone_l5)
        rv.setViewLayoutMargin(R.id.zone_l1, RemoteViews.MARGIN_TOP, g.topPx, TypedValue.COMPLEX_UNIT_PX)
        zones.forEachIndexed { i, id ->
            val on = i < g.size.lines
            rv.setViewVisibility(id, if (on) android.view.View.VISIBLE else android.view.View.GONE)
            if (on) {
                rv.setViewLayoutHeight(id, g.pitchPx, TypedValue.COMPLEX_UNIT_PX)
                rv.setOnClickPendingIntent(id, tapIntent(ctx, taps.getOrNull(i), 71 + i))
            }
        }
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        if (CARD) drawColor(p.surface)
        val ink = if (CARD) p.onSurface else Color.WHITE
        val content = HudComposer.compose(ctx, HudSizing.of(w, h).bodyLines)
        renderer(ctx).draw(this, w, h, content, HudColors(ink), iconPainter(ctx))
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Messaging apps' own monochrome icons, tinted to the HUD ink and optically sized. */
    private fun iconPainter(ctx: Context) = IconPainter { key, c, x, cy, size, ink ->
        val pkg = (key as? AppIconKey)?.pkg ?: return@IconPainter
        val mask = GlyphMask.forPackage(ctx, pkg) ?: return@IconPainter
        val target = size * GlyphMask.optical(GlyphMask.coverage(mask))
        val k = target / max(mask.width, mask.height)
        val bw = mask.width * k
        val bh = mask.height * k
        val left = x + (size - bw) / 2f
        val top = cy - bh / 2f
        iconPaint.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(mask, null, RectF(left, top, left + bw, top + bh), iconPaint)
    }

    companion object {
        private const val ACTION_TICK = "com.wdgts.HUD_TICK"
        private const val ACTION_DISMISS = "com.wdgts.HUD_DISMISS"

        /** true = draw the palette surface behind the text; false = transparent, white ink (the Nothing look). */
        private const val CARD = false

        /** 'wght' axis for a variable Geist Mono; lower = thinner. Tune against the reference. */
        private const val WEIGHT = 330

        @Volatile private var cached: HudRenderer? = null

        private fun renderer(ctx: Context): HudRenderer = cached ?: synchronized(this) {
            cached ?: HudRenderer(typeface(ctx), GlyphRegistry(ctx), WEIGHT).also { cached = it }
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
