package com.wdgts.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * ESPN's public site API (unofficial, no key). Team schedule per competition gives kickoff, state
 * ("pre" / "in" / "post"), live clock and score; ESPN caches it for only a few seconds.
 */
object Espn {
    private val LEAGUES = listOf("eng.1", "uefa.champions", "eng.fa", "eng.league_cup")

    /** Null when the network failed (caller keeps showing the last match). */
    fun fetch(c: Context): Match? {
        val id = Prefs.teamId(c)
        // while a match is live only that competition is polled
        val leagues = Prefs.liveLeague(c)?.let { listOf(it) } ?: LEAGUES
        val all = ArrayList<Match>()
        var anyOk = false
        for (lg in leagues) {
            val json = Http.get("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/teams/$id/schedule") ?: continue
            anyOk = true
            all += parse(json, lg, id)
        }
        if (!anyOk) return null
        val pick = select(all, System.currentTimeMillis())
        Prefs.setLiveLeague(c, if (pick?.state == "in") pick.league else null)
        if (pick != null) { Crest.ensure(c, pick.homeLogo); Crest.ensure(c, pick.awayLogo) }
        return pick
    }

    /** Live match, else a result from the last 8 hours, else the next fixture, else the last result. */
    fun select(all: List<Match>, now: Long): Match? {
        all.firstOrNull { it.state == "in" }?.let { return it }
        all.filter { it.state == "post" && now - it.kickoffMs in 0..8 * 3_600_000L }
            .maxByOrNull { it.kickoffMs }?.let { return it }
        all.filter { it.state == "pre" && it.kickoffMs > now - 3 * 3_600_000L }
            .minByOrNull { it.kickoffMs }?.let { return it }
        return all.filter { it.state == "post" }.maxByOrNull { it.kickoffMs }
    }

    private fun parse(json: String, league: String, teamId: String): List<Match> = runCatching {
        val events = JSONObject(json).optJSONArray("events") ?: return@runCatching emptyList<Match>()
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val status = comp.optJSONObject("status") ?: e.optJSONObject("status")
            val state = status?.optJSONObject("type")?.optString("state", "pre") ?: "pre"
            val clock = status?.optString("displayClock", "") ?: ""
            val comps = comp.optJSONArray("competitors") ?: continue
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (k in 0 until comps.length()) {
                val cc = comps.getJSONObject(k)
                if (cc.optString("homeAway") == "home") home = cc else away = cc
            }
            if (home == null || away == null) continue
            val date = e.optString("date").ifEmpty { comp.optString("date") }
            val ms = runCatching {
                ZonedDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: continue
            out += Match(
                e.optString("id"), league, state, ms,
                name(home), name(away), abbr(home), abbr(away), score(home), score(away), clock,
                logo(home), logo(away), home.optJSONObject("team")?.optString("id") == teamId,
            )
        }
        out
    }.getOrDefault(emptyList())

    private fun name(c: JSONObject) = c.optJSONObject("team")?.optString("displayName") ?: ""
    private fun abbr(c: JSONObject) = c.optJSONObject("team")?.optString("abbreviation") ?: ""
    private fun logo(c: JSONObject): String {
        val t = c.optJSONObject("team") ?: return ""
        return t.optString("logo").ifEmpty { t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: "" }
    }
    private fun score(c: JSONObject): String {
        val s = c.opt("score")
        return when {
            s == null || s == JSONObject.NULL -> ""
            s is JSONObject -> s.optString("displayValue").ifEmpty { s.optString("value") }
            else -> s.toString()
        }
    }
}

object Crest {
    private fun file(c: Context, url: String) = File(c.cacheDir, "crest_${url.hashCode()}.png")

    fun ensure(c: Context, url: String) {
        if (url.isBlank()) return
        val f = file(c, url)
        if (!f.exists()) Http.getBytes(url)?.let { f.writeBytes(it) }
    }

    fun load(c: Context, url: String): Bitmap? =
        if (url.isBlank()) null else file(c, url).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
