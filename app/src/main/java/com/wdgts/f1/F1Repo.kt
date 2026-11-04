package com.wdgts.f1

import android.content.Context
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.cos

data class Session(val name: String, val startMs: Long, val durMin: Int)

data class Weekend(
    val round: Int, val name: String, val circuitId: String, val circuitName: String,
    val locality: String, val country: String, val lat: Double, val lon: Double,
    val sessions: List<Session>,
)

/**
 * Schedule from Jolpica (the maintained Ergast-compatible F1 API, no key).
 * Track outline: bacinger/f1-circuits GeoJSON, matched to the circuit by coordinates and cached as a
 * normalised polyline. Circuits missing from that dataset (new venues) simply show without a layout.
 */
object F1Repo {
    private const val NEXT = "https://api.jolpi.ca/ergast/f1/current/next.json"
    private const val TRACKS = "https://raw.githubusercontent.com/bacinger/f1-circuits/master/f1-circuits.geojson"

    fun cached(c: Context): Weekend? = Prefs.f1Json(c)?.let { parse(it) }

    /** Blocking. Refreshes the schedule every 6 h, or at once when the cached race is over. */
    fun ensure(c: Context): Weekend? {
        val cur = cached(c)
        val now = System.currentTimeMillis()
        val over = cur != null && now > (cur.sessions.lastOrNull()?.let { it.startMs + it.durMin * 60_000L } ?: 0L) + 3 * 3_600_000L
        if (cur != null && !over && now - Prefs.f1Time(c) < 6 * 3_600_000L) return cur
        val json = Http.get(NEXT) ?: return cur
        val wk = parse(json) ?: return cur
        Prefs.setF1(c, json)
        track(c, wk, download = true)
        return wk
    }

    private fun ms(date: String, time: String?): Long {
        val t = (time ?: "00:00:00Z").let { if (it.endsWith("Z")) it else it + "Z" }
        return Instant.parse("${date}T$t").toEpochMilli()
    }

    private fun parse(json: String): Weekend? = runCatching {
        val race = JSONObject(json).getJSONObject("MRData").getJSONObject("RaceTable")
            .getJSONArray("Races").getJSONObject(0)
        fun ses(name: String, key: String, dur: Int): Session? =
            race.optJSONObject(key)?.let { Session(name, ms(it.getString("date"), it.optString("time")), dur) }
        val list = listOfNotNull(
            ses("FP1", "FirstPractice", 60), ses("FP2", "SecondPractice", 60), ses("FP3", "ThirdPractice", 60),
            ses("SPRINT QUALI", "SprintQualifying", 45), ses("SPRINT QUALI", "SprintShootout", 45),
            ses("SPRINT", "Sprint", 35), ses("QUALI", "Qualifying", 60),
            Session("RACE", ms(race.getString("date"), race.optString("time")), 120),
        ).sortedBy { it.startMs }
        val circuit = race.getJSONObject("Circuit")
        val loc = circuit.getJSONObject("Location")
        Weekend(
            race.getInt("round"), race.getString("raceName"), circuit.getString("circuitId"),
            circuit.getString("circuitName"), loc.getString("locality"), loc.getString("country"),
            loc.getString("lat").toDouble(), loc.getString("long").toDouble(), list,
        )
    }.getOrNull()

    /** Normalised outline as flat [x0,y0,x1,y1,...] in 0..1 (aspect preserved), or null. */
    fun track(c: Context, wk: Weekend, download: Boolean = false): FloatArray? {
        val f = File(c.filesDir, "track_${wk.circuitId}.json")
        if (!f.exists()) {
            if (!download) return null
            val txt = Http.get(TRACKS, 25_000) ?: return null
            f.writeText(match(txt, wk)?.toString() ?: "[]")
        }
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return null
        if (a.length() < 8) return null
        return FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }

    private fun match(geojson: String, wk: Weekend): JSONArray? = runCatching {
        val feats = JSONObject(geojson).getJSONArray("features")
        var best: JSONArray? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until feats.length()) {
            val co = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
            var sx = 0.0; var sy = 0.0
            val n = co.length()
            for (k in 0 until n) { val pt = co.getJSONArray(k); sx += pt.getDouble(0); sy += pt.getDouble(1) }
            val d = (sx / n - wk.lon).let { it * it } + (sy / n - wk.lat).let { it * it }
            if (d < bestD) { bestD = d; best = co }
        }
        val co = best ?: return null
        if (bestD > 0.25) return null                       // > ~0.5 degrees away: not in the dataset
        val n = co.length()
        val step = maxOf(1, n / 500)
        val lat0 = wk.lat
        val k = cos(Math.toRadians(lat0))
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        var i = 0
        while (i < n) {
            val pt = co.getJSONArray(i)
            xs += (pt.getDouble(0) - wk.lon) * k
            ys += -(pt.getDouble(1) - lat0)
            i += step
        }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY)
        val out = JSONArray()
        for (j in xs.indices) {
            out.put((xs[j] - minX) / span + (1 - (maxX - minX) / span) / 2)
            out.put((ys[j] - minY) / span + (1 - (maxY - minY) / span) / 2)
        }
        out
    }.getOrNull()
}
