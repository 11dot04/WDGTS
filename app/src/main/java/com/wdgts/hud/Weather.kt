package com.wdgts.hud

import android.content.Context
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import org.json.JSONObject

/** Open-Meteo (free, no key): current temperature and WMO weather code for the Panchang location. */
object Weather {
    fun refreshIfStale(c: Context) {
        val cur = Prefs.weather(c)
        if (cur != null && System.currentTimeMillis() - cur.third < 30 * 60_000L) return
        val txt = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${Prefs.lat(c)}&longitude=${Prefs.lon(c)}&current=temperature_2m,weather_code",
        ) ?: return
        runCatching {
            val o = JSONObject(txt).getJSONObject("current")
            Prefs.setWeather(c, Math.round(o.getDouble("temperature_2m")).toInt(), word(o.getInt("weather_code")))
        }
    }

    fun word(code: Int) = when (code) {
        0 -> "CLEAR"; 1, 2 -> "PARTLY"; 3 -> "CLOUDY"; 45, 48 -> "FOG"
        in 51..57 -> "DRIZZLE"; in 61..67 -> "RAINY"; in 71..77 -> "SNOWY"
        in 80..82 -> "SHOWERS"; in 95..99 -> "STORM"; else -> "—"
    }
}
