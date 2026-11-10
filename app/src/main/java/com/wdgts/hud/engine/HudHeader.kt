package com.wdgts.hud.engine

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object HudHeader {
    /** MON [9][NOV] 12:27▮, with an optional day/night glyph (sun or moon) between the month and the time. */
    fun tokens(now: ZonedDateTime, is24h: Boolean, dayNight: HudGlyph? = null, locale: Locale = Locale.ENGLISH): List<Token> {
        val dow = now.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val mon = now.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val time = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", locale).format(now)
        val out = ArrayList<Token>()
        out += Text(dow)
        out += NumeralPill(now.dayOfMonth.toString())
        out += MonthPill(mon)
        out += Space
        if (dayNight != null) {
            out += GlyphToken(dayNight)
            out += Space
        }
        out += Text(time)
        out += Cursor
        return out
    }
}

/** What the weather source knows; the composer decides whether it becomes a header chip or its own line. */
data class WeatherInfo(val temp: Int, val celsius: Boolean, val word: String?, val glyph: HudGlyph?, val tap: TapRef? = null)

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
        if (w.glyph != null || hasWord) tokens += Space
        if (w.glyph != null) tokens += GlyphToken(w.glyph, tap)
        if (hasWord) tokens += Bubble(w.word!!.trim().uppercase(), tap)
        return Block(id, listOf(tokens), kind = "weather")
    }
}
