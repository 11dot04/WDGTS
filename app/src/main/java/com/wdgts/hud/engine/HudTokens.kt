package com.wdgts.hud.engine

/** Identifies what a tap should fire. targetId "local" = a HudItems.Action name; anything else = a Smartspacer target. */
data class TapRef(val targetId: String, val actionId: String?)

/** Every SVG the HUD draws; [res] is the file name (no extension) under assets/glyphs/. */
enum class HudGlyph(val res: String) {
    SUN("hud_wx_sunny"), MOON("hud_wx_clear_night"), CLOUD("hud_wx_cloudy"),
    BREEZY("hud_wx_breezy"), RAIN("hud_wx_rain"), HAIL("hud_wx_hail"),
    THUNDER("hud_wx_thunder"), TORNADO("hud_wx_tornado"), SNOW("hud_wx_snow"),
    FLOOD("hud_wx_flood"), HAZE("hud_wx_haze"), HURRICANE("hud_wx_hurricane"),
    STORM("hud_wx_storm"), SEVERE("hud_wx_severe"),

    SPEAK("hud_speak"), FOLLOWUP("hud_followup"),

    HAND_1("hud_hand_1"), HAND_2("hud_hand_2"), HAND_3("hud_hand_3"), HAND_4("hud_hand_4"), HAND_5("hud_hand_5"),

    TEMP_100_80("hud_temp_100_80"), TEMP_70_60("hud_temp_70_60"), TEMP_50_30("hud_temp_50_30"),
    TEMP_20_10("hud_temp_20_10"), TEMP_9_0("hud_temp_9_0"),

    UNIT_F("hud_unit_f"), UNIT_C("hud_unit_c"), UNIT_K("hud_unit_k");

    val isHand get() = name.startsWith("HAND_")
    val isUnit get() = name.startsWith("UNIT_")
}

sealed interface Token {
    val tap: TapRef? get() = null
}

/** Free text in the body size; split into words for wrapping. */
data class Text(val text: String, override val tap: TapRef? = null) : Token

/** Filled pill, text knocked out (the date numeral). Fixed width. */
data class NumeralPill(val text: String, override val tap: TapRef? = null) : Token

/** Outlined pill, fixed width, 3 characters (the month). */
data class MonthPill(val text: String, override val tap: TapRef? = null) : Token

/** Outlined pill sized to its text (chips such as IG 1, SMS 14). */
data class OutlinePill(val text: String, override val tap: TapRef? = null) : Token

/** Filled speech-bubble pill, word knocked out (weather condition). */
data class Bubble(val word: String, override val tap: TapRef? = null) : Token

data class GlyphToken(val glyph: HudGlyph, override val tap: TapRef? = null) : Token

/** An icon drawn by the host app through [IconPainter] (for icons that have no SVG yet). */
data class Icon(val key: Any, override val tap: TapRef? = null) : Token

/** Rounded corner bracket + underline with text above it (the "7MIN" countdown). */
data class Timer(val text: String, override val tap: TapRef? = null) : Token

/** The solid block after the time. */
data object Cursor : Token

/** One explicit space-width gap. */
data object Space : Token

/**
 * One target (or the weather) as a group of lines: each non-empty segment is exactly one line,
 * wrapped never, ellipsized when too long. [pinLine] pins the block to a dynamic line (0..2).
 */
data class Block(
    val id: String,
    val segments: List<List<Token>>,
    val pinLine: Int? = null,
    /** Optional tag used to de-duplicate sources, e.g. "calendar". */
    val kind: String? = null,
)

data class HudContent(val header: List<Token>, val blocks: List<Block>)
