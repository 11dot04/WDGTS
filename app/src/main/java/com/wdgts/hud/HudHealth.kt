package com.wdgts.hud

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Steps from Health Connect, the same total your watch or Google Fit shows. Read off the main thread at most every
 * couple of minutes and cached, so drawing the HUD never waits for it.
 */
object HudHealth {
    val STEPS_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
    val PERMISSIONS: Set<String> = setOf(STEPS_PERMISSION, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

    private val running = AtomicBoolean(false)

    fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

    /** Which of our permissions are granted (blocking: call it off the main thread). */
    fun granted(c: Context): Set<String> = runCatching {
        runBlocking { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions() }
    }.getOrDefault(emptySet())

    /** Today's steps as last read, or null when nothing has been read today. */
    fun cached(c: Context): Int? {
        val p = HudNativePrefs
        if (p.long(c, "hc_day", -1L) != LocalDate.now().toEpochDay()) return null
        return p.num(c, "hc_steps", -1).takeIf { it >= 0 }
    }

    /** Starts a read when the cached one is older than two minutes. */
    fun refreshAsync(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (now - HudNativePrefs.long(app, "hc_at", 0L) < 2 * 60_000L || !available(app)) return
        if (!running.compareAndSet(false, true)) return
        thread(name = "hud-health") {
            try {
                HudNativePrefs.setLong(app, "hc_at", now)
                val client = HealthConnectClient.getOrCreate(app)
                val ok = runBlocking { client.permissionController.getGrantedPermissions() }.contains(STEPS_PERMISSION)
                if (!ok) return@thread
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                val r = runBlocking {
                    client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
                }
                val n = (r[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                val changed = n != HudNativePrefs.num(app, "hc_steps", -1) || HudNativePrefs.long(app, "hc_day", -1L) != LocalDate.now().toEpochDay()
                HudNativePrefs.setNum(app, "hc_steps", n)
                HudNativePrefs.setLong(app, "hc_day", LocalDate.now().toEpochDay())
                if (changed) HudWidget().refreshAll(app)
            } catch (_: Throwable) {
                // no Health Connect, no permission, or a read refused in the background: the HUD keeps what it had
            } finally {
                running.set(false)
            }
        }
    }
}
