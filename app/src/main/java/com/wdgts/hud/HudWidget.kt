package com.wdgts.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CallLog
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.SettingsActivity
import com.wdgts.core.*
import com.wdgts.hud.engine.GlyphRegistry
import com.wdgts.hud.engine.Hud
import com.wdgts.hud.engine.HudColors
import com.wdgts.hud.engine.HudContent
import com.wdgts.hud.engine.HudHeader
import com.wdgts.hud.engine.HudPlan
import com.wdgts.hud.engine.HudRenderer
import com.wdgts.hud.engine.IconPainter
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.music.MusicState
import com.wdgts.sports.Match
import com.wdgts.widgets.ImageWidget
import com.wdgts.widgets.Sched
import com.wdgts.widgets.async
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Nothing-clockface HUD: four lines, the first is the date, the other three are filled from [HudItems].
 * Layout, pills, bubbles and glyphs all live in com.wdgts.hud.engine; this class only supplies data,
 * colours, tap intents and the refresh schedule.
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

    private fun pending(c: Context, a: HudItems.Action, code: Int): PendingIntent {
        val settings = PendingIntent.getActivity(c, code, Intent(c, SettingsActivity::class.java), FLAGS)
        return when (a) {
            HudItems.Action.CALLS -> PendingIntent.getActivity(c, code, Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE), FLAGS)
            HudItems.Action.WALLET -> launch(c, "com.google.android.apps.walletnfcrel", code) ?: settings
            HudItems.Action.CALENDAR -> PendingIntent.getActivity(c, code,
                Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")), FLAGS)
            HudItems.Action.ALARMS -> PendingIntent.getActivity(c, code, Intent(AlarmClock.ACTION_SHOW_ALARMS), FLAGS)
            HudItems.Action.MUSIC -> launch(c, MusicState.pkg, code) ?: settings
            HudItems.Action.DISMISS_LOOK -> broadcast(c, ACTION_DISMISS, code)
            HudItems.Action.SCORE -> Prefs.matchJson(c)?.let { Match.fromJson(it) }?.let {
                PendingIntent.getActivity(c, code, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.espn.com/soccer/match/_/gameId/${it.id}")), FLAGS)
            } ?: settings
            HudItems.Action.DRIK -> PendingIntent.getActivity(c, code, Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.drikpanchang.com/panchang/day-panchang.html?date=" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)))), FLAGS)
            else -> settings
        }
    }

    private fun blocks(c: Context) = HudItems.blocks(c, HudSmartspacerSource.blocks(c))

    /** Local taps map to a [HudItems.Action]; Smartspacer taps go through [HudTapActivity]. */
    private fun tapIntent(c: Context, tap: TapRef?, code: Int): PendingIntent {
        if (tap != null && tap.targetId != HudItems.LOCAL) return HudTapActivity.pending(c, tap, code)
        val action = if (tap != null && tap.targetId == HudItems.LOCAL) {
            runCatching { HudItems.Action.valueOf(tap.actionId ?: "") }.getOrNull()
        } else null
        return pending(c, action ?: HudItems.Action.SETTINGS, code)
    }

    /** One tap zone per line (RemoteViews cannot report where you touched). Line 1 is the date: settings. */
    override fun bind(ctx: Context, rv: RemoteViews) {
        val taps = HudPlan.lineTaps(blocks(ctx), Hud.BODY_LINES)
        rv.setOnClickPendingIntent(R.id.zone_l1, pending(ctx, HudItems.Action.SETTINGS, 71))
        listOf(R.id.zone_l2, R.id.zone_l3, R.id.zone_l4).forEachIndexed { i, id ->
            rv.setOnClickPendingIntent(id, tapIntent(ctx, taps.getOrNull(i), 72 + i))
        }
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        if (CARD) drawColor(p.surface)
        val ink = if (CARD) p.onSurface else Color.WHITE
        val content = HudContent(
            header = HudHeader.tokens(ZonedDateTime.now(ZoneId.systemDefault()), DateFormat.is24HourFormat(ctx)),
            blocks = blocks(ctx),
        )
        renderer(ctx).draw(this, w, h, content, HudColors(ink), iconPainter(ctx))
    }

    /** Icons without an SVG yet (phone, eye, torch, ...) are drawn by the existing mGlyph. */
    private fun Canvas.iconPainter(ctx: Context) = IconPainter { key, c, x, cy, unit, ink ->
        val glyph = key as? Glyph ?: return@IconPainter
        c.mGlyph(glyph, x, cy, MicroStyle(unit * 0.945f, ink, Fonts.mono(ctx)), ink)
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
