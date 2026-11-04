package com.wdgts.widgets

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.wdgts.core.Prefs

/** Finds the installed app for a streak tile by package or by (part of) its name; cached. */
object AppResolver {
    fun resolve(c: Context, def: StreakDef): String {
        if (def.web) return ""
        val pm = c.packageManager
        if (def.pkg.isNotEmpty() && pm.getLaunchIntentForPackage(def.pkg) != null) return def.pkg
        if (def.match.isEmpty()) return ""
        Prefs.resolvedPkg(c, def.id)?.let { if (pm.getLaunchIntentForPackage(it) != null) return it }
        // negative results are cached for an hour so rendering doesn't re-scan every time
        if (System.currentTimeMillis() - Prefs.missTime(c, def.id) < 3_600_000L) return ""
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .firstOrNull { it.loadLabel(pm).toString().contains(def.match, ignoreCase = true) }
            ?.activityInfo?.packageName
        if (hit != null) { Prefs.setResolvedPkg(c, def.id, hit); return hit }
        Prefs.setMissTime(c, def.id, System.currentTimeMillis())
        return ""
    }
}
