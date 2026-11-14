package com.wdgts.core

import android.content.Context

data class StreakState(val count: Int, val doneToday: Boolean)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("wdgts", Context.MODE_PRIVATE)

    // --- location & panchang settings (settings screen comes later) ---
    fun lat(c: Context) = sp(c).getFloat("lat", 28.6139f).toDouble()      // default: New Delhi
    fun lon(c: Context) = sp(c).getFloat("lon", 77.2090f).toDouble()
    fun purnimanta(c: Context) = sp(c).getBoolean("purnimanta", true)     // Drik's default naming
    /** Shukla Paksha counted 16..30 (19 instead of 04), as the KWGT card did. */
    fun shukla16(c: Context) = sp(c).getBoolean("shukla16", true)
    fun setShukla16(c: Context, on: Boolean) = sp(c).edit().putBoolean("shukla16", on).apply()
    /** Widgets use the system sans instead of Google Sans Flex. The HUD (Geist Mono) and the music widgets keep their own fonts. */
    fun systemFont(c: Context) = sp(c).getBoolean("system_font", false)
    fun setSystemFont(c: Context, on: Boolean) = sp(c).edit().putBoolean("system_font", on).apply()
    /** Sports widgets (team match, F1) take their colours from the system palette; off = fixed ink, paper and acid green. */
    fun sportsMaterialYou(c: Context) = sp(c).getBoolean("sports_material_you", true)
    fun setSportsMaterialYou(c: Context, on: Boolean) = sp(c).edit().putBoolean("sports_material_you", on).apply()

    // --- "spoiler" peek at tomorrow; auto-expires when the date changes ---
    fun spoilerOn(c: Context, today: Long) = sp(c).getLong("spoiler_day", -1L) == today
    fun setSpoiler(c: Context, on: Boolean, today: Long) =
        sp(c).edit().putLong("spoiler_day", if (on) today else -1L).apply()

    // --- streaks ---
    /** Missed a day -> 0 automatically. [seed] imports an existing streak the first time. */
    fun streak(c: Context, id: String, today: Long, seed: Int = 0): StreakState {
        val s = sp(c)
        if (!s.contains("${id}_n") && seed > 0) {
            s.edit().putInt("${id}_n", seed).putLong("${id}_last", today - 1).apply()
        }
        val n = s.getInt("${id}_n", 0)
        val last = s.getLong("${id}_last", -1L)
        return when {
            last == today -> StreakState(n, true)
            last == today - 1 -> StreakState(n, false)
            else -> StreakState(0, false)
        }
    }

    fun markDone(c: Context, id: String, today: Long, seed: Int = 0) {
        val cur = streak(c, id, today, seed)
        if (cur.doneToday) return
        sp(c).edit().putInt("${id}_n", cur.count + 1).putLong("${id}_last", today).apply()
    }

    // --- kanji ---
    fun jlpt(c: Context) = sp(c).getInt("jlpt", 5)
    fun setJlpt(c: Context, n: Int) = sp(c).edit().putInt("jlpt", n).apply()
    fun kanjiJson(c: Context, level: Int): String? = sp(c).getString("kd_n$level", null)
    fun setKanjiJson(c: Context, level: Int, json: String) = sp(c).edit().putString("kd_n$level", json).apply()
    fun exIndex(c: Context, kanji: String): Int =
        if (sp(c).getString("ex_kanji", "") == kanji) sp(c).getInt("ex_idx", 0) else 0
    fun setExIndex(c: Context, kanji: String, i: Int) =
        sp(c).edit().putString("ex_kanji", kanji).putInt("ex_idx", i).apply()

    // --- settings written by SettingsActivity ---
    fun setLocation(c: Context, lat: Double, lon: Double) =
        sp(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
    fun setPurnimanta(c: Context, on: Boolean) = sp(c).edit().putBoolean("purnimanta", on).apply()

    // --- countdown ---
    fun cdStart(c: Context) = sp(c).getLong("cd_start", java.time.LocalDate.of(2026, 11, 4).toEpochDay())
    fun cdEnd(c: Context) = sp(c).getLong("cd_end", java.time.LocalDate.of(2026, 12, 31).toEpochDay())
    fun cdQuote(c: Context) = sp(c).getString("cd_quote", "The grass is greener where you water it.")!!
    fun setCountdown(c: Context, start: Long, end: Long, quote: String) =
        sp(c).edit().putLong("cd_start", start).putLong("cd_end", end).putString("cd_quote", quote).apply()

    // --- team widget (ESPN team id; 359 = Arsenal) ---
    fun teamId(c: Context) = sp(c).getString("team_id", "359")!!
    fun setTeamId(c: Context, id: String) = sp(c).edit().putString("team_id", id).apply()
    fun matchJson(c: Context): String? = sp(c).getString("match_json", null)
    fun setMatchJson(c: Context, j: String?) = sp(c).edit().putString("match_json", j).apply()
    fun liveLeague(c: Context): String? = sp(c).getString("live_league", null)
    fun setLiveLeague(c: Context, l: String?) = sp(c).edit().putString("live_league", l).apply()

    // --- f1 ---
    fun f1Json(c: Context): String? = sp(c).getString("f1_json", null)
    fun f1Time(c: Context) = sp(c).getLong("f1_time", 0L)
    fun setF1(c: Context, json: String) = sp(c).edit().putString("f1_json", json).putLong("f1_time", System.currentTimeMillis()).apply()

    // --- panchang spoiler: how many days ahead the widget is peeking (0 = today); expires with the date ---
    fun spoilerOffset(c: Context, today: Long): Int =
        if (sp(c).getLong("spoiler_day", -1L) == today) sp(c).getInt("spoiler_off", 1) else 0
    fun setSpoilerOffset(c: Context, off: Int, today: Long) =
        sp(c).edit().putLong("spoiler_day", if (off > 0) today else -1L).putInt("spoiler_off", off.coerceIn(0, 30)).apply()

    fun homeTag(c: Context) = sp(c).getString("home_tag", "N5")!!
    fun setHomeTag(c: Context, t: String) = sp(c).edit().putString("home_tag", t).apply()

    // --- app-name -> package resolution cache (streak tiles) ---
    fun resolvedPkg(c: Context, id: String): String? = sp(c).getString("pkg_$id", null)
    fun setResolvedPkg(c: Context, id: String, pkg: String) = sp(c).edit().putString("pkg_$id", pkg).apply()
    fun missTime(c: Context, id: String) = sp(c).getLong("pkgmiss_$id", 0L)
    fun setMissTime(c: Context, id: String, t: Long) = sp(c).edit().putLong("pkgmiss_$id", t).apply()

    fun raw(c: Context) = sp(c)

    // --- music: per-widget "controls visible" toggle (top-right dots / pill) ---
    fun musicControls(c: Context, name: String) = sp(c).getBoolean("mc_$name", false)
    fun setMusicControls(c: Context, name: String, on: Boolean) = sp(c).edit().putBoolean("mc_$name", on).apply()

    // --- hud ---
    fun hudUnlockDay(c: Context) = sp(c).getLong("hud_unlock_day", -1L)
    fun hudUnlockAt(c: Context) = sp(c).getLong("hud_unlock_at", 0L)
    fun setHudUnlock(c: Context, day: Long, at: Long) = sp(c).edit().putLong("hud_unlock_day", day).putLong("hud_unlock_at", at).apply()
    fun weather(c: Context): Triple<Int, String, Long>? {
        val s = sp(c)
        return if (s.contains("wx_t")) Triple(s.getInt("wx_t", 0), s.getString("wx_w", "") ?: "", s.getLong("wx_at", 0L)) else null
    }
    fun setWeather(c: Context, t: Int, w: String) =
        sp(c).edit().putInt("wx_t", t).putString("wx_w", w).putLong("wx_at", System.currentTimeMillis()).apply()
}
