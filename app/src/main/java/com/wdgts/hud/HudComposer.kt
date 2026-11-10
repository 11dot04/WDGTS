package com.wdgts.hud

import android.content.Context
import android.text.format.DateFormat
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudBlocks
import com.wdgts.hud.engine.HudContent
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudHeader
import com.wdgts.hud.engine.HudPlan
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.music.MusicState
import com.wdgts.widgets.GlyphMask
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Decides what the HUD says right now. Nothing is pinned: every block has a relevance class
 *   0 urgent      missed calls
 *   1 contextual  Smartspacer's top target, look-away nudge, streaks, notable weather
 *   2 ambient     tithi, charging, notification chips, ordinary weather (never on the fifth line)
 * and the free lines go to the most relevant ones. A greeting may take over the date line. When the lines
 * are all wanted by other blocks, the temperature moves up into the date line as a chip.
 */
object HudComposer {
    private class Ranked(val cls: Int, val block: Block)

    /** [slots] = body lines available (3 on a 4-line widget, 4 when the widget is tall enough for five). */
    fun compose(c: Context, slots: Int): HudContent {
        val app = c.applicationContext
        HudSmartspacerSource.ensure(app) { HudWidget().refreshAll(app) }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val zdt = ZonedDateTime.now(zone)
        val today = zdt.toLocalDate()

        val ranked = ArrayList<Ranked>()
        HudSources.missedCalls()?.let { ranked += Ranked(0, it) }
        HudSmartspacerSource.top(c)?.let { ranked += Ranked(1, withMusicIcon(c, it)) }
        HudSources.lookAway(now)?.let { ranked += Ranked(1, it) }
        HudSources.streaks(c, now, zone, today, zdt.hour)?.let { ranked += Ranked(1, it) }
        HudSources.tithi(c, now, zone, today)?.let { ranked += Ranked(2, it.copy(optional = true)) }
        HudSources.charging(c)?.let { ranked += Ranked(2, it.copy(optional = true)) }
        HudSources.chips(c)?.let { ranked += Ranked(2, it.copy(optional = true)) }

        // a greeting takes over line 1 while it is the top target
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
                if (rest.isNotEmpty()) ranked.add(0, Ranked(head.cls, head.block.copy(segments = rest, headline = false, compact = null)))
            }
        }

        // weather: its own line when there is room, otherwise a temperature chip in the date line
        val weather: WeatherInfo? = HudSmartspacerSource.weather() ?: HudSources.weather(c, now, zone, today)
        var extra: List<Token> = emptyList()
        if (weather != null) {
            val others = ranked.sumOf { r -> r.block.segments.count { it.isNotEmpty() } }
            if (others + 1 <= minOf(slots, 3)) {
                val cls = if (weather.word?.uppercase() in HudRules.NOTABLE_WORDS) 1 else 2
                ranked += Ranked(cls, HudBlocks.weather(weather).copy(optional = cls == 2))
            } else if (takeover == null) {
                extra = HudBlocks.temperatureChip(weather.temp, weather.celsius)
            }
        }

        val blocks = ranked.sortedBy { it.cls }.map { it.block }
        val header = takeover ?: HudHeader.tokens(zdt, DateFormat.is24HourFormat(c), dayNightGlyph(c, now, zone, today, zdt.hour))
        return HudContent(header, blocks, headerExtra = if (takeover == null) extra else emptyList(), headerTap = headerTap)
    }

    /** The now-playing target gets the player's own monochrome icon, or a note glyph when the app has none. */
    private fun withMusicIcon(c: Context, b: Block): Block {
        if (!MusicState.hasSession) return b
        val title = MusicState.title
        val first = b.segments.firstOrNull { it.isNotEmpty() } ?: return b
        val text = first.firstOrNull { it is Text } as? Text ?: return b
        if (title.isBlank() || !text.text.equals(title, ignoreCase = true)) return b
        val lead: Token = if (GlyphMask.forPackage(c, MusicState.pkg) != null) {
            Icon(AppIconKey(MusicState.pkg), text.tap)
        } else {
            GlyphToken(HudGlyph.MUSIC_NOTE, text.tap)
        }
        val segs = b.segments.map { if (it === first) listOf(lead) + it else it }
        val compact = b.compact?.let { listOf(lead) + it }
        return b.copy(segments = segs, compact = compact)
    }

    /** Sun or moon for the date line, only when the setting is on. */
    private fun dayNightGlyph(c: Context, now: Long, zone: ZoneId, today: LocalDate, hour: Int): HudGlyph? {
        if (!HudPrefs.dayNightGlyph(c)) return null
        val night = if (HudPrefs.dayNightBySun(c)) HudSources.isNight(c, now, zone, today) else hour !in 6..17
        return if (night) HudGlyph.MOON else HudGlyph.SUN
    }

    /** What each tap zone fires: the date line first, then one per free line. */
    fun lineTaps(content: HudContent, slots: Int): List<TapRef?> =
        listOf(content.headerTap) + HudPlan.lineTaps(content.blocks, slots)
}
