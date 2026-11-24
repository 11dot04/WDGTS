package com.wdgts.hud

import android.Manifest
import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.CalendarContract
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.IconPill
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.Token
import com.wdgts.panchang.Astro
import com.wdgts.panchang.sunsetMs
import com.wdgts.widgets.Sched
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A glyph from the HUD's own set used as an icon inside a pill (the shoe in the steps pill). */
data class GlyphKey(val glyph: HudGlyph)

/** Settings of the HUD's own sources, each one a key here so the settings screen can read and write them generically. */
object HudNativePrefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_native", Context.MODE_PRIVATE)

    fun flag(c: Context, key: String, def: Boolean) = sp(c).getBoolean(key, def)
    fun setFlag(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun num(c: Context, key: String, def: Int) = sp(c).getInt(key, def)
    fun setNum(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()
    fun long(c: Context, key: String, def: Long) = sp(c).getLong(key, def)
    fun setLong(c: Context, key: String, v: Long) = sp(c).edit().putLong(key, v).apply()
    fun float(c: Context, key: String, def: Float) = sp(c).getFloat(key, def)
    fun setFloat(c: Context, key: String, v: Float) = sp(c).edit().putFloat(key, v).apply()

    const val SUN = "sun_on"
    const val SUN_BEFORE = "sun_before"          // minutes before sunrise / sunset
    const val SUN_AFTER = "sun_after"            // minutes after
    const val BDAY = "bday_on"
    const val BDAY_END = "bday_end"              // hour of the day the birthday reminder stops
    const val BDAY_DISMISSED = "bday_dismissed"  // epoch day
    const val BED_MODE = "bed_mode"              // 0 off, 1 manual time, 2 Digital Wellbeing
    const val BED_MIN = "bed_min"                // minute of the day
    const val BED_LEAD = "bed_lead"              // minutes before bedtime
    const val STEPS = "steps_on"
    const val STEPS_GOAL = "steps_goal"
    const val AQI = "aqi_on"
    const val AQI_THR = "aqi_thr"
    const val AQI_MARGIN = "aqi_margin"
    const val AQI_ACTIVE = "aqi_active"
    const val RAIN = "rain_on"
    const val RAIN_WIN = "rain_win"              // minutes
    const val EVENT = "event_on"
    const val EVENT_LEAD = "event_lead"          // minutes
    const val ALARM = "alarm_on"
    const val ALARM_LEAD = "alarm_lead"          // minutes
    const val SEEN_N = "seen_n"                  // unlocks after which an ambient item steps aside; 0 = never
}

/**
 * Ambient complications (steps, countdowns) step aside after they have been on show for the first N unlocks, and come
 * back when what they say changes in a way that matters (the [sig]nature changes). An unlock counts for every item that
 * was eligible in the last ten minutes.
 */
object HudRelevance {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_seen", Context.MODE_PRIVATE)
    private val eligible = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun allow(c: Context, id: String, sig: String): Boolean {
        val n = HudNativePrefs.num(c, HudNativePrefs.SEEN_N, 3)
        val p = sp(c)
        if (p.getString("sig_$id", null) != sig) p.edit().putString("sig_$id", sig).putInt("seen_$id", 0).apply()
        eligible[id] = System.currentTimeMillis()
        return n <= 0 || p.getInt("seen_$id", 0) < n
    }

    fun onUnlock(c: Context) {
        val now = System.currentTimeMillis()
        val p = sp(c)
        val e = p.edit()
        var any = false
        for ((id, at) in eligible) if (now - at < 10 * 60_000L) {
            e.putInt("seen_$id", p.getInt("seen_$id", 0) + 1)
            any = true
        }
        if (any) e.apply()
    }
}

/** Steps from the phone's step counter, kept per day. The counter counts since boot, so the day's start and any reboot are handled here. */
object HudSteps {
    private lateinit var app: Context
    private var started = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_steps", Context.MODE_PRIVATE)

    fun granted(c: Context) = c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { record(app, e.values[0].toLong()) }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Starts listening once the permission is there; cheap to call again. */
    @Synchronized
    fun ensure(c: Context) {
        if (started || !granted(c)) return
        app = c.applicationContext
        val sm = app.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        started = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 120_000_000)
    }

    private fun record(c: Context, raw: Long) {
        val p = sp(c)
        val day = LocalDate.now().toEpochDay()
        val last = p.getLong("last", -1L)
        var base = p.getLong("base", raw)
        var carry = p.getLong("carry", 0L)
        if (p.getLong("day", -1L) != day) {
            base = if (last in 0..raw) last else raw      // steps since the last report belong to the new day
            carry = 0L
        } else if (raw < last) {                          // the phone rebooted: the counter started again from zero
            carry += (last - base).coerceAtLeast(0L)
            base = 0L
        }
        p.edit().putLong("day", day).putLong("base", base).putLong("carry", carry).putLong("last", raw).apply()
    }

    /** Steps so far today, or null without the permission or a step sensor. */
    fun today(c: Context): Int? {
        ensure(c)
        if (!started) return null
        val p = sp(c)
        if (p.getLong("day", -1L) != LocalDate.now().toEpochDay()) return 0
        return (p.getLong("carry", 0L) + (p.getLong("last", 0L) - p.getLong("base", 0L))).coerceAtLeast(0L).toInt()
    }
}

