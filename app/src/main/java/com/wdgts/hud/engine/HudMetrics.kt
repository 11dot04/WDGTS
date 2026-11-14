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
    /** Inline app / plugin icon box. Smaller than the pill height: the Nothing glyphs are ~8 units tall, and app silhouettes fill their box. */
    const val ICON_W = 7.8f
    const val ICONPILL_W = 13.2f
    const val ICONPILL_ICON = 6.6f
    const val BADGE_D = 12.6f
    const val BADGE_WIDE = 15.2f
    const val TIMER_TEXT_SCALE = 0.92f
    /** Timer bracket, measured from the reference (unit = 6.655 px there): stem 13 px, underline 6 px, corner radius about 5.4. */
    const val TIMER_STEM_W = 1.95f
    const val TIMER_LINE_W = 0.9f
    const val TIMER_RADIUS = 5.4f
    /** CJK and other double-width text is drawn at this fraction of the body size so it sits with the Latin caps. */
    const val WIDE_SCALE = 0.84f
    /** Album-art strip: the cover cropped to a landscape strip as tall as a pill, centred on its subject. */
    const val ART_W = 22.0f
    /** Four overlapping rings, each filling completely at 25, 50, 75 and 100 % of the track. */
    const val PROGRESS_RINGS = 4
    const val PROGRESS_D = 8.0f
    const val PROGRESS_STEP = 4.4f
    const val PROGRESS_W = (PROGRESS_RINGS - 1) * PROGRESS_STEP + PROGRESS_D
    /** The moon-phase glyph's disc. */
    const val MOON_D = 9.6f
    /** The small filled circle with a knocked-out word (BY). */
    const val BADGE_CIRCLE_TEXT = 0.7f
    /** Distance from the timer text's baseline up from its underline. */
    const val TIMER_GAP = 4.1f
    const val CHIP_ICON = 5.6f
    const val CHIP_PAD_L = 3.6f
    const val CHIP_PAD_R = 3.8f
    const val CHIP_GAP = 1.4f
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

/**
 * Advance widths in design units. Derived from the font at runtime, see HudRenderer.
 * [bodyWide] / [pillWide] are the advances of double-width characters (CJK, kana, hangul, emoji): the monospace
 * font has no glyphs for them, so the system falls back to another face that is far wider than one cell.
 */
data class Metrics(
    val bodyAdv: Float,
    val pillAdv: Float,
    val bodyWide: Float = bodyAdv * 2f,
    val pillWide: Float = pillAdv * 2f,
) {
    fun body(s: String) = sum(s, bodyAdv, bodyWide)
    fun pill(s: String) = sum(s, pillAdv, pillWide)

    private fun sum(s: String, narrow: Float, wide: Float): Float {
        var t = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            t += if (isWide(cp)) wide else narrow
            i += Character.charCount(cp)
        }
        return t
    }

    companion object {
        /** East Asian wide / fullwidth ranges plus emoji; everything else is one monospace cell. */
        fun isWide(cp: Int): Boolean =
            cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0xA4CF || cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 ||
                cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD
    }
}
