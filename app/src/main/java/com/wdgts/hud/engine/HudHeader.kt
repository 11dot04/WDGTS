package com.wdgts.hud.engine

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object HudHeader {
    /** SUN [9][AUG] ☀ 10:20▮ */
    fun tokens(now: ZonedDateTime, is24h: Boolean, locale: Locale = Locale.ENGLISH): List<Token> {
        val dow = now.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val mon = now.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).take(3)
        val time = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", locale).format(now)
        return listOf(
            Text(dow), NumeralPill(now.dayOfMonth.toString()), MonthPill(mon),
            Space, GlyphToken(HudGlyph.SUN), Space, Text(time), Cursor,
        )
    }
}

object HudBlocks {
    /** I◂ 16°(C) 💧 (RAINY), pinned to the first dynamic line. */
    fun weather(temp: Int, celsius: Boolean, word: String?, glyph: HudGlyph?, tap: TapRef? = null, id: String = "weather"): Block {
        val tokens = ArrayList<Token>()
        tokens += GlyphToken(HudRules.tempGlyph(HudRules.toFahrenheit(temp, celsius)), tap)
        tokens += Text("$temp°", tap)
        tokens += GlyphToken(if (celsius) HudGlyph.UNIT_C else HudGlyph.UNIT_F, tap)
        val hasWord = !word.isNullOrBlank() && word != "—"
        if (glyph != null || hasWord) tokens += Space
        if (glyph != null) tokens += GlyphToken(glyph, tap)
        if (hasWord) tokens += Bubble(word!!.trim().uppercase(), tap)
        return Block(id, listOf(tokens), pinLine = 0)
    }
}
