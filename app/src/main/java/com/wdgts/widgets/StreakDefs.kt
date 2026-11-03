package com.wdgts.widgets

/**
 * One tile on a 4x4 grid. (col,row) is the top-left cell, w x h the span.
 * 2x2 = big tile with count, 2x1 = wide tile with count, 1x1 = icon-only tile.
 * Fill in label + pkg (package name) for each; the app's monochrome themed icon is used.
 * [seed] imports your existing streak the first time the widget renders.
 */
data class StreakDef(
    val id: String, val label: String, val pkg: String,
    val col: Int, val row: Int, val w: Int, val h: Int, val seed: Int = 0,
)

val STREAKS = listOf(
    StreakDef("duo", "Duolingo", "com.duolingo", 0, 0, 2, 2, seed = 985),
    StreakDef("big2", "", "", 0, 2, 2, 2),
    StreakDef("s1", "", "", 2, 0, 1, 1),
    StreakDef("s2", "", "", 3, 0, 1, 1),
    StreakDef("s3", "", "", 2, 1, 1, 1),
    StreakDef("s4", "", "", 3, 1, 1, 1),
    StreakDef("wide", "", "", 2, 2, 2, 1),
    StreakDef("s5", "", "", 2, 3, 1, 1),
    StreakDef("s6", "", "", 3, 3, 1, 1),
)
