package com.wdgts.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CallLog
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.SettingsActivity
import com.wdgts.core.*
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
 * Context-aware micrograph HUD, in the vein of the Nothing clockface: mono type, inline pills,
 * a battery, temperature and weather tokens, then the two most urgent things from [HudItems],
 * then notification-count chips.
 */
class HudWidget : ImageWidget() {
    override val layoutRes: Int = R.layout.widget_hud

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        super.onUpdate(c, m, ids)
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

    override fun bind(ctx: Context, rv: RemoteViews) {
        val items = HudItems.build(ctx)
        val settings = pending(ctx, HudItems.Action.SETTINGS, 71)
        rv.setOnClickPendingIntent(R.id.zone_main, settings)
        rv.setOnClickPendingIntent(R.id.zone_foot, settings)
        rv.setOnClickPendingIntent(R.id.zone_r3, pending(ctx, items.getOrNull(0)?.action ?: HudItems.Action.SETTINGS, 72))
        rv.setOnClickPendingIntent(R.id.zone_r4, pending(ctx, items.getOrNull(1)?.action ?: HudItems.Action.SETTINGS, 73))
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val mono = Fonts.mono(ctx)
        val u = minOf(w, h) / 100f
        val st = MicroStyle(0.86f * u, p.onSurface, mono)
        val g = st.g
        drawColor(p.surface)
        val left = 7f * u
        val right = w - 7f * u
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)

        // ---- row 1:  TUE [11] (NOV) ✹ 10:20 ▮ ----
        val cy1 = 15f * u
        var x = left
        x += mText(now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase(), x, cy1, st) + st.gap
        x += mPill(now.dayOfMonth.toString(), x, cy1, st, filled = true, color = p.accent) + st.gap
        x += mPill(now.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase(), x, cy1, st, filled = false) + st.gap
        val timeStr = DateTimeFormat(ctx, now)
        val tSize = 8.2f * g
        val timeW = mWidth(timeStr, st, tSize)
        if (x + 11f * g + st.gap + timeW + 5f * g < right) {
            if (now.hour in 6..18) drawShape(M3Shapes.Sunny, x + 4.8f * g, cy1, 9.6f * g, p.tertiary)
            else mGlyph(Glyph.MOON, x, cy1, st, p.tertiary)
            x += 11f * g + st.gap
        }
        x += mText(timeStr, x, cy1, st, tSize) + 1.8f * g
        drawRect(x, cy1 - 3.6f * g, x + 1.9f * g, cy1 + 3.6f * g, android.graphics.Paint().also { it.color = p.onSurface })

        // ---- row 2: battery, temperature, weather word ----
        val cy2 = 31f * u
        x = left
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = (bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        x += mBattery(level * 100 / scale, charging, x, cy2, st) + st.gap
        Prefs.weather(ctx)?.let { (t, word, _) ->
            if (x + mWidth("$t°", st, 7.4f * g) + 12f * g + mWidth(word, st) + 8f * g < right) {
                x += mTemp(t, x, cy2, st) + st.gap
                mBubble(word, x, cy2, st, p.accent)
            }
        }
        hairline(left, 41f * u, right, 41f * u, alpha(p.faint, 150), 0.3f * u)

        // ---- rows 3 and 4: the two most urgent things ----
        val items = HudItems.build(ctx)
        fun row(item: HudItems.Item, cy: Float, first: Boolean) {
            val ink = if (first) p.onSurface else p.muted
            mGlyph(item.glyph, left, cy, st, if (first) p.tertiary else p.faint)
            var limit = right
            if (item.arrow) { mArrowPill(right - 17f * g, cy, st, p.accent); limit = right - 17f * g - st.gap }
            if (item.sub != null) { val sw = mBracket(item.sub, limit, cy, st, p.muted); limit -= sw + st.gap }
            val tx = left + 11f * g + 1.6f * g
            mText(ellipsize(item.text, limit - tx, 6.4f * g, mono), tx, cy, st, color = ink)
        }
        items.getOrNull(0)?.let { row(it, 55f * u, true) }
        items.getOrNull(1)?.let { row(it, 72f * u, false) }

        // ---- footer: notification counts ----
        val cy5 = 90f * u
        x = left
        val chips = listOf("WA" to (HudSignals.counts["com.whatsapp"] ?: 0), "IG" to (HudSignals.counts["com.instagram.android"] ?: 0),
            "SMS" to HudSignals.smsCount(ctx))
        for ((name, n) in chips) if (n > 0 && x < right - 20f * g) x += mPill("$name $n", x, cy5, st, filled = false, color = p.muted) + st.gap
        if (x == left) mText("WDGTS // HUD", x, cy5, st, 5f * g, p.faint)
    }

    @Suppress("FunctionName")
    private fun DateTimeFormat(ctx: Context, t: ZonedDateTime): String =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", Locale.US).format(t)

    companion object {
        private const val ACTION_TICK = "com.wdgts.HUD_TICK"
        private const val ACTION_DISMISS = "com.wdgts.HUD_DISMISS"
    }
}
