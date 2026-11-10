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
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.Hud
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.Token
import java.time.LocalTime
import java.util.Locale

/**
 * Smartspacer targets -> HUD blocks. Needs com.kieronquinn.smartspacer:sdk-client:1.2.
 * Nothing in the rest of the engine depends on this file, so it can be added later.
 */
@SuppressLint("RestrictedApi")
class HudSmartspacerMapper(private val locale: Locale = Locale.getDefault()) {

    class WeatherLook(val glyph: HudGlyph, val word: String)

    fun weatherLook(s: WeatherStateIcon): WeatherLook? = when (s) {
        WeatherStateIcon.UNKNOWN_ICON -> null
        WeatherStateIcon.SUNNY, WeatherStateIcon.MOSTLY_SUNNY -> WeatherLook(HudGlyph.SUN, "SUNNY")
        WeatherStateIcon.CLEAR_NIGHT, WeatherStateIcon.MOSTLY_CLEAR_NIGHT -> WeatherLook(HudGlyph.MOON, "CLEAR")
        WeatherStateIcon.PARTLY_CLOUDY, WeatherStateIcon.PARTLY_CLOUDY_NIGHT,
        WeatherStateIcon.MOSTLY_CLOUDY_DAY, WeatherStateIcon.MOSTLY_CLOUDY_NIGHT,
        WeatherStateIcon.CLOUDY -> WeatherLook(HudGlyph.CLOUD, "CLOUDY")
        WeatherStateIcon.HAZE_FOG_DUST_SMOKE -> WeatherLook(HudGlyph.HAZE, "HAZE")
        WeatherStateIcon.DRIZZLE -> WeatherLook(HudGlyph.RAIN, "RAIN")
        WeatherStateIcon.HEAVY_RAIN, WeatherStateIcon.SHOWERS_RAIN,
        WeatherStateIcon.SCATTERED_SHOWERS_DAY, WeatherStateIcon.SCATTERED_SHOWERS_NIGHT -> WeatherLook(HudGlyph.RAIN, "RAIN")
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_DAY,
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_NIGHT -> WeatherLook(HudGlyph.THUNDER, "THUNDER")
        WeatherStateIcon.STRONG_TSTORMS -> WeatherLook(HudGlyph.STORM, "STORM")
        WeatherStateIcon.BLIZZARD, WeatherStateIcon.BLOWING_SNOW, WeatherStateIcon.FLURRIES,
        WeatherStateIcon.HEAVY_SNOW, WeatherStateIcon.SCATTERED_SNOW_SHOWERS_DAY,
        WeatherStateIcon.SCATTERED_SNOW_SHOWERS_NIGHT, WeatherStateIcon.SNOW_SHOWERS_SNOW,
        WeatherStateIcon.WINTRY_MIX_RAIN_SNOW -> WeatherLook(HudGlyph.SNOW, "SNOW")
        WeatherStateIcon.MIXED_RAIN_HAIL_RAIN_SLEET -> WeatherLook(HudGlyph.HAIL, "HAIL")
        WeatherStateIcon.SLEET_HAIL -> WeatherLook(HudGlyph.HAIL, "HAIL")
        WeatherStateIcon.TORNADO -> WeatherLook(HudGlyph.TORNADO, "TORNADO")
        WeatherStateIcon.TROPICAL_STORM_HURRICANE -> WeatherLook(HudGlyph.HURRICANE, "HURRICANE")
        WeatherStateIcon.WINDY_BREEZY -> WeatherLook(HudGlyph.BREEZY, "BREEZY")
    }

    /** The weather from whichever target carries weather data. */
    fun weather(targets: List<SmartspaceTarget>): WeatherInfo? {
        val t = targets.firstOrNull { weatherData(it) != null } ?: return null
        val w = weatherData(t) ?: return null
        val look = weatherLook(w.state)
        return WeatherInfo(w.temperature, w.useCelsius, look?.word, look?.glyph, tapOf(t, t.baseAction ?: t.headerAction))
    }

