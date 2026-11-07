package com.wdgts.hud

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.StatusBarNotification

/** Live signals the HUD reads. Written by [HudHooks] and the notification listener; no persistence needed. */
object HudSignals {
    @Volatile var screenOnSince = 0L
    @Volatile var lastScreenOff = 0L
    @Volatile var lookDismissedAt = 0L
    @Volatile var lastWifiAt = System.currentTimeMillis()
    @Volatile var onWifi = true
    @Volatile var hasCellular = false
    @Volatile var torchOn = false
    @Volatile var missed: List<String> = emptyList()
    @Volatile var counts: Map<String, Int> = emptyMap()

    /** Recounts missed calls and per-app notifications from the active list. */
    fun rescan(list: List<StatusBarNotification>) {
        val cnt = HashMap<String, Int>()
        val miss = ArrayList<String>()
        for (sbn in list) {
            val n = sbn.notification ?: continue
            if (n.category == "missed_call") {
                val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                miss += if (title.contains("issed", ignoreCase = true) && text.isNotBlank()) text else title.ifBlank { "UNKNOWN" }
                continue
            }
            if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            cnt[sbn.packageName] = (cnt[sbn.packageName] ?: 0) + 1
        }
        missed = miss
        counts = cnt
    }

    fun smsCount(c: Context): Int = Telephony.Sms.getDefaultSmsPackage(c)?.let { counts[it] } ?: 0
}
