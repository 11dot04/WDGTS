package com.wdgts.hud.engine

import kotlin.math.floor
import kotlin.math.min

/** One unit size and line count for a widget of a given pixel size. Pure, so the widget and the renderer agree. */
object HudSizing {
    class Size(val unit: Float, val lines: Int) {
        val bodyLines get() = lines - 1
    }

    fun of(widthPx: Int, heightPx: Int): Size {
        val unit = min(widthPx / Hud.WIDTH_UNITS, heightPx / (Hud.MIN_LINES * Hud.LINE_PITCH + 2 * Hud.PAD_V))
        val avail = heightPx / unit - 2 * Hud.PAD_V
        val lines = floor(avail / Hud.LINE_PITCH + 0.001f).toInt().coerceIn(Hud.MIN_LINES, Hud.MAX_LINES)
        return Size(unit, lines)
    }
}
