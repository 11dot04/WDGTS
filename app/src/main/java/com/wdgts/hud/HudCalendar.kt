package com.wdgts.hud

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

object HudCalendar {
    data class Ev(val title: String, val begin: Long)

    /** Next timed event in the coming 12 hours (needs the calendar permission; null otherwise). */
    fun next(c: Context, now: Long): Ev? {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now - 5 * 60_000L)
            ContentUris.appendId(b, now + 12 * 3_600_000L)
            c.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cur ->
                var found: Ev? = null
                while (found == null && cur.moveToNext()) {
                    if (cur.getInt(2) == 1) continue
                    val begin = cur.getLong(1)
                    if (begin >= now - 5 * 60_000L) found = Ev(cur.getString(0) ?: "EVENT", begin)
                }
                found
            }
        }.getOrNull()
    }
}
