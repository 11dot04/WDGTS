package com.wdgts.hud

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Telephony
import com.wdgts.core.Prefs
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.Badge
import com.wdgts.hud.engine.BadgeShape
import com.wdgts.hud.engine.Chip
import com.wdgts.hud.engine.CountRing
import com.wdgts.hud.engine.HudText
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.IconPill
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.DotRow
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.music.MusicState
import com.wdgts.panchang.Astro
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.sunsetMs
import com.wdgts.widgets.GlyphMask
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

    /** [SHAKA] MISSED · NAME [→]: call me back. */
    fun missedCalls(): Block? {
        val missed = HudSignals.missed
        if (missed.isEmpty()) return null
        val t = tap(CALLS)
        val text = if (missed.size == 1) "MISSED · ${missed[0].uppercase()}" else "MISSED CALLS · ${missed.size}"
        return Block("missed", listOf(listOf(GlyphToken(HudGlyph.SHAKA, t), Text(text, t), GlyphToken(HudGlyph.FOLLOWUP, t))))
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

    fun defaultChipApps(): Set<String> = MESSAGING.toSet()

    /** Notification counts per app, shown as up to three differently shaped chips, busiest first. */
    fun chips(c: Context): Block? {
        val allowed = HudPrefs.chipApps(c) ?: MESSAGING.toSet()
        val counts = HashMap<String, Int>()
        for (pkg in allowed) HudSignals.counts[pkg]?.takeIf { it > 0 }?.let { counts[pkg] = it }
        val sms = HudSignals.smsCount(c)
        if (sms > 0) Telephony.Sms.getDefaultSmsPackage(c)?.takeIf { it in allowed }?.let { counts[it] = sms }
        if (counts.isEmpty()) return null
        val tokens = ArrayList<Token>()
        counts.entries.sortedByDescending { it.value }.take(3).forEachIndexed { rank, (pkg, n) ->
            val t = tap(APP_PREFIX + pkg)
            val count = if (n > 99) "99+" else n.toString()
            val key = AppIconKey(pkg)
            if (GlyphMask.forPackage(c, pkg) == null) {
                tokens += OutlinePill("${label(c, pkg)} $count", t)
                return@forEachIndexed
            }
            when (rank) {
                0 -> { tokens += IconPill(key, t); tokens += CountRing(count, t) }       // [icon] (3)
                1 -> tokens += Chip(key, count, t)                                       // ( icon 3 )
                else -> { tokens += Icon(key, t); tokens += Badge(BadgeShape.BURST, count, t) }   // icon (burst 3)
            }
        }
        return Block("chips", listOf(tokens))
    }

    private fun label(c: Context, pkg: String): String =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg.substringAfterLast('.')).uppercase().take(4)

    /** Apps worth offering in Settings: the messaging list that is installed, plus whatever is notifying right now. */
    fun candidateApps(c: Context): List<Pair<String, String>> {
        val pkgs = LinkedHashSet<String>()
        pkgs += MESSAGING.filter { runCatching { c.packageManager.getApplicationInfo(it, 0) }.isSuccess }
        pkgs += HudSignals.counts.keys
        Telephony.Sms.getDefaultSmsPackage(c)?.let { pkgs += it }
        return pkgs.map { it to label(c, it) }.sortedBy { it.second }
    }

    /**
     * The now-playing block, with the player's own icon. Layouts (see [HudPrefs.musicLayout]):
     *  0 auto   a stable variant per track, so it does not flicker between updates
     *  1 plain  [icon] TITLE [→] / ARTIST
     *  2 by     [icon] TITLE (BY) ARTIST on one line, when it is short enough, else plain
     *  3 brackets  [icon] TITLE [→] / [ARTIST]
     * Titles are cleaned of "(Official Video)" and friends.
     */
    fun music(c: Context): Block? {
        if (!(MusicState.hasSession && MusicState.playing)) return null
        val title = HudText.cleanTitle(MusicState.title).uppercase()
        if (title.isBlank()) return null
        val artist = HudText.cleanArtist(MusicState.artist).uppercase()
        val pkg = MusicState.pkg
        val t = tap(APP_PREFIX + pkg)
        val lead: Token = if (GlyphMask.forPackage(c, pkg) != null) Icon(AppIconKey(pkg), t) else GlyphToken(HudGlyph.MUSIC_NOTE, t)
        val arrow = GlyphToken(HudGlyph.FOLLOWUP, t)
        var layout = HudPrefs.musicLayout(c)
        if (layout == 0) layout = 1 + Math.floorMod((title + artist).hashCode(), 3)
        if (artist.isBlank()) return Block("music", listOf(listOf(lead, Text(title, t), arrow)))
        val fitsOneLine = title.length + artist.length <= 18
        if (layout == 2 && fitsOneLine) {
            return Block("music", listOf(listOf(lead, Text(title, t), CountRing("BY", t), Text(artist, t), arrow)))
        }
        val second = if (layout == 3) "[$artist]" else artist
        val compact = listOf(lead, Text(title, t), GlyphToken(HudGlyph.STAR4), Text(artist, t), arrow)
        return Block("music", listOf(listOf(lead, Text(title, t), arrow), listOf<Token>(Text(second, t))), compact = compact)
    }

    /** True when [text] is the track that is playing (so Smartspacer's own now-playing target can step aside). */
    fun isPlayingTitle(text: String): Boolean {
        if (!(MusicState.hasSession && MusicState.playing)) return false
        val t = text.trim()
        return t.equals(MusicState.title.trim(), ignoreCase = true) || t.equals(HudText.cleanTitle(MusicState.title), ignoreCase = true)
    }

    /** [USB-C] FULL IN ⌊40MIN, only while charging and the system knows how long it will take. */
    fun charging(c: Context): Block? {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return null
        val ms = c.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        if (ms <= 0) return null
        val minutes = (ms / 60_000L).coerceAtLeast(1)
        val remaining = if (minutes >= 60) "${minutes / 60}H ${"%02d".format(minutes % 60)}M" else "${minutes}MIN"
        return Block("charging", listOf(listOf(GlyphToken(HudGlyph.USB_C), Text("FULL IN"), Timer(remaining))))
    }

    /** Open-Meteo weather from Weather.kt, used when Smartspacer has none. */
    fun weather(c: Context, now: Long, zone: ZoneId, today: LocalDate): WeatherInfo? {
        val (temp, word, _) = Prefs.weather(c) ?: return null
        val look = HudRules.weatherLook(word, isNight(c, now, zone, today))
        return WeatherInfo(temp, true, look?.word, look?.glyphs.orEmpty())
    }

    fun isNight(c: Context, now: Long, zone: ZoneId, today: LocalDate): Boolean {
        val lat = Prefs.lat(c)
        val lon = Prefs.lon(c)
        return now < Astro.sunriseMs(today, zone, lat, lon) || now >= Astro.sunsetMs(today, zone, lat, lon)
    }
}
