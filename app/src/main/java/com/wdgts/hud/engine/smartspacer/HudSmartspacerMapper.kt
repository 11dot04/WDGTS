package com.wdgts.hud.engine.smartspacer

import android.annotation.SuppressLint
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceAction
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.BaseTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.CarouselTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.CombinedCardsTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.HeadToHeadTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.SubListTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.kieronquinn.app.smartspacer.sdk.model.weather.WeatherData
import com.kieronquinn.app.smartspacer.sdk.model.weather.WeatherData.WeatherStateIcon
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.Hud
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.IconPill
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.Space
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.WeatherInfo
import java.time.LocalTime
import java.util.Locale

/**
 * Smartspacer targets -> HUD blocks. Needs com.kieronquinn.smartspacer:sdk-client:1.2.
 * Nothing in the rest of the engine depends on this file, so it can be added later.
 *
 * Grammar: a target is a title line (lead glyph, title, arrow) and a subtitle line (its icon, the subtitle or a
 * countdown, then the complications). Every complication is a pair, [icon pill][value pill], so a stray value
 * such as a sunset time can never be mistaken for part of the event.
 */
@SuppressLint("RestrictedApi")
class HudSmartspacerMapper(private val locale: Locale = Locale.getDefault()) {

    class WeatherLook(val glyphs: List<HudGlyph>, val word: String)

    fun weatherLook(s: WeatherStateIcon): WeatherLook? = when (s) {
        WeatherStateIcon.UNKNOWN_ICON -> null
        WeatherStateIcon.SUNNY, WeatherStateIcon.MOSTLY_SUNNY -> WeatherLook(listOf(HudGlyph.SUN), "SUNNY")
        WeatherStateIcon.CLEAR_NIGHT, WeatherStateIcon.MOSTLY_CLEAR_NIGHT -> WeatherLook(listOf(HudGlyph.MOON), "CLEAR NIGHT")
        WeatherStateIcon.PARTLY_CLOUDY -> WeatherLook(listOf(HudGlyph.SUN, HudGlyph.CLOUD), "PARTLY SUNNY")
        WeatherStateIcon.PARTLY_CLOUDY_NIGHT, WeatherStateIcon.MOSTLY_CLOUDY_NIGHT ->
            WeatherLook(listOf(HudGlyph.MOON, HudGlyph.CLOUD), "CLOUDY NIGHT")
        WeatherStateIcon.MOSTLY_CLOUDY_DAY, WeatherStateIcon.CLOUDY -> WeatherLook(listOf(HudGlyph.CLOUD), "CLOUDY")
        WeatherStateIcon.HAZE_FOG_DUST_SMOKE -> WeatherLook(listOf(HudGlyph.HAZE), "HAZE")
        WeatherStateIcon.DRIZZLE, WeatherStateIcon.HEAVY_RAIN, WeatherStateIcon.SHOWERS_RAIN,
        WeatherStateIcon.SCATTERED_SHOWERS_DAY, WeatherStateIcon.SCATTERED_SHOWERS_NIGHT -> WeatherLook(listOf(HudGlyph.RAIN), "RAIN")
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_DAY,
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_NIGHT -> WeatherLook(listOf(HudGlyph.THUNDER), "THUNDER")
        WeatherStateIcon.STRONG_TSTORMS -> WeatherLook(listOf(HudGlyph.STORM), "STORM")
        WeatherStateIcon.BLIZZARD, WeatherStateIcon.BLOWING_SNOW, WeatherStateIcon.FLURRIES,
        WeatherStateIcon.HEAVY_SNOW, WeatherStateIcon.SCATTERED_SNOW_SHOWERS_DAY,
        WeatherStateIcon.SCATTERED_SNOW_SHOWERS_NIGHT, WeatherStateIcon.SNOW_SHOWERS_SNOW,
        WeatherStateIcon.WINTRY_MIX_RAIN_SNOW -> WeatherLook(listOf(HudGlyph.SNOW), "SNOW")
        WeatherStateIcon.MIXED_RAIN_HAIL_RAIN_SLEET, WeatherStateIcon.SLEET_HAIL -> WeatherLook(listOf(HudGlyph.HAIL), "HAIL")
        WeatherStateIcon.TORNADO -> WeatherLook(listOf(HudGlyph.TORNADO), "TORNADO")
        WeatherStateIcon.TROPICAL_STORM_HURRICANE -> WeatherLook(listOf(HudGlyph.HURRICANE), "HURRICANE")
        WeatherStateIcon.WINDY_BREEZY -> WeatherLook(listOf(HudGlyph.BREEZY), "BREEZY")
    }