/** Open-Meteo air quality for the Panchang location. The raw PM values are kept for a later India-scale index. */
object Aqi {
    fun refreshIfStale(c: Context) {
        val at = HudNativePrefs.long(c, "aqi_at", 0L)
        if (System.currentTimeMillis() - at < 60 * 60_000L) return
        val txt = Http.get(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${Prefs.lat(c)}&longitude=${Prefs.lon(c)}&current=us_aqi,pm2_5,pm10",
        ) ?: return
        runCatching {
            val o = JSONObject(txt).getJSONObject("current")
            if (o.isNull("us_aqi")) return
            HudNativePrefs.setNum(c, "aqi_v", Math.round(o.getDouble("us_aqi")).toInt())
            if (!o.isNull("pm2_5")) HudNativePrefs.setFloat(c, "aqi_pm25", o.getDouble("pm2_5").toFloat())
            if (!o.isNull("pm10")) HudNativePrefs.setFloat(c, "aqi_pm10", o.getDouble("pm10").toFloat())
            HudNativePrefs.setLong(c, "aqi_at", System.currentTimeMillis())
        }
    }
}

/**
 * The HUD's own complications. Each one decides when it is relevant and says nothing otherwise; [HudComposer] ranks them
 * and [HudPlan] places them. Ranking classes: 0 urgent, 1 timed (shown in their window), 3 ambient (steps, countdowns).
 */
object HudNative {
    private fun tap(action: String) = TapRef(HudSources.LOCAL, action)
    private const val MIN = 60_000L

    /** Hands up to ten minutes, then the bracket with the time: ✌🖐 ⌊7MIN, or ⌊1H 05M. */
    private fun countdown(minutes: Long, tap: TapRef?): List<Token> {
        val m = minutes.coerceAtLeast(1L).toInt()
        val out = ArrayList<Token>()
        HudRules.hands(m).forEach { out += GlyphToken(it, tap) }
        out += Timer(if (m >= 60) "${m / 60}H ${"%02d".format(m % 60)}M" else "${m}MIN", tap)
        return out
    }

    private fun minutesUntil(at: Long, now: Long) = (at - now + MIN - 1) / MIN

    private fun clock(c: Context, ms: Long, zone: ZoneId): String {
        val f = if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm"
        return DateTimeFormatter.ofPattern(f, Locale.US).format(Instant.ofEpochMilli(ms).atZone(zone)).uppercase(Locale.US)
    }

    // ---------------------------------------------------------------- sunrise and sunset