    /**
     * The single topmost usable target. Widget targets, remote-view targets and Smartspacer's
     * "Widgets not supported" fallback are skipped, and so is anything that only carries weather.
     */
    fun top(targets: List<SmartspaceTarget>, redactSensitive: Boolean): Block? =
        targets.asSequence().mapNotNull { block(it, redactSensitive) }.firstOrNull()

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
        val compact = compactLine(t, segments, hide)
        val kind = if (t.featureType == SmartspaceTarget.FEATURE_CALENDAR) "calendar" else null
        val first = (segments.firstOrNull()?.firstOrNull { it is Text } as? Text)?.text.orEmpty()
        return Block(
            t.smartspaceTargetId, segments.take(Hud.BODY_LINES), kind = kind,
            headline = !hide && HudRules.isGreeting(first),
            compact = compact,
        )
    }

    /** TITLE — SUBTITLE [→] when there is only one line to give; null when the subtitle is not plain text. */
    private fun compactLine(t: SmartspaceTarget, segments: List<List<Token>>, hide: Boolean): List<Token>? {
        if (segments.size < 2) return null
        val title = segments[0]
        val sub = segments[1]
        val plain = sub.firstOrNull() as? Text ?: return null
        if (sub.any { it !is Text && it !is OutlinePill }) return null
        val out = ArrayList<Token>()
        out += title.filter { it !is GlyphToken || it.glyph != HudGlyph.FOLLOWUP }
        out += Text("—")
        out += plain
        title.firstOrNull { it is GlyphToken && it.glyph == HudGlyph.FOLLOWUP }?.let { out += it }
        return out
    }

    private fun legacy(t: SmartspaceTarget, hide: Boolean, out: MutableList<List<Token>>) {
        val a = t.headerAction ?: t.baseAction ?: return
        val tap = tapOf(t, a)
        val title = a.title.trim()
        if (title.isNotEmpty()) out += titleLine(t, txt(title, hide), tap, hasAction = tap != null)
        a.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += subtitleLine(t, it, hide, emptyList()) }
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
        if (title.isNotEmpty()) out += titleLine(t, txt(title, hide), tap, hasAction = tap != null)

        val sub = d.subtitleItem?.text?.text?.toString()?.trim().orEmpty()
        val extras = listOfNotNull(d.subtitleSupplementalItem, d.supplementalLineItem, d.supplementalAlarmItem)
            .mapNotNull { it.text?.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        if (sub.isNotEmpty() || extras.isNotEmpty()) out += subtitleLine(t, sub, hide, extras)

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
            is HeadToHeadTemplateData -> {
                val parts = listOfNotNull(d.headToHeadTitle, d.headToHeadFirstCompetitorText, d.headToHeadSecondCompetitorText)
                    .map { it.text.toString().trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) out += listOf<Token>(Text(txt(parts.joinToString(" "), hide)))
            }
            else -> Unit
        }
    }

    private fun leadGlyph(t: SmartspaceTarget): HudGlyph? = when (t.featureType) {
        SmartspaceTarget.FEATURE_CALENDAR, SmartspaceTarget.FEATURE_REMINDER -> HudGlyph.SPEAK
        else -> null
    }

    private fun titleLine(t: SmartspaceTarget, title: String, tap: TapRef?, hasAction: Boolean): List<Token> {
        val tokens = ArrayList<Token>()
        leadGlyph(t)?.let { tokens += GlyphToken(it, tap) }
        tokens += Text(title, tap)
        if (hasAction) tokens += GlyphToken(HudGlyph.FOLLOWUP, tap)
        return tokens
    }

    private fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    private fun subtitleLine(t: SmartspaceTarget, sub: String, hide: Boolean, extras: List<String>): List<Token> {
        val tokens = ArrayList<Token>()
        val rel = if (hide) null else HudRules.countdown(sub)
        val clock = if (hide || rel != null) null else HudRules.clockCountdown(sub, nowMinutes())
        val minutes = rel?.first ?: clock
        if (minutes != null) {
            val rest = rel?.second.orEmpty()
            val lead = rest.ifEmpty { if (t.featureType == SmartspaceTarget.FEATURE_CALENDAR) "STARTING" else "" }
            if (lead.isNotEmpty()) tokens += Text(lead.uppercase(locale))
            HudRules.hands(minutes).forEach { tokens += GlyphToken(it) }
            tokens += Timer("${minutes}MIN")
        } else if (sub.isNotEmpty()) {
            tokens += Text(txt(sub, hide))
        }
        // complications are chips: outlined pills, one per complication
        extras.forEach { tokens += OutlinePill(txt(it, hide)) }
        return tokens
    }

    private fun txt(s: String, hide: Boolean): String =
        if (hide) "•".repeat(s.length.coerceAtMost(8)) else s.uppercase(locale)

    private fun tapOf(t: SmartspaceTarget, a: SmartspaceAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id) else null

    private fun tapOf(t: SmartspaceTarget, a: TapAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id.toString()) else null
}