    /** The weather from whichever target carries weather data. */
    fun weather(targets: List<SmartspaceTarget>): WeatherInfo? {
        val t = targets.firstOrNull { weatherData(it) != null } ?: return null
        val w = weatherData(t) ?: return null
        val look = weatherLook(w.state)
        return WeatherInfo(w.temperature, w.useCelsius, look?.word, look?.glyphs.orEmpty(), tapOf(t, t.baseAction ?: t.headerAction))
    }

    /**
     * The single topmost usable target. Widget targets, remote-view targets and Smartspacer's
     * "Widgets not supported" fallback are skipped, and so is anything that only carries weather.
     * [skip] lets the caller drop more (a greeting outside its window, a music target we replace with our own).
     */
    fun top(targets: List<SmartspaceTarget>, redactSensitive: Boolean, skip: (Block) -> Boolean = { false }): Block? =
        targets.asSequence().mapNotNull { block(it, redactSensitive) }.firstOrNull { !skip(it) }

    private val WIDGET_FALLBACK = Regex("""(?i)widgets?\s+(are\s+)?not\s+supported""")

    private fun isWidgetTarget(t: SmartspaceTarget): Boolean {
        if (t.remoteViews != null || t.widget != null) return true
        val texts = listOfNotNull(
            t.headerAction?.title, t.baseAction?.title,
            t.templateData?.primaryItem?.text?.text?.toString(),
        )
        return texts.any { WIDGET_FALLBACK.containsMatchIn(it) }
    }

    private fun weatherData(t: SmartspaceTarget): WeatherData? =
        (listOfNotNull(t.baseAction, t.headerAction) + t.actionChips + t.iconGrid)
            .firstNotNullOfOrNull { it.weatherData }

    private fun block(t: SmartspaceTarget, redact: Boolean): Block? {
        if (isWidgetTarget(t)) return null
        if (weatherData(t) != null) return null
        val hide = redact && t.isSensitive
        val segments = ArrayList<List<Token>>()
        val d = t.templateData
        if (d != null) collect(t, d, hide, segments) else legacy(t, hide, segments)
        if (segments.isEmpty()) return null
        val kind = if (t.featureType == SmartspaceTarget.FEATURE_CALENDAR) "calendar" else null
        val first = (segments.firstOrNull()?.firstOrNull { it is Text } as? Text)?.text.orEmpty()
        return Block(
            t.smartspaceTargetId, segments.take(Hud.BODY_LINES), kind = kind,
            headline = !hide && HudRules.isGreeting(first),
            compact = compactLine(segments),
        )
    }

    /** TITLE ✦ SUBTITLE [→] for when only one line is left; null when the subtitle carries a countdown. */
    private fun compactLine(segments: List<List<Token>>): List<Token>? {
        if (segments.size < 2) return null
        val title = segments[0]
        val sub = segments[1]
        if (sub.any { it is Timer || (it is GlyphToken && it.glyph.isHand) || it is IconPill }) return null
        val plain = sub.firstOrNull { it is Text } as? Text ?: return null
        val out = ArrayList<Token>()
        out += title.filter { it !is GlyphToken || it.glyph != HudGlyph.FOLLOWUP }
        out += GlyphToken(HudGlyph.STAR4)
        out += plain
        (title + sub).firstOrNull { it is GlyphToken && it.glyph == HudGlyph.FOLLOWUP }?.let { out += it }
        return out
    }

    private fun legacy(t: SmartspaceTarget, hide: Boolean, out: MutableList<List<Token>>) {
        val a = t.headerAction ?: t.baseAction ?: return
        val tap = tapOf(t, a)
        val title = a.title.trim()
        val sub = a.subtitle?.toString()?.trim().orEmpty()
        build(t, title, sub, tap, null, emptyList(), hide, out)
    }

