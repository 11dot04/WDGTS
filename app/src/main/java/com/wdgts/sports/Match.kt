package com.wdgts.sports

import org.json.JSONObject

data class Match(
    val id: String, val league: String,
    val state: String,                 // ESPN: "pre" | "in" | "post"
    val kickoffMs: Long,
    val homeName: String, val awayName: String,
    val homeAbbr: String, val awayAbbr: String,
    val homeScore: String, val awayScore: String,
    val clock: String,                 // "67'" while live
    val homeLogo: String, val awayLogo: String,
    val teamIsHome: Boolean,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("league", league); put("state", state); put("ko", kickoffMs)
        put("hn", homeName); put("an", awayName); put("ha", homeAbbr); put("aa", awayAbbr)
        put("hs", homeScore); put("as", awayScore); put("clock", clock)
        put("hl", homeLogo); put("al", awayLogo); put("th", teamIsHome)
    }.toString()

    companion object {
        fun fromJson(s: String): Match? = runCatching {
            val o = JSONObject(s)
            Match(
                o.getString("id"), o.getString("league"), o.getString("state"), o.getLong("ko"),
                o.getString("hn"), o.getString("an"), o.getString("ha"), o.getString("aa"),
                o.getString("hs"), o.getString("as"), o.getString("clock"),
                o.getString("hl"), o.getString("al"), o.getBoolean("th"),
            )
        }.getOrNull()
    }
}