    /** [SUNSET IN ⌊20MIN] from 30 minutes before to 15 minutes after (both changeable), then SUNSET 17:31 until the window closes. */
    fun sun(c: Context, now: Long, zone: ZoneId, today: LocalDate): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.SUN, true)) return null
        val before = HudNativePrefs.num(c, HudNativePrefs.SUN_BEFORE, 30) * MIN
        val after = HudNativePrefs.num(c, HudNativePrefs.SUN_AFTER, 15) * MIN
        val lat = Prefs.lat(c)
        val lon = Prefs.lon(c)
        val events = listOf(
            "SUNRISE" to Astro.sunriseMs(today, zone, lat, lon),
            "SUNSET" to Astro.sunsetMs(today, zone, lat, lon),
        )
        val (name, at) = events.firstOrNull { (_, t) -> now >= t - before && now <= t + after } ?: return null
        val t = tap(HudSources.SETTINGS)
        val line = ArrayList<Token>()
        line += GlyphToken(HudGlyph.TWILIGHT, t)
        if (now < at) {
            line += Text("$name IN", t)
            line += countdown(minutesUntil(at, now), t)
        } else {
            line += Text(name, t)
            line += OutlinePill(clock(c, at, zone), t)
        }
        return Block("sun", listOf(line))
    }

    // ---------------------------------------------------------------- birthdays

    private class Bdays(val day: Long, val at: Long, val names: List<String>)
    @Volatile private var bdayCache: Bdays? = null

    private val BDAY_SUFFIX = Regex("(?i)\\s*['’]s\\s+(birthday|b-day|bday).*$")
    private val BDAY_WORD = Regex("(?i)\\b(birthday|b-day|bday)\\b[:\\s-]*")

    private fun nameOf(title: String): String {
        val t = title.trim()
        val stripped = BDAY_SUFFIX.replace(t, "")
        val name = if (stripped != t) stripped else t.replace(BDAY_WORD, "").replace("🎂", "").trim()
        return name.ifBlank { t }
    }

    /** All-day birthday events for today: Google's Birthdays calendar, or any all-day event with "birthday" in it. */
    private fun bdayNames(c: Context, now: Long, today: LocalDate): List<String> {
        val cached = bdayCache
        if (cached != null && cached.day == today.toEpochDay() && now - cached.at < 10 * MIN) return cached.names
        val start = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()      // all-day events live in UTC
        val names = runCatching {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, start)
            ContentUris.appendId(b, start + 24 * 3_600_000L)
            val out = LinkedHashSet<String>()
            c.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.OWNER_ACCOUNT,
                ),
                null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(1) != 1) continue
                    val title = cur.getString(0) ?: continue
                    val cal = cur.getString(2).orEmpty()
                    val owner = cur.getString(3).orEmpty()
                    val isBirthday = owner.contains("addressbook#contacts", true) || cal.contains("birthday", true) ||
                        title.contains("birthday", true) || title.contains("🎂")
                    if (isBirthday) out += nameOf(title)
                }
            }
            out.toList()
        }.getOrDefault(emptyList())
        bdayCache = Bdays(today.toEpochDay(), now, names)
        return names
    }

    /** 🎈 AMAN'S BIRTHDAY from midnight until [HudNativePrefs.BDAY_END] (09:00). No countdown; a tap dismisses it for the day. */
    fun birthday(c: Context, now: Long, today: LocalDate, hour: Int): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.BDAY, true)) return null
        if (hour >= HudNativePrefs.num(c, HudNativePrefs.BDAY_END, 9)) return null
        if (HudNativePrefs.long(c, HudNativePrefs.BDAY_DISMISSED, -1L) == today.toEpochDay()) return null
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val names = bdayNames(c, now, today)
        if (names.isEmpty()) return null
        val t = tap(HudSources.BDAY)
        val first = names[0].uppercase(Locale.getDefault())
        val short = ArrayList<Token>()
        short += GlyphToken(HudGlyph.BALLOON, t)
        short += Text(first, t)
        if (names.size > 1) short += OutlinePill("+${names.size - 1}", t)
        val folds = if (names.size == 1) listOf(listOf<Token>(GlyphToken(HudGlyph.BALLOON, t), Text("$first’S BIRTHDAY", t))) else emptyList()
        return Block("bday", listOf(short), folds = folds)
    }

    // ---------------------------------------------------------------- bedtime

    /** The Digital Wellbeing bedtime rule, as far as Android shows it: needs Notification Policy Access. */
    private fun dwRule(c: Context): AutomaticZenRule? = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return null
        val rules = nm.automaticZenRules.values
        rules.firstOrNull { Build.VERSION.SDK_INT >= 35 && it.type == AutomaticZenRule.TYPE_BEDTIME }
            ?: rules.firstOrNull { it.name.contains("bedtime", true) }
    }.getOrNull()

    private val CLOCK = Regex("(\\d{1,2})[:.](\\d{2})\\s*([AaPp])?\\.?[Mm]?\\.?")

    /** First clock time in the rule's own description of when it is active ("10:00 PM – 7:00 AM" -> 22:00), as a minute of the day. */
    private fun dwStart(r: AutomaticZenRule): Int? {
        val desc = if (Build.VERSION.SDK_INT >= 35) r.triggerDescription.orEmpty() else ""
        val m = CLOCK.find(desc) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val ap = m.groupValues[3].lowercase()
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    /** ZZZ BEDTIME IN ⌊25MIN, from [HudNativePrefs.BED_LEAD] minutes before until bedtime. Manual time, or synced with Digital Wellbeing (manual time as the fallback). */
    fun bedtime(c: Context, now: Long, zone: ZoneId, today: LocalDate): Block? {
        val mode = HudNativePrefs.num(c, HudNativePrefs.BED_MODE, 0)
        if (mode == 0) return null
        var minute = HudNativePrefs.num(c, HudNativePrefs.BED_MIN, 23 * 60)
        if (mode == 2) {
            val r = dwRule(c)
            if (r != null) {
                if (!r.isEnabled) return null
                dwStart(r)?.let { minute = it }
            }
        }
        val lead = HudNativePrefs.num(c, HudNativePrefs.BED_LEAD, 30) * MIN
        val bed = today.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        if (now > bed || now < bed - lead) return null
        val t = tap(HudSources.SETTINGS)
        return Block("bed", listOf(listOf<Token>(GlyphToken(HudGlyph.ZZZ, t), Text("BEDTIME IN", t)) + countdown(minutesUntil(bed, now), t)))
    }

    /** What the bedtime sync can see, for working out why it does not follow Digital Wellbeing. */
    fun bedtimeDump(c: Context): String = buildString {
        val nm = c.getSystemService(NotificationManager::class.java)
        appendLine("policy access=${nm.isNotificationPolicyAccessGranted} sdk=${Build.VERSION.SDK_INT}")
        val rules = runCatching { nm.automaticZenRules.values.toList() }.getOrElse { appendLine("error ${it.javaClass.simpleName}"); emptyList() }
        appendLine("rules=${rules.size}")
        for (r in rules) {
            val type = if (Build.VERSION.SDK_INT >= 35) r.type else -2
            val desc = if (Build.VERSION.SDK_INT >= 35) r.triggerDescription else null
            appendLine("  name=${r.name} type=$type enabled=${r.isEnabled} owner=${r.owner?.flattenToShortString()} desc=$desc start=${dwStart(r)}")
        }
    }

    // ---------------------------------------------------------------- steps

    /** [shoe pill] (6,432), and a thumbs up once the goal is reached. Ambient: it steps aside after N unlocks and returns when it changes by a thousand or hits the goal. */
    fun steps(c: Context): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.STEPS, true)) return null
        val n = HudSteps.today(c) ?: return null
        val reached = n >= HudNativePrefs.num(c, HudNativePrefs.STEPS_GOAL, 10_000)
        if (!HudRelevance.allow(c, "steps", "${n / 1000}:$reached")) return null
        val t = tap(HudSources.SETTINGS)
        val line = ArrayList<Token>()
        line += IconPill(GlyphKey(HudGlyph.STEPS), t)
        line += OutlinePill("%,d".format(Locale.US, n), t)
        if (reached) line += GlyphToken(HudGlyph.THUMB_UP, t)
        return Block("steps", listOf(line))
    }

    // ---------------------------------------------------------------- air quality and rain

    /** [MASK] AQI (168) while the index is at or above the threshold; it leaves once it is [HudNativePrefs.AQI_MARGIN] below it. */
    fun aqi(c: Context, now: Long): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.AQI, true)) return null
        val at = HudNativePrefs.long(c, "aqi_at", 0L)
        if (at <= 0L || now - at > 3 * 3_600_000L) return null
        val v = HudNativePrefs.num(c, "aqi_v", -1)
        if (v < 0) return null
        val thr = HudNativePrefs.num(c, HudNativePrefs.AQI_THR, 150)
        val margin = HudNativePrefs.num(c, HudNativePrefs.AQI_MARGIN, 5)
        val was = HudNativePrefs.flag(c, HudNativePrefs.AQI_ACTIVE, false)
        val on = v >= thr || (was && v >= thr - margin)
        if (on != was) HudNativePrefs.setFlag(c, HudNativePrefs.AQI_ACTIVE, on)
        if (!on) return null
        val t = tap(HudSources.SETTINGS)
        return Block("aqi", listOf(listOf<Token>(GlyphToken(HudGlyph.MASK, t), Text("AQI", t), OutlinePill("$v", t))))
    }

    private val RAINING = setOf("RAINY", "DRIZZLE", "SHOWERS", "STORM")

    /** [RAIN] RAIN IN ⌊20MIN when the next rain is within the window (an hour) and it is not already raining. */
    fun rain(c: Context, now: Long): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.RAIN, true)) return null
        val at = HudNativePrefs.long(c, "rain_at", 0L)
        val fetched = HudNativePrefs.long(c, "rain_fetched", 0L)
        if (at <= 0L || now - fetched > 45 * MIN) return null
        if ((Prefs.weather(c)?.second ?: "") in RAINING) return null
        val mins = minutesUntil(at, now)
        if (mins < 1 || mins > HudNativePrefs.num(c, HudNativePrefs.RAIN_WIN, 60)) return null
        val t = tap(HudSources.SETTINGS)
        return Block("rain", listOf(listOf<Token>(GlyphToken(HudGlyph.RAIN, t), Text("RAIN IN", t)) + countdown(mins, t)))
    }

    // ---------------------------------------------------------------- next event and alarm

    /**
     * [SPEAK] TITLE [→] / STARTING IN ✌🖐 ⌊7MIN. Ambient until ten minutes before (then it is urgent and ignores the unlock rule).
     * Returns the ranking class with the block.
     */
    fun event(c: Context, now: Long): Pair<Int, Block>? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.EVENT, true)) return null
        val ev = HudCalendar.next(c, now) ?: return null
        val mins = minutesUntil(ev.begin, now)
        if (mins > HudNativePrefs.num(c, HudNativePrefs.EVENT_LEAD, 60)) return null
        val imminent = mins <= 10
        val ok = HudRelevance.allow(c, "event", "${ev.title}:${ev.begin}:$imminent")
        if (!imminent && !ok) return null
        val t = tap(HudSources.EVENT)
        val title = listOf<Token>(GlyphToken(HudGlyph.SPEAK, t), Text(ev.title.uppercase(Locale.getDefault()), t), GlyphToken(HudGlyph.FOLLOWUP, t))
        val second: List<Token> = if (mins <= 0) listOf(Text("STARTING NOW", t)) else listOf<Token>(Text("STARTING IN", t)) + countdown(mins, t)
        return (if (imminent) 0 else 3) to Block("event", listOf(title, second), kind = "calendar")
    }

    /** [ALARM] ALARM IN ⌊1H 20M, within [HudNativePrefs.ALARM_LEAD] minutes of the next alarm clock. Ambient. */
    fun alarm(c: Context, now: Long): Block? {
        if (!HudNativePrefs.flag(c, HudNativePrefs.ALARM, true)) return null
        val at = runCatching { c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull() ?: return null
        val mins = minutesUntil(at, now)
        if (mins < 1 || mins > HudNativePrefs.num(c, HudNativePrefs.ALARM_LEAD, 120)) return null
        if (!HudRelevance.allow(c, "alarm", "$at")) return null
        val t = tap(HudSources.ALARM)
        return Block("alarm", listOf(listOf<Token>(GlyphToken(HudGlyph.ALARM, t), Text("ALARM IN", t)) + countdown(mins, t)))
    }

    // ---------------------------------------------------------------- scheduling

    /** The next moment something starts or stops being relevant, so the HUD can wake exactly then. */
    fun nextEdge(c: Context, now: Long, zone: ZoneId): Long? {
        val today = LocalDate.now(zone)
        val edges = ArrayList<Long>()
        if (HudNativePrefs.flag(c, HudNativePrefs.SUN, true)) {
            val before = HudNativePrefs.num(c, HudNativePrefs.SUN_BEFORE, 30) * MIN
            val after = HudNativePrefs.num(c, HudNativePrefs.SUN_AFTER, 15) * MIN
            for (d in listOf(today, today.plusDays(1))) for (t in listOf(Astro.sunriseMs(d, zone, Prefs.lat(c), Prefs.lon(c)), Astro.sunsetMs(d, zone, Prefs.lat(c), Prefs.lon(c)))) {
                edges += t - before; edges += t + after
            }
        }
        if (HudNativePrefs.flag(c, HudNativePrefs.BDAY, true)) {
            edges += today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            edges += today.atStartOfDay(zone).plusHours(HudNativePrefs.num(c, HudNativePrefs.BDAY_END, 9).toLong()).toInstant().toEpochMilli()
        }
        if (HudNativePrefs.num(c, HudNativePrefs.BED_MODE, 0) != 0) {
            val bed = today.atStartOfDay(zone).plusMinutes(HudNativePrefs.num(c, HudNativePrefs.BED_MIN, 23 * 60).toLong()).toInstant().toEpochMilli()
            edges += bed - HudNativePrefs.num(c, HudNativePrefs.BED_LEAD, 30) * MIN; edges += bed
        }
        HudCalendar.next(c, now)?.let { ev ->
            edges += ev.begin - HudNativePrefs.num(c, HudNativePrefs.EVENT_LEAD, 60) * MIN
            edges += ev.begin - 10 * MIN; edges += ev.begin + 5 * MIN
        }
        runCatching { c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()?.let { at ->
            edges += at - HudNativePrefs.num(c, HudNativePrefs.ALARM_LEAD, 120) * MIN; edges += at
        }
        return edges.filter { it > now + 1_000L }.minOrNull()
    }

    /** Arms one alarm for [nextEdge]; the HUD refreshes then and arms the next. */
    fun armEdges(c: Context) {
        if (!HudPrefs.usesNative(c)) return
        val at = nextEdge(c, System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
        Sched.arm(c.applicationContext, HudWidget::class.java, HudWidget.ACTION_TICK, at + 1_500L, 71)
    }
}
