package com.wdgts.hud.engine

/**
 * All geometry is in "design units" taken straight from the SVG set (pill height = 10.368).
 * One unit becomes `unit` pixels at render time, so every glyph keeps its designed proportions.
 */
object Hud {
    const val PILL_H = 10.368f
    const val STROKE = 0.5184f

    /** Cap/digit height of text knocked out of or placed inside pills (measured from Days/Specs). */
    const val PILL_CAP = 5.94f

    /** Cap height of free text. Measured from the reference screenshot (~6.4); tune here. */
    const val BODY_CAP = 6.4f

    const val NUMERAL_W = 16.589f
    const val MONTH_W = 22.291f
    const val BUBBLE_PAD_L = 5.6f
    const val BUBBLE_PAD_R = 6.0f
    const val CURSOR_W = 3.0f
    /** As tall as the digits next to it. */
    const val CURSOR_H = 6.4f
    const val TIMER_LEAD = 6.2f
    const val ICON_W = 10.4f
    const val ICONPILL_W = 13.2f
    const val ICONPILL_ICON = 6.6f
    const val BADGE_D = 12.6f
    const val BADGE_WIDE = 15.2f
    const val TIMER_TEXT_SCALE = 0.92f
    /** Distance from the timer text's baseline up from its underline. */
    const val TIMER_GAP = 4.1f
    const val CHIP_ICON = 6.2f
    const val CHIP_PAD_L = 2.4f
    const val CHIP_PAD_R = 3.2f
    const val CHIP_GAP = 1.2f
    const val DOT = 2.8f
    const val DOT_GAP = 1.8f
    const val OUTLINE_PAD = 3.6f

    const val LINES = 4
    /** Fewest and most lines a widget can show; the fifth only appears when the widget is tall enough. */
    const val MIN_LINES = 4
    const val MAX_LINES = 5
    /** Ambient blocks (tithi, chips, ordinary weather) never use body lines past this many. */
    const val AMBIENT_BODY_LINES = 3
    const val BODY_LINES = LINES - 1
    const val LINE_PITCH = 11.9f
    const val PAD_H = 3f
    const val PAD_V = 4f
    const val WIDTH_UNITS = 150f
    const val HEIGHT_UNITS = LINES * LINE_PITCH + 2 * PAD_V
}

/** Advance widths in design units. Derived from the font at runtime, see HudRenderer. */
data class Metrics(val bodyAdv: Float, val pillAdv: Float) {
    fun body(s: String) = s.codePointCount(0, s.length) * bodyAdv
    fun pill(s: String) = s.codePointCount(0, s.length) * pillAdv
}