    /** ARSENAL 1–0 HULL CITY [→] / FINAL · 07/11 : a match fits two lines instead of three. */
    private fun headToHead(t: SmartspaceTarget, d: HeadToHeadTemplateData, hide: Boolean, out: MutableList<List<Token>>): Boolean {
        fun str(x: Any?) = x?.toString()?.trim().orEmpty()
        val team = str(d.primaryItem?.text?.text)
        val first = str(d.headToHeadFirstCompetitorText?.text)
        val second = str(d.headToHeadSecondCompetitorText?.text)
        if (team.isEmpty() || first.isEmpty() || second.isEmpty()) return false
        val opponent = str(d.subtitleItem?.text?.text).replace(Regex("(?i)^vs\\.?\\s*"), "")
        val tap = tapOf(t, d.primaryItem?.tapAction)
        val tokens = ArrayList<Token>()
        tokens += Text(txt("$team $first–$second $opponent".trim(), hide), tap)
        if (tap != null) tokens += GlyphToken(HudGlyph.FOLLOWUP, tap)
        out += tokens
        val info = str(d.headToHeadTitle?.text).split('•', '·', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (info.isNotEmpty()) out += listOf<Token>(Text(txt(info.reversed().joinToString(" · "), hide)))
        return true
    }

    private fun collect(t: SmartspaceTarget, d: BaseTemplateData, hide: Boolean, out: MutableList<List<Token>>) {
        if (d is HeadToHeadTemplateData && headToHead(t, d, hide, out)) return
        val title = d.primaryItem?.text?.text?.toString()?.trim().orEmpty()
        val tap = tapOf(t, d.primaryItem?.tapAction)
        val sub = d.subtitleItem?.text?.text?.toString()?.trim().orEmpty()
        val tid = t.smartspaceTargetId
        val comps = listOf("c1" to d.subtitleSupplementalItem, "c2" to d.supplementalLineItem, "c3" to d.supplementalAlarmItem)
            .mapNotNull { (slot, item) ->
                val text = item?.text?.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null else Triple(slot, text, tapOf(t, item?.tapAction))
            }
        build(t, title, sub, tap, "sub".takeIf { SmartspacerIcons.has(tid, "sub") }, comps, hide, out)

        when (d) {
            is CombinedCardsTemplateData -> d.combinedCardDataList.forEach { collect(t, it, hide, out) }
            is SubListTemplateData -> d.subListTexts.take(2).forEach { x ->
                out += listOf<Token>(Text(txt(x.text.toString(), hide)))
            }
            is CarouselTemplateData -> {
                val parts = d.carouselItems.take(3).flatMap { listOfNotNull(it.upperText, it.lowerText) }
                    .map { it.text.toString().trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) out += listOf<Token>(Text(txt(parts.joinToString(" "), hide)))
            }
            else -> Unit
        }
    }

    private fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    /** Titles longer than this push the arrow to the start of the second line, like the reference does. */
    private val ARROW_AT_START = 19

    /**
     * The two lines of a target.
     *  line 1: [speak glyph] TITLE [→]
     *  line 2: [subtitle icon] SUBTITLE or STARTING IN ✌🖐 ⌊10MIN, then one [icon][value] pair per complication
     */
    private fun build(
        t: SmartspaceTarget, title: String, sub: String, tap: TapRef?, subIconSlot: String?,
        comps: List<Triple<String, String, TapRef?>>, hide: Boolean, out: MutableList<List<Token>>,
    ) {
        val tid = t.smartspaceTargetId
        val rel = if (hide || sub.isEmpty()) null else HudRules.countdown(sub)
        val clock = if (hide || sub.isEmpty() || rel != null) null else HudRules.clockCountdown(sub, nowMinutes())
        val minutes = rel?.first ?: clock
        val timeBound = minutes != null
        val calendarLike = t.featureType == SmartspaceTarget.FEATURE_CALENDAR || t.featureType == SmartspaceTarget.FEATURE_REMINDER

        val arrowAtStart = tap != null && title.length > ARROW_AT_START
        if (title.isNotEmpty()) {
            val line = ArrayList<Token>()
            if (calendarLike || timeBound) line += GlyphToken(HudGlyph.SPEAK, tap)
            line += Text(txt(title, hide), tap)
            if (tap != null && !arrowAtStart) line += GlyphToken(HudGlyph.FOLLOWUP, tap)
            out += line
        }

        val line = ArrayList<Token>()
        if (arrowAtStart) line += GlyphToken(HudGlyph.FOLLOWUP, tap)
        if (subIconSlot != null && !hide) line += Icon(SsIconKey(tid, subIconSlot))
        if (minutes != null) {
            val rest = rel?.second.orEmpty()
            val lead = when {
                rest.isNotEmpty() -> "${rest.uppercase(locale)} IN"
                t.featureType == SmartspaceTarget.FEATURE_CALENDAR -> "STARTING IN"
                else -> "IN"
            }
            line += Text(lead)
            HudRules.hands(minutes).forEach { line += GlyphToken(it) }
            line += Timer("${minutes}MIN")
        } else if (sub.isNotEmpty()) {
            line += Text(txt(sub, hide))
        }
        if (comps.isNotEmpty()) {
            if (line.isNotEmpty()) line += Space
            for ((slot, text, ctap) in comps) {
                if (!hide && SmartspacerIcons.has(tid, slot)) line += IconPill(SsIconKey(tid, slot), ctap)
                line += OutlinePill(txt(text, hide), ctap)
            }
        }
        if (line.isNotEmpty()) out += line
    }

    private fun txt(s: String, hide: Boolean): String =
        if (hide) "•".repeat(s.length.coerceAtMost(8)) else s.uppercase(locale)

    private fun tapOf(t: SmartspaceTarget, a: SmartspaceAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id) else null

    private fun tapOf(t: SmartspaceTarget, a: TapAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id.toString()) else null
}
