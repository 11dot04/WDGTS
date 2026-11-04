package com.wdgts.core

import android.content.Context

data class StreakState(val count: Int, val doneToday: Boolean)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("wdgts", Context.MODE_PRIVATE)

    // --- location & panchang settings (settings screen comes later) ---
    fun lat(c: Context) = sp(c).getFloat("lat", 28.6139f).toDouble()      // default: New Delhi
    fun lon(c: Context) = sp(c).getFloat("lon", 77.2090f).toDouble()
    fun purnimanta(c: Context) = sp(c).getBoolean("purnimanta", true)     // Drik's default naming

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
}
