package com.wdgts.hud

import android.content.Context
import android.text.format.DateFormat
import com.wdgts.core.Prefs
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.ScorePill
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Token
import com.wdgts.sports.Crest
import com.wdgts.sports.Espn
import com.wdgts.sports.Match
import com.wdgts.widgets.Sched
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A team crest drawn by the host: [mode] is one of [HudFootball] CREST_*. */
data class CrestKey(val url: String, val mode: Int)

/**
 * Football on the HUD from the same ESPN data and team as the Team widget (they share the cached match):
 *   [crest] ARS 2 (67') 1 CHE [crest]       live, with the minute knocked out of a pill between the scores
 *   [crest] ARS (20:00) CHE [crest]         before kickoff
 *   [crest] ARS 2 (FT) 1 CHE [crest]        after the final whistle
 * A goal puts the new number in a pill for two minutes. It shows from [FOOT_BEFORE] minutes before kickoff until
 * [FOOT_AFTER] minutes after full time, and it is urgent while the match is live.
 */
object HudFootball {
    const val FOOT = "foot_on"
    const val FOOT_BEFORE = "foot_before"
    const val FOOT_AFTER = "foot_after"
    const val FOOT_CREST = "foot_crest"

    const val CREST_OFF = 0
    const val CREST_COLOUR = 1
    const val CREST_MONO = 2
    const val CREST_RING = 3

    private const val MIN = 60_000L
    private const val GOAL_MS = 2 * MIN
    private const val MATCH_LENGTH_MS = 115 * MIN       // kickoff to the final whistle, roughly: ESPN gives no end time

    private fun tap() = TapRef(HudSources.LOCAL, HudSources.MATCH)

    fun match(c: Context): Match? = Prefs.matchJson(c)?.let { Match.fromJson(it) }

    /** The ranking class for [m] right now (0 live, 1 before and after) or null when it is outside its window. */
    private fun cls(c: Context, m: Match, now: Long): Int? {
        val before = HudNativePrefs.num(c, FOOT_BEFORE, 180) * MIN
        val after = HudNativePrefs.num(c, FOOT_AFTER, 180) * MIN
        return when (m.state) {
            "in" -> 0
            "pre" -> if (m.kickoffMs - now <= before && now - m.kickoffMs < 3 * 60 * MIN) 1 else null
            else -> if (now - (m.kickoffMs + MATCH_LENGTH_MS) <= after) 1 else null
        }
    }

    private fun clean(s: String) = s.replace(" ", "").uppercase(Locale.getDefault())

    private fun middle(c: Context, m: Match, zone: ZoneId): String = when (m.state) {
        "pre" -> {
            val f = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm"
            DateTimeFormatter.ofPattern(f, Locale.US).format(Instant.ofEpochMilli(m.kickoffMs).atZone(zone)).uppercase(Locale.US)
        }
        "in" -> clean(m.detail.ifBlank { m.clock }).ifBlank { "LIVE" }
        else -> clean(m.detail).ifBlank { "FT" }
    }

    /** Which side scored last and when, kept between refreshes so the new number can be highlighted for a couple of minutes. */
    private fun goalSide(c: Context, m: Match, now: Long): Int {
        val h = m.homeScore.toIntOrNull() ?: return 0
        val a = m.awayScore.toIntOrNull() ?: return 0
        val key = "${m.id}:$h-$a"
        val last = HudNativePrefs.long(c, "foot_goal_at", 0L)
        val seen = c.applicationContext.getSharedPreferences("hud_native", Context.MODE_PRIVATE).getString("foot_seen", null)
        if (seen != key) {
            val prev = seen?.takeIf { it.startsWith("${m.id}:") }?.substringAfter(':')?.split('-')?.mapNotNull { it.toIntOrNull() }
            c.applicationContext.getSharedPreferences("hud_native", Context.MODE_PRIVATE).edit().putString("foot_seen", key).apply()
            if (prev != null && prev.size == 2 && (h > prev[0] || a > prev[1])) {
                HudNativePrefs.setLong(c, "foot_goal_at", now)
                HudNativePrefs.setNum(c, "foot_goal_side", if (h > prev[0]) 1 else 2)
            }
        }
        val at = HudNativePrefs.long(c, "foot_goal_at", last)
        return if (m.state == "in" && now - at < GOAL_MS) HudNativePrefs.num(c, "foot_goal_side", 0) else 0
    }

