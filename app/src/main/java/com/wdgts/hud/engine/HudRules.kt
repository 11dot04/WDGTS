package com.wdgts.hud.engine

/** Pure helpers with no Android or SDK types, unit tested on the JVM. */
object HudRules {

    /** Temp bar bucket from the file names 100-80, 70-60, 50-30, 20-10, 9-0 (°F lower bounds 80/60/30/10/0). */
    fun tempGlyph(tempF: Float): HudGlyph = when {
        tempF >= 80f -> HudGlyph.TEMP_100_80
        tempF >= 60f -> HudGlyph.TEMP_70_60
        tempF >= 30f -> HudGlyph.TEMP_50_30
        tempF >= 10f -> HudGlyph.TEMP_20_10
        else -> HudGlyph.TEMP_9_0
    }

    fun toFahrenheit(temp: Int, celsius: Boolean): Float = if (celsius) temp * 9f / 5f + 32f else temp.toFloat()

    /**
     * Finger counting for countdowns: remainder first, then fives (7 -> 2,5; 12 -> 2,5,5).
     * Empty when it would need more than [maxHands] hands, so only the number shows.
     */
    fun hands(n: Int, maxHands: Int = 3): List<HudGlyph> {
        if (n <= 0) return emptyList()
        val parts = ArrayList<Int>()
        if (n % 5 != 0) parts += n % 5
        repeat(n / 5) { parts += 5 }
        if (parts.size > maxHands) return emptyList()
        return parts.map { HudGlyph.valueOf("HAND_$it") }
    }

    class WeatherLook(val glyph: HudGlyph, val word: String)

    /**
     * The Nothing weather vocabulary: SUNNY, CLOUDY, BREEZY, RAIN, HAIL, THUNDER, TORNADO, SNOW, FLOOD, HAZE,
     * HURRICANE, STORM, SEVERE, plus CLEAR for a clear night. Weather.kt's words are folded into it.
     */
    fun weatherLook(word: String, night: Boolean): WeatherLook? = when (word.trim().uppercase()) {
        "CLEAR", "SUNNY" -> if (night) WeatherLook(HudGlyph.MOON, "CLEAR") else WeatherLook(HudGlyph.SUN, "SUNNY")
        "PARTLY", "CLOUDY", "OVERCAST" -> WeatherLook(HudGlyph.CLOUD, "CLOUDY")
        "FOG", "HAZE", "MIST" -> WeatherLook(HudGlyph.HAZE, "HAZE")
        "DRIZZLE", "RAINY", "RAIN", "SHOWERS" -> WeatherLook(HudGlyph.RAIN, "RAIN")
        "SNOWY", "SNOW" -> WeatherLook(HudGlyph.SNOW, "SNOW")
        "HAIL", "SLEET" -> WeatherLook(HudGlyph.HAIL, "HAIL")
        "STORM", "THUNDER" -> WeatherLook(HudGlyph.THUNDER, "THUNDER")
        "BREEZY", "WINDY" -> WeatherLook(HudGlyph.BREEZY, "BREEZY")
        else -> null
    }

    /** Conditions worth a line of their own even when the lines are busy. */
    val NOTABLE_WORDS = setOf("RAIN", "SNOW", "THUNDER", "STORM", "HAIL", "HURRICANE", "TORNADO", "SEVERE", "FLOOD")

    private val MINUTES = Regex("""(?i)\b(?:in\s+)?(\d{1,3})\s*(?:min|mins|minutes|m)\b""")

    /** "in 7 min" -> 7 and the text around it ("" for a bare countdown). */
    fun countdown(s: String): Pair<Int, String>? {
        val m = MINUTES.find(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n !in 1..60) return null
        return n to s.removeRange(m.range).trim()
    }

    private val EXACT_MIN = Regex("""^(\d{1,3})\s?MIN$""", RegexOption.IGNORE_CASE)
    private val SHORT_TIME = Regex("""^\d+\s?(MIN|S|H)(\s\d+M)?$""", RegexOption.IGNORE_CASE)

    /** "7MIN" -> 7, otherwise null. */
    fun exactMinutes(s: String): Int? = EXACT_MIN.find(s.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..60 }

    /** Durations like 7MIN, 20S, 2H 30M get the bracket/underline Timer treatment. */
    fun isDuration(s: String): Boolean = SHORT_TIME.matches(s.trim())

    private val CLOCK = Regex("""(?i)^\s*(?:at\s+)?(\d{1,2})[:.](\d{2})\s*([ap])?\.?m?\.?\s*$""")

    /** "at 09:50" or "9:50 PM" -> minutes from [nowMinutes] (minute of the day) when that is 1..59 ahead, else null. */
    fun clockCountdown(s: String, nowMinutes: Int): Int? {
        val m = CLOCK.find(s) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val ap = m.groupValues[3].lowercase()
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        val diff = h * 60 + min - nowMinutes
        return diff.takeIf { it in 1..59 }
    }

    private val GREETING = Regex("""(?i)^(good\s+(morning|afternoon|evening|night)|hello|hi)\b""")

    fun isGreeting(title: String): Boolean = GREETING.containsMatchIn(title.trim())
}
