package com.wdgts.hud.engine

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object HudHeader {
    /**
     * MON [9][NOV] 12:27▮, with an optional badge (day/night glyph or time-zone burst) between the month and the time.
     * [showTime] false drops the time and its cursor (the temperature takes that room; the status bar has the time anyway).
     */
    fun tokens(now: ZonedDateTime, is24h: Boolean, badge: Token? = null, locale: Locale = Locale.ENGLISH, showTime: Boolean = true): List<Token> {
        val dow = now.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val mon = now.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val time = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", locale).format(now)
        val out = ArrayList<Token>()
        out += Text(dow)
        out += NumeralPill(now.dayOfMonth.toString())
        out += MonthPill(mon)
        out += Space
        if (badge != null) {
            out += badge
            if (showTime) out += Space
        }
        if (showTime) {
            out += Text(time)
            out += Cursor
        }
        return out
    }
}

/** What the weather source knows; the composer decides whether it becomes a header chip or its own line. */
data class WeatherInfo(val temp: Int, val celsius: Boolean, val word: String?, val glyphs: List<HudGlyph> = emptyList(), val tap: TapRef? = null)

object HudBlocks {
    /** The temperature as a header chip: I◂ 27°(C). Appended to line 1 only if it still fits. */
    fun temperatureChip(temp: Int, celsius: Boolean): List<Token> = listOf(
        Space,
        GlyphToken(HudRules.tempGlyph(HudRules.toFahrenheit(temp, celsius))),
        Text("$temp°"),
        GlyphToken(if (celsius) HudGlyph.UNIT_C else HudGlyph.UNIT_F),
    )

    /** I◂ 16°(C) 💧 (RAINY) as its own line. Placement is decided by the composer. */
    fun weather(w: WeatherInfo, id: String = "weather"): Block {
        val tap = w.tap
        val tokens = ArrayList<Token>()
        tokens += GlyphToken(HudRules.tempGlyph(HudRules.toFahrenheit(w.temp, w.celsius)), tap)
        tokens += Text("${w.temp}°", tap)
        tokens += GlyphToken(if (w.celsius) HudGlyph.UNIT_C else HudGlyph.UNIT_F, tap)
        val hasWord = !w.word.isNullOrBlank() && w.word != "—"
        if (w.glyphs.isNotEmpty() || hasWord) tokens += Space
        w.glyphs.forEach { tokens += GlyphToken(it, tap) }
        if (hasWord) tokens += Bubble(w.word!!.trim().uppercase(), tap)
        return Block(id, listOf(tokens), kind = "weather")
    }
}
