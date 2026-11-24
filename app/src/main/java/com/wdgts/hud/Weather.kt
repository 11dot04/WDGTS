package com.wdgts.hud

import android.content.Context
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import org.json.JSONObject

/** Open-Meteo (free, no key): current temperature and WMO weather code for the Panchang location. */
object Weather {
    fun refreshIfStale(c: Context) {
        Aqi.refreshIfStale(c)
        val cur = Prefs.weather(c)
        if (cur != null && System.currentTimeMillis() - cur.third < 30 * 60_000L) return
        val txt = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${Prefs.lat(c)}&longitude=${Prefs.lon(c)}" +
                "&current=temperature_2m,weather_code&minutely_15=precipitation&forecast_minutely_15=8&timeformat=unixtime",
        ) ?: return
        runCatching {
            val o = JSONObject(txt).getJSONObject("current")
            Prefs.setWeather(c, Math.round(o.getDouble("temperature_2m")).toInt(), word(o.getInt("weather_code")))
        }
        runCatching { rainAhead(c, JSONObject(txt)) }
    }

    /** When the next 15-minute slot with measurable rain starts (0 = none in the next two hours), for the "rain in 20 min" line. */
    private fun rainAhead(c: Context, root: JSONObject) {
        val m = root.getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val rain = m.getJSONArray("precipitation")
        val now = System.currentTimeMillis()
        var at = 0L
        for (i in 0 until times.length()) {
            val start = times.getLong(i) * 1000L
            if (start + 15 * 60_000L <= now) continue
            if (!rain.isNull(i) && rain.getDouble(i) >= 0.1) { at = maxOf(start, now); break }
        }
        HudNativePrefs.setLong(c, "rain_at", at)
        HudNativePrefs.setLong(c, "rain_fetched", now)
    }

    fun word(code: Int) = when (code) {
        0 -> "CLEAR"; 1, 2 -> "PARTLY"; 3 -> "CLOUDY"; 45, 48 -> "FOG"
        in 51..57 -> "DRIZZLE"; in 61..67 -> "RAINY"; in 71..77 -> "SNOWY"
        in 80..82 -> "SHOWERS"; in 95..99 -> "STORM"; else -> "—"
    }
}
