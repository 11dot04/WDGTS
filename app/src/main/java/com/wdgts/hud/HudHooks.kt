package com.wdgts.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wdgts.core.Prefs
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Dynamic receivers (USER_PRESENT, screen on/off and the per-minute TIME_TICK can't be declared in the manifest),
 * so they live for as long as the notification listener does. Feeds [HudSignals] and refreshes the HUD.
 */
class HudHooks(private val c: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastWeather = 0L
    private var attached = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> {
                    val day = LocalDate.now().toEpochDay()
                    if (Prefs.hudUnlockDay(c) != day) Prefs.setHudUnlock(c, day, now)       // first unlock of the day
                }
                Intent.ACTION_SCREEN_ON -> if (now - HudSignals.lastScreenOff > 20_000L || HudSignals.screenOnSince == 0L) HudSignals.screenOnSince = now
                Intent.ACTION_SCREEN_OFF -> HudSignals.lastScreenOff = now
                Intent.ACTION_TIME_TICK -> if (now - lastWeather > 30 * 60_000L) {
                    lastWeather = now
                    thread { Weather.refreshIfStale(c); refresh() }
                }
            }
            refresh()
        }
    }

    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { HudSignals.torchOn = enabled; refresh() }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            HudSignals.onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            HudSignals.hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (HudSignals.onWifi) HudSignals.lastWifiAt = System.currentTimeMillis()
        }
        override fun onLost(network: Network) { HudSignals.onWifi = false; HudSignals.hasCellular = false }
    }

    private fun refresh() = HudWidget().refreshAll(c)

    fun attach() {
        if (attached) return
        attached = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_TIME_TICK)
        }
        c.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        runCatching { c.getSystemService(CameraManager::class.java).registerTorchCallback(torch, handler) }
        runCatching { c.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net) }
        if (c.getSystemService(PowerManager::class.java).isInteractive && HudSignals.screenOnSince == 0L) {
            HudSignals.screenOnSince = System.currentTimeMillis()
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        runCatching { c.unregisterReceiver(receiver) }
        runCatching { c.getSystemService(CameraManager::class.java).unregisterTorchCallback(torch) }
        runCatching { c.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) }
    }
}
