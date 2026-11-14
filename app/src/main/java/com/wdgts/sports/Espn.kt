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

    /** [ok] is false when the network failed (the caller keeps what it had); [match] is null when no fixture exists. */
    class Fetch(val ok: Boolean, val match: Match?)

    /**
     * Uses each competition's scoreboard over a date window; the per-team "schedule" endpoint only lists played
     * matches unless asked for fixtures. Every request and its outcome goes to [Prefs.espnDebug] for the settings screen.
     */
    fun fetch(c: Context): Fetch {
        val id = Prefs.teamId(c)
        val leagues = Prefs.liveLeague(c)?.let { listOf(it) } ?: LEAGUES    // while live, poll only that competition
        val today = java.time.LocalDate.now()
        val now = System.currentTimeMillis()
        val dbg = StringBuilder()
        var anyOk = false

        // Stage 1: a small window (2 days back, 14 ahead) keeps each response light enough for a phone on mobile data.
        val near = scan(leagues, today.minusDays(2), today.plusDays(14), id, dbg, "near")
        anyOk = anyOk || near.ok
        var pick = select(near.matches, now)
        // Stage 2: nothing in that window (international break, cup draw pending): look further ahead.
        if (pick == null) {
            val far = scan(leagues, today.plusDays(15), today.plusDays(75), id, dbg, "far")
            anyOk = anyOk || far.ok
            pick = select(far.matches, now)
        }
        // Stage 3: still nothing, or the scoreboards would not answer: ask ESPN's per-team fixture list directly.
        if (pick == null) {
            val sched = ArrayList<Match>()
            for (lg in leagues) {
                val json = request("team $lg", listOf("$BASE/$lg/teams/$id/schedule?fixture=true"), dbg) ?: continue
                anyOk = true
                val ms = parse(json, lg, id)
                dbg.append("  ${ms.size} ours\n")
                sched += ms
            }
            pick = select(sched, now)
        }
        dbg.append("picked: ").append(pick?.let { "${it.homeAbbr}-${it.awayAbbr} ${it.state} ${it.league}" } ?: "none")
        Prefs.setEspnDebug(c, java.time.LocalDateTime.now().toString().take(16) + "\n" + dbg)
        if (!anyOk) return Fetch(false, null)
        Prefs.setLiveLeague(c, if (pick?.state == "in") pick.league else null)
        if (pick != null) { Crest.ensure(c, pick.homeLogo); Crest.ensure(c, pick.awayLogo) }
        return Fetch(true, pick)
    }

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    private class Scan(val ok: Boolean, val matches: List<Match>)

    /** First of [urls] that answers 2xx; each failed attempt is logged with its HTTP status or exception. */
    private fun request(label: String, urls: List<String>, dbg: StringBuilder): String? {
        for (u in urls) {
            val r = Http.probe(u, 15000)
            val body = r.body
            if (body != null) { dbg.append("$label: ${body.length / 1024} KB\n"); return body }
            dbg.append("$label: HTTP ${r.code}").append(r.err?.let { " $it" } ?: "").append('\n')
        }
        return null
    }

    /** One scoreboard request per league over [from]..[to]; [ok] when at least one league answered. */
    private fun scan(
        leagues: List<String>, from: java.time.LocalDate, to: java.time.LocalDate,
        id: String, dbg: StringBuilder, tag: String,
    ): Scan {
        val fmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        val range = from.format(fmt) + "-" + to.format(fmt)
        val all = ArrayList<Match>()
        var ok = false
        for (lg in leagues) {
            // with the limit first, then without in case ESPN rejects that parameter
            val json = request("$tag $lg", listOf(
                "$BASE/$lg/scoreboard?dates=$range&limit=300",
                "$BASE/$lg/scoreboard?dates=$range",
            ), dbg) ?: continue
            ok = true
            val ms = parse(json, lg, id)
            dbg.append("  ${ms.size} ours\n")
            all += ms
        }
        return Scan(ok, all)
    }

    /** Live match, else a result from the last 8 hours, else the next fixture, else the last result. */
    fun select(all: List<Match>, now: Long): Match? {
        all.firstOrNull { it.state == "in" }?.let { return it }
        all.filter { it.state == "post" && now - it.kickoffMs in 0..8 * 3_600_000L }
            .maxByOrNull { it.kickoffMs }?.let { return it }
        all.filter { it.state == "pre" && it.kickoffMs > now - 3 * 3_600_000L }
            .minByOrNull { it.kickoffMs }?.let { return it }
        return null     // nothing live, recent or upcoming: the widget says so instead of showing an old score
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
            // scoreboards list every match in the league: keep only this team's
            if (home.optJSONObject("team")?.optString("id") != teamId && away.optJSONObject("team")?.optString("id") != teamId) continue
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