    fun block(c: Context, now: Long, zone: ZoneId): Pair<Int, Block>? {
        if (!HudNativePrefs.flag(c, FOOT, true)) return null
        val m = match(c) ?: return null
        val cls = cls(c, m, now) ?: return null
        return cls to blockFor(c, m, zone, goalSide(c, m, now))
    }

    /** The line for [m]; [scorer] is 1 or 2 to put the home or away number in a pill (a goal just went in). */
    fun blockFor(c: Context, m: Match, zone: ZoneId, scorer: Int): Block {
        val t = tap()
        val mode = HudNativePrefs.num(c, FOOT_CREST, CREST_MONO)
        val shown = m.state != "pre"

        fun number(score: String, side: Int): Token =
            if (scorer == side) ScorePill(score, t) else Text(score, t)

        fun line(withCrests: Boolean): List<Token> {
            val out = ArrayList<Token>()
            if (withCrests && mode != CREST_OFF && m.homeLogo.isNotBlank()) out += Icon(CrestKey(m.homeLogo, mode), t)
            out += Text(m.homeAbbr.uppercase(Locale.getDefault()), t)
            if (shown) out += number(m.homeScore.ifBlank { "0" }, 1)
            out += if (scorer != 0) OutlinePill(middle(c, m, zone), t) else ScorePill(middle(c, m, zone), t)
            if (shown) out += number(m.awayScore.ifBlank { "0" }, 2)
            out += Text(m.awayAbbr.uppercase(Locale.getDefault()), t)
            if (withCrests && mode != CREST_OFF && m.awayLogo.isNotBlank()) out += Icon(CrestKey(m.awayLogo, mode), t)
            return out
        }

        // the crests are the first thing to go when the line is too long
        return Block("football", listOf(line(false)), folds = listOf(line(true)))
    }

    // ---------------------------------------------------------------- polling

    /** How soon the cached match is worth fetching again. */
    private fun interval(m: Match?, now: Long): Long = when {
        m == null -> 45 * MIN
        m.state == "in" -> 55_000L
        m.state == "pre" -> {
            val d = m.kickoffMs - now
            when {
                d <= 0 -> 55_000L
                d < 20 * MIN -> 100_000L
                d < 3 * 60 * MIN -> 20 * MIN
                else -> 45 * MIN
            }
        }
        now - m.kickoffMs < 4 * 60 * MIN -> 10 * MIN
        else -> 60 * MIN
    }

    /**
     * Fetches the match when it is due (blocking: call it off the main thread), shares the result with the Team widget and
     * arms the next wake-up. Returns true when something new was fetched.
     */
    fun sync(c: Context): Boolean {
        val app = c.applicationContext
        if (!HudPrefs.usesNative(app) || !HudNativePrefs.flag(app, FOOT, true)) return false
        val now = System.currentTimeMillis()
        val cur = match(app)
        val last = HudNativePrefs.long(app, "foot_fetched", 0L)
        var fetched = false
        if (now - last >= interval(cur, now) - 5_000L) {
            val f = Espn.fetch(app)
            HudNativePrefs.setLong(app, "foot_fetched", now)
            if (f.ok) {
                Prefs.setMatchJson(app, f.match?.toJson())
                f.match?.let { m -> Crest.ensure(app, m.homeLogo); Crest.ensure(app, m.awayLogo) }
                fetched = true
            }
        }
        val m = match(app)
        val wake = HudNativePrefs.long(app, "foot_fetched", now) + interval(m, System.currentTimeMillis())
        Sched.arm(app, HudWidget::class.java, HudWidget.ACTION_TICK, maxOf(wake, System.currentTimeMillis() + 30_000L), 72)
        return fetched
    }
}
