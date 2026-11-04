package com.wdgts.core

import android.content.Context
import android.content.res.Configuration

/**
 * Material You colours, mapped the way the KWGT set uses them: surfaces from the SECONDARY palette
 * (teal-tinted, not neutral grey), cyan primary for text/accents, lavender tertiary for glyphs.
 */
class Palette private constructor(
    val surface: Int,    // card / container (secondary tone 20)
    val tile: Int,       // inset tiles, darker (secondary tone 10)
    val done: Int,       // a lit-up tile (secondary tone 40)
    val onSurface: Int,
    val muted: Int,
    val faint: Int,
    val accent: Int,     // primary tone 80, the cyan
    val onAccent: Int,
    val tertiary: Int,   // tertiary tone 80, the lavender
) {
    companion object {
        fun of(c: Context): Palette {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            fun k(id: Int) = c.getColor(id)
            return if (night) Palette(
                surface = k(android.R.color.system_accent2_800),
                tile = k(android.R.color.system_accent2_900),
                done = k(android.R.color.system_accent2_600),
                onSurface = k(android.R.color.system_neutral1_50),
                muted = k(android.R.color.system_accent2_400),
                faint = k(android.R.color.system_accent2_500),
                accent = k(android.R.color.system_accent1_200),
                onAccent = k(android.R.color.system_accent1_900),
                tertiary = k(android.R.color.system_accent3_200),
            ) else Palette(
                surface = k(android.R.color.system_accent2_100),
                tile = k(android.R.color.system_accent2_50),
                done = k(android.R.color.system_accent2_300),
                onSurface = k(android.R.color.system_neutral1_900),
                muted = k(android.R.color.system_accent2_600),
                faint = k(android.R.color.system_accent2_400),
                accent = k(android.R.color.system_accent1_600),
                onAccent = k(android.R.color.system_accent1_0),
                tertiary = k(android.R.color.system_accent3_700),
            )
        }
    }
}
