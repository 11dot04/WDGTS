package com.wdgts.hud

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Telephony
import com.wdgts.core.Prefs
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.Chip
import com.wdgts.hud.engine.DotRow
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.panchang.Astro
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.sunsetMs
import com.wdgts.widgets.STREAKS
import com.wdgts.widgets.Streaks
import java.time.LocalDate
import java.time.ZoneId

/** Identifies an app whose monochrome icon the HUD should draw. */
data class AppIconKey(val pkg: String)

/**
 * What the device knows that Smartspacer does not: missed calls, the 20-20-20 look-away nudge, the tithi on the
 * first unlock of the day, the streaks still open late in the evening, and per-app notification counts.
 * Each source only decides what to say; ranking and placement happen in [HudComposer].
 */
object HudSources {
    /** [TapRef.targetId] for taps handled by this app (the action id is one of the constants below). */
    const val LOCAL = "local"
    const val CALLS = "CALLS"
    const val DISMISS_LOOK = "DISMISS_LOOK"
    const val DRIK = "DRIK"
    const val SETTINGS = "SETTINGS"
    const val APP_PREFIX = "APP:"

    private fun tap(action: String) = TapRef(LOCAL, action)

    private val MESSAGING = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.discord", "com.slack", "com.google.android.gm", "com.snapchat.android",
    )

    /** [PHONE] MISSED · NAME [→] */
    fun missedCalls(): Block? {
        val missed = HudSignals.missed
        if (missed.isEmpty()) return null
        val t = tap(CALLS)
        val text = if (missed.size == 1) "MISSED · ${missed[0].uppercase()}" else "MISSED CALLS · ${missed.size}"
        return Block("missed", listOf(listOf(GlyphToken(HudGlyph.PHONE, t), Text(text, t), GlyphToken(HudGlyph.FOLLOWUP, t))))
    }

    /** [EYE] LOOK AWAY · 20 FT [→] / ⌊20S */
    fun lookAway(now: Long): Block? {
        val on = HudSignals.screenOnSince
        if (on <= 0 || now - maxOf(on, HudSignals.lookDismissedAt) < 20 * 60_000L) return null
        val t = tap(DISMISS_LOOK)
        return Block(
            "look",
            listOf(
                listOf(GlyphToken(HudGlyph.EYE, t), Text("LOOK AWAY · 20 FT", t), GlyphToken(HudGlyph.FOLLOWUP, t)),
                listOf(Timer("20S", t)),
            ),
        )
    }

    /** [MOON DISC] SP 5 · KARTIK [→], only in the first ten minutes after the first unlock of the day. */
    fun tithi(c: Context, now: Long, zone: ZoneId, today: LocalDate): Block? {
        if (Prefs.hudUnlockDay(c) != today.toEpochDay() || now - Prefs.hudUnlockAt(c) >= 10 * 60_000L) return null
        val d = runCatching {
            Panchang.compute(today, zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c))
        }.getOrNull() ?: return null
        val t = tap(DRIK)
        val text = "${if (d.paksha == Paksha.KRISHNA) "KP" else "SP"} ${d.pakshaDay} · ${d.month.uppercase()}"
        return Block("tithi", listOf(listOf(GlyphToken(HudGlyph.MOON_DISC, t), Text(text, t), GlyphToken(HudGlyph.FOLLOWUP, t))))
    }

    /** 3 STREAKS LEFT / ●●●○○○ ⌊2H 10M, shown from 22:00 until midnight while any streak is still open. */
    fun streaks(c: Context, now: Long, zone: ZoneId, today: LocalDate, hour: Int): Block? {
        if (hour < 22 || STREAKS.isEmpty()) return null
        val day = today.toEpochDay()
        val total = STREAKS.size
        val done = STREAKS.count { Streaks.state(c, it, day).doneToday }
        val left = total - done
        if (left <= 0) return null
        val t = tap(SETTINGS)
        val minutes = ((today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - now) / 60_000L).coerceAtLeast(1)
        val remaining = if (minutes >= 60) "${minutes / 60}H${if (minutes % 60 > 0) " ${minutes % 60}M" else ""}" else "${minutes}MIN"
        return Block(
            "streaks",
            listOf(
                listOf(Text("$left STREAK${if (left > 1) "S" else ""} LEFT", t)),
                listOf(DotRow(done, total, t), Timer(remaining, t)),
            ),
        )
    }

    /** One chip per messaging app with unread notifications: [app icon] 3. Up to three, busiest first. */
    fun chips(c: Context): Block? {
        val counts = HashMap<String, Int>()
        for (pkg in MESSAGING) HudSignals.counts[pkg]?.takeIf { it > 0 }?.let { counts[pkg] = it }
        val sms = HudSignals.smsCount(c)
        if (sms > 0) Telephony.Sms.getDefaultSmsPackage(c)?.let { counts[it] = sms }
        if (counts.isEmpty()) return null
        val chips = counts.entries.sortedByDescending { it.value }.take(3).map { (pkg, n) ->
            Chip(AppIconKey(pkg), if (n > 99) "99+" else n.toString(), tap(APP_PREFIX + pkg))
        }
        return Block("chips", listOf(chips))
    }

    /** [USB-C] CHARGING · 57%, only while something is plugged in. */
    fun charging(c: Context): Block? {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return null
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return Block("charging", listOf(listOf(GlyphToken(HudGlyph.USB_C), Text("CHARGING · ${level * 100 / scale}%"))))
    }

    /** Open-Meteo weather from Weather.kt, used when Smartspacer has none. */
    fun weather(c: Context, now: Long, zone: ZoneId, today: LocalDate): WeatherInfo? {
        val (temp, word, _) = Prefs.weather(c) ?: return null
        val look = HudRules.weatherLook(word, isNight(c, now, zone, today))
        return WeatherInfo(temp, true, look?.word, look?.glyph)
    }

    fun isNight(c: Context, now: Long, zone: ZoneId, today: LocalDate): Boolean {
        val lat = Prefs.lat(c)
        val lon = Prefs.lon(c)
        return now < Astro.sunriseMs(today, zone, lat, lon) || now >= Astro.sunsetMs(today, zone, lat, lon)
    }
}
