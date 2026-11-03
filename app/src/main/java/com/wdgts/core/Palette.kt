package com.wdgts.core

import android.content.Context
import android.content.res.Configuration

/** Colours come from the system (Material You) so every widget follows wallpaper + light/dark. */
class Palette private constructor(
    val surface: Int,    // card / container
    val tile: Int,       // inset tiles, darker than the container
    val onSurface: Int,
    val muted: Int,
    val faint: Int,
    val accent: Int,
    val onAccent: Int,
) {
    companion object {
        fun of(c: Context): Palette {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            fun k(id: Int) = c.getColor(id)
            return if (night) Palette(
                surface = k(android.R.color.system_neutral2_800),
                tile = k(android.R.color.system_neutral2_900),
                onSurface = k(android.R.color.system_neutral1_50),
                muted = k(android.R.color.system_neutral2_400),
                faint = k(android.R.color.system_neutral2_500),
                accent = k(android.R.color.system_accent1_200),
                onAccent = k(android.R.color.system_accent1_900),
            ) else Palette(
                surface = k(android.R.color.system_neutral2_100),
                tile = k(android.R.color.system_neutral2_50),
                onSurface = k(android.R.color.system_neutral1_900),
                muted = k(android.R.color.system_neutral2_600),
                faint = k(android.R.color.system_neutral2_400),
                accent = k(android.R.color.system_accent1_600),
                onAccent = k(android.R.color.system_accent1_0),
            )
        }
    }
}
