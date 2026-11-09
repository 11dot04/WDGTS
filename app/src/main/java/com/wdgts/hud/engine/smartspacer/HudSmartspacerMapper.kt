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
import com.wdgts.hud.engine.HudBlocks
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.Token
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
        WeatherStateIcon.DRIZZLE -> WeatherLook(HudGlyph.RAIN, "DRIZZLE")
        WeatherStateIcon.HEAVY_RAIN, WeatherStateIcon.SHOWERS_RAIN,
        WeatherStateIcon.SCATTERED_SHOWERS_DAY, WeatherStateIcon.SCATTERED_SHOWERS_NIGHT -> WeatherLook(HudGlyph.RAIN, "RAINY")
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_DAY,
        WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_NIGHT -> WeatherLook(HudGlyph.THUNDER, "THUNDER")
        WeatherStateIcon.STRONG_TSTORMS -> WeatherLook(HudGlyph.STORM, "STORM")
        WeatherStateIcon.BLIZZARD, WeatherStateIcon.BLOWING_SNOW, WeatherStateIcon.FLURRIES,
        WeatherStateIcon.HEAVY_SNOW, WeatherStateIcon.SCATTERED_SNOW_SHOWERS_DAY,
        WeatherStateIcon.SCATTERED_SNOW_SHOWERS_NIGHT, WeatherStateIcon.SNOW_SHOWERS_SNOW,
        WeatherStateIcon.WINTRY_MIX_RAIN_SNOW -> WeatherLook(HudGlyph.SNOW, "SNOWY")
        WeatherStateIcon.MIXED_RAIN_HAIL_RAIN_SLEET -> WeatherLook(HudGlyph.HAIL, "SLEET")
        WeatherStateIcon.SLEET_HAIL -> WeatherLook(HudGlyph.HAIL, "HAIL")
        WeatherStateIcon.TORNADO -> WeatherLook(HudGlyph.TORNADO, "TORNADO")
        WeatherStateIcon.TROPICAL_STORM_HURRICANE -> WeatherLook(HudGlyph.HURRICANE, "HURRICANE")
        WeatherStateIcon.WINDY_BREEZY -> WeatherLook(HudGlyph.BREEZY, "BREEZY")
    }

    /** Weather block first (pinned), then one block per target, in Smartspacer's order. */
    fun blocks(targets: List<SmartspaceTarget>, redactSensitive: Boolean): List<Block> {
        val out = ArrayList<Block>()
        weatherBlock(targets)?.let { out += it }
        for (t in targets) block(t, redactSensitive)?.let { out += it }
        return out
    }

    private fun weatherData(t: SmartspaceTarget): WeatherData? =
        (listOfNotNull(t.baseAction, t.headerAction) + t.actionChips + t.iconGrid)
            .firstNotNullOfOrNull { it.weatherData }

    private fun weatherBlock(targets: List<SmartspaceTarget>): Block? {
        val t = targets.firstOrNull { weatherData(it) != null } ?: return null
        val w = weatherData(t) ?: return null
        val look = weatherLook(w.state)
        return HudBlocks.weather(
            w.temperature, w.useCelsius, look?.word, look?.glyph,
            tapOf(t, t.baseAction ?: t.headerAction), id = t.smartspaceTargetId,
        )
    }

    private fun block(t: SmartspaceTarget, redact: Boolean): Block? {
        if (t.remoteViews != null || t.widget != null) return null
        if (weatherData(t) != null) return null
        val hide = redact && t.isSensitive
        val segments = ArrayList<List<Token>>()
        val d = t.templateData
        if (d != null) collect(t, d, hide, segments) else legacy(t, hide, segments)
        if (segments.isEmpty()) return null
        val kind = if (t.featureType == SmartspaceTarget.FEATURE_CALENDAR) "calendar" else null
        return Block(t.smartspaceTargetId, segments.take(Hud.BODY_LINES), kind = kind)
    }

    private fun legacy(t: SmartspaceTarget, hide: Boolean, out: MutableList<List<Token>>) {
        val a = t.headerAction ?: t.baseAction ?: return
        val tap = tapOf(t, a)
        val title = a.title.trim()
        if (title.isNotEmpty()) out += titleLine(t, txt(title, hide), tap, hasAction = tap != null)
        a.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += subtitleLine(t, it, hide, emptyList()) }
    }

    private fun collect(t: SmartspaceTarget, d: BaseTemplateData, hide: Boolean, out: MutableList<List<Token>>) {
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

    private fun subtitleLine(t: SmartspaceTarget, sub: String, hide: Boolean, extras: List<String>): List<Token> {
        val tokens = ArrayList<Token>()
        val count = if (hide) null else HudRules.countdown(sub)
        if (count != null) {
            val (minutes, rest) = count
            val lead = rest.ifEmpty { if (t.featureType == SmartspaceTarget.FEATURE_CALENDAR) "STARTING" else "" }
            if (lead.isNotEmpty()) tokens += Text(lead.uppercase(locale))
            HudRules.hands(minutes).forEach { tokens += GlyphToken(it) }
            tokens += Timer("${minutes}MIN")
        } else if (sub.isNotEmpty()) {
            tokens += Text(txt(sub, hide))
        }
        extras.forEach { tokens += Text(txt(it, hide)) }
        return tokens
    }

    private fun txt(s: String, hide: Boolean): String =
        if (hide) "•".repeat(s.length.coerceAtMost(8)) else s.uppercase(locale)

    private fun tapOf(t: SmartspaceTarget, a: SmartspaceAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id) else null

    private fun tapOf(t: SmartspaceTarget, a: TapAction?): TapRef? =
        if (a != null && (a.pendingIntent != null || a.intent != null)) TapRef(t.smartspaceTargetId, a.id.toString()) else null
}
