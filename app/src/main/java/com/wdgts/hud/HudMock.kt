package com.wdgts.hud

import android.content.Context
import com.wdgts.hud.engine.Block
import com.wdgts.sports.Crest
import com.wdgts.sports.Match
import com.wdgts.widgets.Sched
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Sample complications for checking how they look without waiting for the real thing (a match, a sunset, a high AQI).
 * One sample at a time is put at the top of the HUD, in the preview and on the home screen, and it goes away by itself
 * after ten minutes so it is never left on by accident.
 */
object HudMock {
    class Case(val id: String, val label: String)
    class Group(val title: String, val cases: List<Case>)

    val GROUPS = listOf(
        Group(
            "Football",
            listOf(Case("foot_live", "Live"), Case("foot_goal", "Goal"), Case("foot_pre", "Kickoff"), Case("foot_ft", "Full time"), Case("foot_stoppage", "Stoppage")),
        ),
        Group("Sunrise and sunset", listOf(Case("sun_before", "In 20 min"), Case("sun_close", "In 7 min"), Case("sun_after", "Just after"))),
        Group("Birthdays", listOf(Case("bday_one", "One"), Case("bday_many", "Several"))),
        Group("Bedtime", listOf(Case("bed", "In 25 min"), Case("bed_close", "In 6 min"))),
        Group("Steps", listOf(Case("steps", "6,432"), Case("steps_goal", "Goal reached"))),
        Group("Air and rain", listOf(Case("aqi", "High AQI"), Case("rain", "Rain soon"))),
        Group("Event and alarm", listOf(Case("event", "In 40 min"), Case("event_soon", "In 7 min"), Case("event_now", "Now"), Case("alarm", "Alarm"))),
    )

    private const val LIFETIME_MS = 10 * 60_000L
    private const val ARSENAL = "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"
    private const val CHELSEA = "https://a.espncdn.com/i/teamlogos/soccer/500/363.png"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hud_mock", Context.MODE_PRIVATE)

    /** The sample on show, or null once it has expired. */
    fun current(c: Context): String? {
        val p = sp(c)
        val id = p.getString("id", null) ?: return null
        return if (System.currentTimeMillis() < p.getLong("until", 0L)) id else null
    }

    /** Shows [id] (null clears it), fetches the two crests the football samples use and refreshes the HUD. */
    fun set(c: Context, id: String?) {
        val app = c.applicationContext
        val until = System.currentTimeMillis() + LIFETIME_MS
        sp(app).edit().putString("id", id).putLong("until", if (id == null) 0L else until).apply()
        if (id != null) Sched.arm(app, HudWidget::class.java, HudWidget.ACTION_TICK, until + 1_000L, 73)
        HudWidget().refreshAll(app)
        if (id != null && id.startsWith("foot")) thread(name = "hud-mock-crests") {
            Crest.ensure(app, ARSENAL); Crest.ensure(app, CHELSEA)
            HudWidget().refreshAll(app)
        }
    }

    private fun match(state: String, hs: String, aw: String, detail: String, now: Long) = Match(
        "mock", "eng.1", state, if (state == "pre") now + 25 * 60_000L else now - 70 * 60_000L,
        "Arsenal", "Chelsea", "ARS", "CHE", hs, aw, detail, ARSENAL, CHELSEA, true, "", detail,
    )

    /** The sample's block with the ranking class it would have for real, or null when none is on. */
    fun block(c: Context, now: Long, zone: ZoneId): Pair<Int, Block>? {
        val id = current(c) ?: return null
        fun f(m: Match, scorer: Int = 0, cls: Int = 0) = cls to HudFootball.blockFor(c, m, zone, scorer)
        return when (id) {
            "foot_live" -> f(match("in", "2", "1", "67'", now))
            "foot_goal" -> f(match("in", "2", "1", "67'", now), scorer = 1)
            "foot_stoppage" -> f(match("in", "1", "1", "90'+4'", now))
            "foot_pre" -> f(match("pre", "", "", "", now), cls = 1)
            "foot_ft" -> f(match("post", "3", "1", "FT", now), cls = 1)
            "sun_before" -> 1 to HudNative.sunBlock(c, "SUNSET", now + 20 * 60_000L, now, zone)
            "sun_close" -> 1 to HudNative.sunBlock(c, "SUNSET", now + 7 * 60_000L, now, zone)
            "sun_after" -> 1 to HudNative.sunBlock(c, "SUNSET", now - 4 * 60_000L, now, zone)
            "bday_one" -> 1 to HudNative.bdayBlock(listOf("Aman"))
            "bday_many" -> 1 to HudNative.bdayBlock(listOf("Aman", "Priya", "Rohan"))
            "bed" -> 1 to HudNative.bedBlock(25)
            "bed_close" -> 1 to HudNative.bedBlock(6)
            "steps" -> 3 to HudNative.stepsBlock(6432, false)
            "steps_goal" -> 3 to HudNative.stepsBlock(10250, true)
            "aqi" -> 1 to HudNative.aqiBlock(168)
            "rain" -> 1 to HudNative.rainBlock(20)
            "event" -> HudNative.eventBlock("Weekly design sync", 40)
            "event_soon" -> HudNative.eventBlock("Weekly design sync", 7)
            "event_now" -> HudNative.eventBlock("Weekly design sync", 0)
            "alarm" -> 3 to HudNative.alarmBlock(80)
            else -> null
        }
    }
}
