package com.wdgts.hud

import android.content.Context

/** HUD settings, kept in their own small preferences file. */
object HudPrefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)

    const val BADGE_OFF = 0
    const val BADGE_DAY_NIGHT = 1
    const val BADGE_ZONE = 2
    const val BADGE_ZONE_AWAY = 3

    /** What sits between the month and the time: nothing, a sun/moon glyph, or the time-zone burst. Off by default. */
    fun badgeMode(c: Context) = sp(c).getInt("badge_mode", BADGE_OFF)
    fun setBadgeMode(c: Context, mode: Int) = sp(c).edit().putInt("badge_mode", mode).apply()

    /** The zone the phone was in when "zone when away" was first used. */
    fun homeZone(c: Context): String? = sp(c).getString("home_zone", null)
    fun setHomeZone(c: Context, id: String) = sp(c).edit().putString("home_zone", id).apply()

    /** true = sun/moon switch at sunrise/sunset, false = at 06:00 and 18:00. */
    fun dayNightBySun(c: Context) = sp(c).getBoolean("day_night_sun", false)
    fun setDayNightBySun(c: Context, on: Boolean) = sp(c).edit().putBoolean("day_night_sun", on).apply()

    /** Apps whose notification counts get a chip; null = the built-in messaging list. */
    fun chipApps(c: Context): Set<String>? = sp(c).getStringSet("chip_apps", null)
    fun setChipApps(c: Context, apps: Set<String>) = sp(c).edit().putStringSet("chip_apps", apps).apply()

    /** The greeting may take over the date line for one minute per part of the day. */
    fun greetingKey(c: Context) = sp(c).getLong("greeting_key", 0L)
    fun greetingStart(c: Context) = sp(c).getLong("greeting_start", 0L)
    fun startGreeting(c: Context, key: Long, now: Long) = sp(c).edit().putLong("greeting_key", key).putLong("greeting_start", now).apply()

    /**
     * Now-playing style: 0 varies per track, 1 title then artist, 2 TITLE (BY) ARTIST + rings, 3 TITLE (c) [ARTIST],
     * 4 TITLE + progress rings + ARTIST, 5 TITLE + album-art strip + ARTIST. Styles 2-5 take one line when they fit.
     */
    fun musicLayout(c: Context) = sp(c).getInt("music_layout", 0)
    fun setMusicLayout(c: Context, v: Int) = sp(c).edit().putInt("music_layout", v).apply()

    const val CONTROL_ARROW = 0
    const val CONTROL_PAUSE = 1
    const val CONTROL_CASSETTE = 2

    /** The button at the end of the now-playing line: the arrow (open the player), a pause pill, or the cassette pill. */
    fun musicControl(c: Context) = sp(c).getInt("music_control", CONTROL_ARROW)
    fun setMusicControl(c: Context, v: Int) = sp(c).edit().putInt("music_control", v).apply()

    /** Material You: the date numeral, the weather bubble and the action pills take the system accent colour. */
    fun materialYou(c: Context) = sp(c).getBoolean("material_you", false)
    fun setMaterialYou(c: Context, on: Boolean) = sp(c).edit().putBoolean("material_you", on).apply()
}
