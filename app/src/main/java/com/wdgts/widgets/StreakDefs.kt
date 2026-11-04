package com.wdgts.widgets

/**
 * One tile on a 4x4 grid. (col,row) is the top-left cell, w x h the span.
 * 2x2 = big tile with count, 2x1 = wide tile with count, 1x1 = icon-only tile.
 *
 * The app is found by [pkg] if given, otherwise by [match]: any installed launcher app whose name contains it
 * (case-insensitive). Its themed monochrome icon is drawn on the tile and a tap opens it.
 * [web] tiles have no app: a globe is drawn, and [url] (if set) opens on tap.
 * [seed] imports an existing streak the first time the widget renders.
 */
data class StreakDef(
    val id: String, val label: String,
    val col: Int, val row: Int, val w: Int, val h: Int,
    val pkg: String = "", val match: String = "",
    val web: Boolean = false, val url: String = "",
    val seed: Int = 0,
)

val STREAKS = listOf(
    StreakDef("duo", "Duolingo", 0, 0, 2, 2, pkg = "com.duolingo", seed = 1034),
    StreakDef("brilliant", "Brilliant", 0, 2, 2, 2, pkg = "org.brilliant.android", match = "brilliant", seed = 303),
    StreakDef("globo", "Globo", 2, 0, 1, 1, match = "globo"),
    StreakDef("nyt", "NYT Games", 3, 0, 1, 1, match = "nyt games"),
    StreakDef("lumosity", "Lumosity", 2, 1, 1, 1, match = "lumosity"),
    StreakDef("kinnu", "Kinnu", 3, 1, 1, 1, match = "kinnu"),
    StreakDef("matiks", "Matiks", 2, 2, 2, 1, match = "matiks", seed = 236),
    StreakDef("chess", "Chess.com", 2, 3, 1, 1, pkg = "com.chess", match = "chess"),
    StreakDef("teuf", "TeufTeuf Games", 3, 3, 1, 1, web = true /* , url = "https://..." */),
)
