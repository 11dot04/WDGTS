package com.wdgts.hud

import android.content.Context

/** HUD settings, kept in their own small preferences file. */
object HudPrefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)

    /** Sun/moon glyph between the month and the time. Off by default. */
    fun dayNightGlyph(c: Context) = sp(c).getBoolean("day_night", false)
    fun setDayNightGlyph(c: Context, on: Boolean) = sp(c).edit().putBoolean("day_night", on).apply()

    /** true = switch at sunrise/sunset, false = at 06:00 and 18:00. */
    fun dayNightBySun(c: Context) = sp(c).getBoolean("day_night_sun", false)
    fun setDayNightBySun(c: Context, on: Boolean) = sp(c).edit().putBoolean("day_night_sun", on).apply()
}
