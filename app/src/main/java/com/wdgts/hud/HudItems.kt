package com.wdgts.hud

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.wdgts.core.Glyph
import com.wdgts.core.Prefs
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudBlocks
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.music.MusicState
import com.wdgts.panchang.Astro
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.sunsetMs
import com.wdgts.sports.Match
import com.wdgts.widgets.Countdown
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the HUD can say, most urgent first. Everything here comes from the device or the widgets' own data:
 * missed calls, look-away reminder, torch, greeting, tap-to-pay, live score, next event, next alarm,
 * music, sunrise/sunset, countdown, tithi. [blocks] turns this list into what the HUD engine draws, and takes
 * Smartspacer blocks as [extra].
 */
object HudItems {
    enum class Action { NONE, CALLS, WALLET, CALENDAR, ALARMS, MUSIC, DISMISS_LOOK, SCORE, DRIK, SETTINGS }
    data class Item(val glyph: Glyph, val text: String, val sub: String? = null, val action: Action = Action.NONE, val arrow: Boolean = false, val ambient: Boolean = false)

    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun until(ms: Long, now: Long): String {
        val m = ((ms - now) / 60_000L).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}H${if (m % 60 > 0) " ${m % 60}M" else ""}" else "${m}MIN"
    }

    fun build(c: Context): List<Item> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val out = ArrayList<Item>()

        val missed = HudSignals.missed
        if (missed.isNotEmpty()) {
            out += Item(Glyph.PHONE, if (missed.size == 1) "MISSED · ${missed[0].uppercase()}" else "MISSED CALLS · ${missed.size}",
                action = Action.CALLS, arrow = true)
        }
        val on = HudSignals.screenOnSince
        if (on > 0 && now - maxOf(on, HudSignals.lookDismissedAt) >= 20 * 60_000L) {
            out += Item(Glyph.EYE, "LOOK AWAY · 20 FT", sub = "20S", action = Action.DISMISS_LOOK, arrow = true)
        }
        if (HudSignals.torchOn) out += Item(Glyph.TORCH, "TORCH ON")

        if (Prefs.hudUnlockDay(c) == today.toEpochDay() && now - Prefs.hudUnlockAt(c) < 10 * 60_000L) {
            out += Item(Glyph.FLOWER, if (ZonedDateTime.now(zone).hour < 12) "GOOD MORNING" else "HELLO")
        }
        if (!HudSignals.onWifi && HudSignals.hasCellular && now - HudSignals.lastWifiAt > 3 * 60_000L) {
            out += Item(Glyph.WALLET, "TAP TO PAY", action = Action.WALLET, arrow = true)
        }

        Prefs.matchJson(c)?.let { Match.fromJson(it) }?.let { m ->
            val hs = m.homeScore.ifEmpty { "0" }
            val aw = m.awayScore.ifEmpty { "0" }
            if (m.state == "in") out += Item(Glyph.BALL, "${m.homeAbbr} $hs–$aw ${m.awayAbbr}", sub = m.clock.ifEmpty { "LIVE" }, action = Action.SCORE)
            else if (m.state == "post" && now - m.kickoffMs < 8 * 3_600_000L) out += Item(Glyph.BALL, "FT ${m.homeAbbr} $hs–$aw ${m.awayAbbr}", action = Action.SCORE)
        }

        HudCalendar.next(c, now)?.let { ev ->
            if (ev.begin - now < 3 * 3_600_000L) out += Item(Glyph.CAL, ev.title.uppercase(), sub = until(ev.begin, now), action = Action.CALENDAR)
        }

        c.getSystemService(AlarmManager::class.java).nextAlarmClock?.let { a ->
            if (a.triggerTime - now < 12 * 3_600_000L) {
                val t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(a.triggerTime), zone)
                out += Item(Glyph.ALARM, "ALARM · ${hm.format(t)}", action = Action.ALARMS)
            }
        }

        if (MusicState.hasSession && MusicState.playing) {
            out += Item(Glyph.NOTE, "${MusicState.title} — ${MusicState.artist}".uppercase(), action = Action.MUSIC)
        }

        // next sunrise / sunset
        val lat = Prefs.lat(c); val lon = Prefs.lon(c)
        val rise = Astro.sunriseMs(today, zone, lat, lon)
        val set = Astro.sunsetMs(today, zone, lat, lon)
        val riseTomorrow = Astro.sunriseMs(today.plusDays(1), zone, lat, lon)
        val (gl, label, at) = when {
            now < rise -> Triple(Glyph.SUN_UP, "SUNRISE", rise)
            now < set -> Triple(Glyph.SUN_DOWN, "SUNSET", set)
            else -> Triple(Glyph.SUN_UP, "SUNRISE", riseTomorrow)
        }
        out += Item(gl, "$label · ${hm.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone))}", ambient = true)

        Countdown.span(c).let { sp -> if (sp.left in 0..60) out += Item(Glyph.CAL, "COUNTDOWN · ${sp.left}D", action = Action.SETTINGS, ambient = true) }

        runCatching {
            val d = Panchang.compute(today, zone, lat, lon, Prefs.purnimanta(c))
            out += Item(Glyph.MOON, "${if (d.paksha == Paksha.KRISHNA) "KP" else "SP"} ${d.pakshaDay} · ${d.month.uppercase()}", action = Action.DRIK, ambient = true)
        }
        return out
    }

    // ---------------------------------------------------------------- engine blocks

    /** TapRef.targetId for taps that map to a [Action]. */
    const val LOCAL = "local"

    private fun tapOf(a: Action): TapRef? = if (a == Action.NONE) null else TapRef(LOCAL, a.name)

    /**
     * Everything the HUD can show, ranked: weather (pinned to the first dynamic line), the urgent local items,
     * then [extra] (Smartspacer), then the ambient local items (sunrise/sunset, countdown, tithi), then the low
     * priority battery warning and notification chips. The engine fits as many as there are lines.
     * A Smartspacer weather block replaces the local one, and a Smartspacer calendar target replaces the local event.
     */
    fun blocks(c: Context, extra: List<Block> = emptyList()): List<Block> {
        val out = ArrayList<Block>()
        if (extra.none { it.pinLine == 0 }) weather(c)?.let { out += it }
        val skipCalendar = extra.any { it.kind == "calendar" }
        val items = build(c).filterNot { skipCalendar && it.glyph == Glyph.CAL && it.action == Action.CALENDAR }
        items.filterNot { it.ambient }.forEachIndexed { n, item -> out += block(item, n) }
        out += extra
        items.filter { it.ambient }.forEachIndexed { n, item -> out += block(item, 100 + n) }
        battery(c)?.let { out += it }
        chips(c)?.let { out += it }
        return out
    }

    /** 🗣 TITLE [→] / STARTING ✌🖐 ⌊7MIN */
    private fun block(i: Item, n: Int): Block {
        val tap = tapOf(i.action)
        val first = ArrayList<Token>()
        first += Icon(i.glyph, tap)
        first += Text(i.text, tap)
        if (i.arrow) first += GlyphToken(HudGlyph.FOLLOWUP, tap)
        val segs = ArrayList<List<Token>>()
        segs += first
        i.sub?.let { s ->
            val tokens = ArrayList<Token>()
            val minutes = HudRules.exactMinutes(s)
            if (minutes != null) {
                if (i.glyph == Glyph.CAL) tokens += Text("STARTING", tap)
                HudRules.hands(minutes).forEach { tokens += GlyphToken(it, tap) }
                tokens += Timer(s.uppercase(), tap)
            } else if (HudRules.isDuration(s)) {
                tokens += Timer(s.uppercase(), tap)
            } else {
                tokens += Text(s, tap)
            }
            segs += tokens
        }
        return Block("item$n", segs)
    }

    private fun weather(c: Context): Block? {
        val (t, word, _) = Prefs.weather(c) ?: return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lat = Prefs.lat(c); val lon = Prefs.lon(c)
        val now = System.currentTimeMillis()
        val night = now < Astro.sunriseMs(today, zone, lat, lon) || now >= Astro.sunsetMs(today, zone, lat, lon)
    
        val look = HudRules.weatherLook(word, night)
    
        return HudBlocks.weather(
            WeatherInfo(
                temp = t,
                celsius = true,
                word = look?.word ?: word,
                glyph = look?.glyph
            )
        )
    }

    private fun battery(c: Context): Block? {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val pct = level * 100 / scale
        if (pct > 20 && !charging) return null
        return Block("battery", listOf(listOf(Text(if (charging) "CHARGING" else "BATTERY LOW"), OutlinePill("$pct%"))))
    }

    private fun chips(c: Context): Block? {
        val counts = listOf(
            "WA" to (HudSignals.counts["com.whatsapp"] ?: 0),
            "IG" to (HudSignals.counts["com.instagram.android"] ?: 0),
            "SMS" to HudSignals.smsCount(c),
        ).filter { it.second > 0 }
        if (counts.isEmpty()) return null
        return Block("chips", listOf(counts.map { (name, n) -> OutlinePill("$name $n") }))
    }
}
