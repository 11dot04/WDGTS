package com.wdgts.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Inexact wake-ups (no special permission). Doze may stretch them to a few minutes. */
object Sched {
    fun arm(c: Context, cls: Class<*>, action: String, atMs: Long, code: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, code, Intent(c, cls).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }
}
