package com.wdgts.widgets

/**
 * One tile on a 4x4 grid. (col,row) is the top-left cell, w x h the span.
 * 2x2 = big tile with count, 2x1 = wide tile with count, 1x1 = icon-only tile.
 *
 * The app is found by [pkg] if given, otherwise by [match]: any installed launcher app whose name contains it.
 * Icon: a bundled vector wins (drop res/drawable/ic_streak_<id>.xml), else the app's themed icon, else its icon
 * foreground as a silhouette, else a glyph extracted from the plain icon. [web] tiles draw a globe and open [url].
 * [seed] is the count imported once (see Streaks.SEED_VERSION).
 */
data class StreakDef(
    val id: String, val label: String,
    val col: Int, val row: Int, val w: Int, val h: Int,
    val pkg: String = "", val match: String = "",
    val web: Boolean = false, val url: String = "",
    val seed: Int = 0,
)

val STREAKS = listOf(
    StreakDef("duo", "Duolingo", 0, 0, 2, 2, pkg = "com.duolingo", seed = 1035),
    StreakDef("brilliant", "Brilliant", 0, 2, 2, 2, pkg = "org.brilliant.android", match = "brilliant", seed = 304),
    StreakDef("globo", "Globo", 2, 0, 1, 1, match = "globo"),
    StreakDef("nyt", "NYT Games", 3, 0, 1, 1, pkg = "com.nytimes.crossword", match = "nyt games"),
    StreakDef("lumosity", "Lumosity", 2, 1, 1, 1, pkg = "com.lumoslabs.lumosity", match = "lumosity"),
    StreakDef("kinnu", "Kinnu", 3, 1, 1, 1, match = "kinnu"),
    StreakDef("matiks", "Matiks", 2, 2, 2, 1, match = "matiks", seed = 237),
    StreakDef("chess", "Chess.com", 2, 3, 1, 1, pkg = "com.chess", match = "chess"),
    StreakDef("teuf", "TeufTeuf Games", 3, 3, 1, 1, web = true /* , url = "https://..." */),
)
