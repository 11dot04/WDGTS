package com.wdgts.widgets

import android.content.Context
import com.wdgts.core.Prefs

/**
 * Streak state + rules. A tile only knows what you tell it (tap the tile, or set it in settings), so
 * nothing resets unless that tile's rule says so:
 *  - earn:  shields gained for each completed day (Brilliant: 1)
 *  - cap:   most shields you can hold
 *  - grace: missed days forgiven for free before shields are spent
 *  - autoReset: when off the count just waits for you; when on, missed days spend grace, then shields, then zero it
 */
object Streaks {
    const val SEED_VERSION = 2

    data class Rule(val earn: Int, val cap: Int, val grace: Int, val autoReset: Boolean)
    data class View(val count: Int, val doneToday: Boolean, val shields: Int)

    fun defaultRule(def: StreakDef) = if (def.id == "brilliant") Rule(1, 2, 0, true) else Rule(0, 0, 0, false)

    fun rule(c: Context, def: StreakDef): Rule {
        val s = Prefs.raw(c)
        val d = defaultRule(def)
        return Rule(
            s.getInt("${def.id}_earn", d.earn), s.getInt("${def.id}_cap", d.cap),
            s.getInt("${def.id}_grace", d.grace), s.getBoolean("${def.id}_reset", d.autoReset),
        )
    }

    fun setRule(c: Context, def: StreakDef, r: Rule) {
        Prefs.raw(c).edit().putInt("${def.id}_earn", r.earn).putInt("${def.id}_cap", r.cap)
            .putInt("${def.id}_grace", r.grace).putBoolean("${def.id}_reset", r.autoReset).apply()
    }

    /** One-off: re-import the seeds (the old rule had zeroed them). Bump SEED_VERSION to re-import again. */
    private fun migrate(c: Context, today: Long) {
        val s = Prefs.raw(c)
        if (s.getInt("seed_v", 0) >= SEED_VERSION) return
        val e = s.edit()
        for (d in STREAKS) if (d.seed > 0) e.putInt("${d.id}_n", d.seed).putLong("${d.id}_last", today - 1).putInt("${d.id}_shield", 0)
        e.putInt("seed_v", SEED_VERSION).apply()
    }

    /** Brings the stored state up to [today], applying the rule to missed days exactly once. */
    fun state(c: Context, def: StreakDef, today: Long): View {
        migrate(c, today)
        val s = Prefs.raw(c)
        val id = def.id
        if (!s.contains("${id}_n")) {
            s.edit().putInt("${id}_n", def.seed).putLong("${id}_last", today - 1).putInt("${id}_shield", 0).apply()
        }
        var n = s.getInt("${id}_n", 0)
        var last = s.getLong("${id}_last", today - 1)
        var sh = s.getInt("${id}_shield", 0)
        val r = rule(c, def)
        val missed = (today - last - 1).toInt()
        if (missed > 0 && r.autoReset) {
            var m = (missed - r.grace).coerceAtLeast(0)
            while (m > 0 && sh > 0) { sh--; m-- }
            if (m > 0) { n = 0; sh = 0 }
            last = today - 1
            s.edit().putInt("${id}_n", n).putLong("${id}_last", last).putInt("${id}_shield", sh).apply()
        }
        return View(n, last == today, sh)
    }

    fun markDone(c: Context, def: StreakDef, today: Long) {
        val v = state(c, def, today)
        if (v.doneToday) return
        val r = rule(c, def)
        Prefs.raw(c).edit().putInt("${def.id}_n", v.count + 1).putLong("${def.id}_last", today)
            .putInt("${def.id}_shield", minOf(r.cap, v.shields + r.earn)).apply()
    }

    fun setCount(c: Context, def: StreakDef, count: Int, shields: Int) {
        Prefs.raw(c).edit().putInt("${def.id}_n", count.coerceAtLeast(0)).putInt("${def.id}_shield", shields.coerceAtLeast(0)).apply()
    }
}
