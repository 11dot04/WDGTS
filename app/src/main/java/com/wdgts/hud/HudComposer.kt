package com.wdgts.hud

import android.content.Context
import android.text.format.DateFormat
import com.wdgts.hud.engine.Badge
import com.wdgts.hud.engine.BadgeShape
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudBlocks
import com.wdgts.hud.engine.HudContent
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudHeader
import com.wdgts.hud.engine.HudPlan
import com.wdgts.hud.engine.HudZones
import com.wdgts.hud.engine.MoonPhase
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.widgets.Sched
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Decides what the HUD says right now.
 *   line 1      the date. A greeting may take it over for a minute, a few times a day.
 *   line 2      the weather, right under the date, as in the reference, unless the lines are needed
 *               (then the temperature moves up into line 1 as a chip)
 *   the rest    blocks by relevance: 0 urgent (missed calls) > 1 contextual (Smartspacer's top target, music,
 *               look-away, streaks) > 2 ambient (tithi, charging, notification chips; never on the fifth line)
 */
object HudComposer {
    private const val GREETING_MS = 60_000L

    private class Ranked(val cls: Int, val block: Block)

    /** [slots] = body lines available (3 on a 4-line widget, 4 when the widget is tall enough for five). */
    fun compose(c: Context, slots: Int): HudContent {
        val app = c.applicationContext
        HudSmartspacerSource.ensure(app) { HudWidget().refreshAll(app) }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val zdt = ZonedDateTime.now(zone)
        val today = zdt.toLocalDate()
        val greetingOk = greetingAllowed(c, now, zdt.hour, today)

        val ranked = ArrayList<Ranked>()
        HudSources.missedCalls()?.let { ranked += Ranked(0, it) }
        val target = HudSmartspacerSource.top(c) { b -> adjustTarget(b, greetingOk) }
        target?.let { ranked += Ranked(1, it) }
        HudSmartspacerSource.extras(c, target?.id)?.let { ranked += Ranked(2, it) }   // independent complications fill what is left
        HudSources.music(c)?.let { ranked += Ranked(1, it) }
        HudSources.lookAway(now)?.let { ranked += Ranked(1, it) }
        HudSources.streaks(c, now, zone, today, zdt.hour)?.let { ranked += Ranked(1, it) }
        HudSources.tithi(c, now, zone, today)?.let { ranked += Ranked(2, it.copy(optional = true)) }
        HudSources.charging(c)?.let { ranked += Ranked(2, it.copy(optional = true)) }
        HudSources.chips(c)?.let { ranked += Ranked(2, it.copy(optional = true)) }

        // a greeting takes over line 1 while it is allowed
        var takeover: List<Token>? = null
        var headerTap: TapRef? = null
        val head = ranked.firstOrNull { it.block.headline }
        if (head != null) {
            val first = head.block.segments.firstOrNull { it.isNotEmpty() }
            if (first != null) {
                takeover = first
                headerTap = first.firstNotNullOfOrNull { it.tap }
                val rest = head.block.segments.filter { it.isNotEmpty() }.drop(1)
                ranked.remove(head)
                if (rest.isNotEmpty()) ranked.add(0, Ranked(head.cls, head.block.copy(segments = rest, headline = false, compact = null, folds = emptyList())))
            }
        }

        // weather: the renderer gives it its own line under the date when that displaces nothing, else a temperature chip on line 1
        val weather: WeatherInfo? = HudSmartspacerSource.weather() ?: HudSources.weather(c, now, zone, today)
        val weatherBlock = weather?.let { HudBlocks.weather(it).copy(optional = true) }
        val chip = if (weather != null && takeover == null) HudBlocks.temperatureChip(weather.temp, weather.celsius) else emptyList()

        val blocks = ranked.sortedBy { it.cls }.map { it.block }
        val badge = headerBadge(c, now, zone, today, zdt.hour)
        val header = takeover ?: HudHeader.tokens(zdt, DateFormat.is24HourFormat(c), badge)
        val headerNoTime = if (takeover == null) HudHeader.tokens(zdt, DateFormat.is24HourFormat(c), badge, showTime = false) else null
        return HudContent(header, blocks, headerExtra = chip, headerTap = headerTap, weather = weatherBlock, headerCompact = headerNoTime)
    }

    /**
     * What to do with Smartspacer's top target before it is used. Our own now-playing block replaces the music
     * target. A greeting outside its window loses only its greeting line: its complications (the lines under it)
     * still come through; a greeting with nothing under it steps aside for the next target.
     */
    private fun adjustTarget(b: Block, greetingOk: Boolean): Block? {
        val first = (b.segments.firstOrNull()?.firstOrNull { it is Text } as? Text)?.text
        if (first != null && HudSources.isPlayingTitle(first)) return null
        if (b.headline && !greetingOk) {
            val rest = b.segments.filter { it.isNotEmpty() }.drop(1)
            return if (rest.isEmpty()) null else b.copy(segments = rest, headline = false, compact = null, folds = emptyList())
        }
        return b
    }

    /** One minute per part of the day (05-12, 12-17, 17-22), starting with the first time you look at the phone. */
    private fun greetingAllowed(c: Context, now: Long, hour: Int, today: LocalDate): Boolean {
        val part = when (hour) { in 5..11 -> 1; in 12..16 -> 2; in 17..21 -> 3; else -> 0 }
        if (part == 0) return false
        val key = today.toEpochDay() * 10 + part
        if (HudPrefs.greetingKey(c) != key) {
            if (HudSignals.screenOnSince <= 0L) return false   // nobody is looking yet
            HudPrefs.startGreeting(c, key, now)
            Sched.arm(c.applicationContext, HudWidget::class.java, HudWidget.ACTION_TICK, now + GREETING_MS + 1500L, 70)
        }
        return key == HudPrefs.greetingKey(c) && now - HudPrefs.greetingStart(c) < GREETING_MS
    }

    /** What sits between the month and the time, per the setting: sun/moon, or the time-zone burst. */
    private fun headerBadge(c: Context, now: Long, zone: ZoneId, today: LocalDate, hour: Int): Token? = when (HudPrefs.badgeMode(c)) {
        HudPrefs.BADGE_DAY_NIGHT -> {
            val night = if (HudPrefs.dayNightBySun(c)) HudSources.isNight(c, now, zone, today) else hour !in 6..17
            if (night) HudSources.moonElongation(c, zone, today)?.let { MoonPhase(it) } ?: GlyphToken(HudGlyph.MOON) else GlyphToken(HudGlyph.SUN)
        }
        HudPrefs.BADGE_ZONE -> Badge(BadgeShape.BURST, zoneAbbreviation(zone))
        HudPrefs.BADGE_ZONE_AWAY -> {
            val home = HudPrefs.homeZone(c) ?: zone.id.also { HudPrefs.setHomeZone(c, it) }
            if (home != zone.id) Badge(BadgeShape.BURST, zoneAbbreviation(zone)) else null
        }
        else -> null
    }

    /** IST, EST, ... or the offset (+5:30) when the zone has no short name. */
    private fun zoneAbbreviation(zone: ZoneId): String = HudZones.abbreviation(zone)

    /** What each tap zone fires: the date line first, then one per free line. */
    fun lineTaps(content: HudContent, slots: Int): List<TapRef?> =
        listOf(content.headerTap) + HudPlan.lineTaps(content.blocks, slots)
}
