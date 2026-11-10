package com.wdgts.hud.engine

import android.content.Context
import java.io.IOException

/**
 * Loads glyph SVGs straight from assets: `<dir>/<HudGlyph.res>.svg` (default `glyphs/`).
 * Sizes come from the SVG's own width/height, which are already in design units.
 */
class GlyphRegistry(context: Context, private val dir: String = "glyphs") {
    private val assets = context.applicationContext.assets
    private val cache = HashMap<HudGlyph, SvgGlyph?>()

    fun glyph(g: HudGlyph): SvgGlyph? {
        if (cache.containsKey(g)) return cache[g]
        val parsed = try {
            assets.open("$dir/${g.res}.svg").bufferedReader().use { SvgGlyph.parse(it.readText()) }
        } catch (e: IOException) {
            null
        }
        cache[g] = parsed
        return parsed
    }

    /** Width and height in design units; falls back to the designed sizes if the file is missing. */
    fun sizeUnits(g: HudGlyph): Pair<Float, Float> {
        glyph(g)?.let { return it.width to it.height }
        return FALLBACK[g] ?: (10f to Hud.PILL_H)
    }

    private companion object {
        val FALLBACK = mapOf(
            HudGlyph.SUN to (10f to Hud.PILL_H), HudGlyph.MOON to (10f to Hud.PILL_H),
            HudGlyph.CLOUD to (10f to Hud.PILL_H), HudGlyph.BREEZY to (8f to Hud.PILL_H),
            HudGlyph.RAIN to (7f to Hud.PILL_H), HudGlyph.HAIL to (11f to Hud.PILL_H),
            HudGlyph.THUNDER to (8f to Hud.PILL_H), HudGlyph.TORNADO to (9f to Hud.PILL_H),
            HudGlyph.SNOW to (11f to Hud.PILL_H), HudGlyph.FLOOD to (8f to Hud.PILL_H),
            HudGlyph.HAZE to (8f to Hud.PILL_H), HudGlyph.HURRICANE to (9f to Hud.PILL_H),
            HudGlyph.STORM to (8f to Hud.PILL_H), HudGlyph.SEVERE to (9f to Hud.PILL_H),
            HudGlyph.SPEAK to (11f to 8f), HudGlyph.FOLLOWUP to (16f to 11f),
            HudGlyph.TEMP_100_80 to (7f to 9f), HudGlyph.TEMP_70_60 to (7f to 9f),
            HudGlyph.TEMP_50_30 to (7f to 9f), HudGlyph.TEMP_20_10 to (7f to 9f),
            HudGlyph.TEMP_9_0 to (7f to 9f),
            HudGlyph.EYE to (10.4f to Hud.PILL_H), HudGlyph.PHONE to (10.4f to Hud.PILL_H),
            HudGlyph.CALENDAR to (10f to Hud.PILL_H), HudGlyph.MOON_DISC to (10.4f to Hud.PILL_H),
            HudGlyph.UNIT_F to (11f to 11f), HudGlyph.UNIT_C to (11f to 11f), HudGlyph.UNIT_K to (11f to 11f),
        )
    }
}
