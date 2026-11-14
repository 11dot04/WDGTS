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

    /**
     * The unit to draw at when the widget is to be filled: as big as the widest line (width) and the lines in use (height)
     * allow, but never smaller than [base] and never more than 1.5 x it. [widestUnits] includes both side paddings.
     */
    fun fillUnit(widthPx: Int, heightPx: Int, base: Float, usedLines: Int, widestUnits: Float): Float {
        if (usedLines <= 0 || widestUnits <= 0f) return base
        val byWidth = widthPx / widestUnits
        val byHeight = heightPx / (usedLines * Hud.LINE_PITCH + 2 * Hud.PAD_V)
        return min(byWidth, byHeight).coerceIn(base, base * 1.5f)
    }
}
