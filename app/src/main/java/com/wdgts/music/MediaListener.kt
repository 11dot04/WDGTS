package com.wdgts.music

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wdgts.hud.HudHooks
import com.wdgts.hud.HudSignals
import com.wdgts.hud.HudWidget
import kotlin.concurrent.thread

/**
 * Needs notification access (that is what lets an app see other apps' media sessions).
 * Mirrors the active session into [MusicState] and nudges the widgets; while playing it refreshes
 * every few seconds so the progress ring/bar advances.
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var msm: MediaSessionManager? = null
    private val hud = HudHooks(this)

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = sync()
        override fun onPlaybackStateChanged(state: PlaybackState?) = sync()
        override fun onSessionDestroyed() = sync()
    }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    private val tick = object : Runnable {
        override fun run() {
            if (MusicState.playing) MusicWidgets.refreshAll(this@MediaListener)
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = ComponentName(this, MediaListener::class.java)
        runCatching { m.addOnActiveSessionsChangedListener(sessionsListener, cn) }
        attach(runCatching { m.getActiveSessions(cn) }.getOrDefault(emptyList()))
        handler.removeCallbacks(tick)
        handler.post(tick)
        hud.attach()
        rescan()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = rescan()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rescan()

    /** Missed calls and per-app notification counts for the HUD. */
    private fun rescan() {
        HudSignals.rescan(runCatching { activeNotifications?.toList() }.getOrNull() ?: emptyList())
        HudWidget().refreshAll(this)
    }

    override fun onListenerDisconnected() {
        hud.detach()
        handler.removeCallbacks(tick)
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        sync()
    }

    private fun sync() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        if (c == null) {
            MusicState.hasSession = false
            MusicState.playing = false
            MusicWidgets.refreshAll(this)
            return
        }
        val md = c.metadata
        val pb = c.playbackState
        val oldKey = MusicState.key
        MusicState.title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        MusicState.artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        MusicState.pkg = c.packageName ?: ""
        MusicState.art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        MusicState.durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        MusicState.playing = pb?.state == PlaybackState.STATE_PLAYING
        MusicState.posMs = pb?.position ?: 0L
        MusicState.posTime = pb?.lastPositionUpdateTime ?: 0L
        MusicState.speed = pb?.playbackSpeed ?: 1f
        MusicState.hasSession = MusicState.title.isNotEmpty()

        val newKey = MusicState.key
        if (newKey != oldKey || MusicState.cutoutKey != newKey) {
            if (newKey != oldKey) MusicState.cutout = null
            val art = MusicState.art
            if (art != null) thread(name = "wdgts-cutout") {
                val cut = Cutout.compute(applicationContext, art, newKey)
                if (MusicState.key == newKey) {
                    MusicState.cutout = cut
                    MusicState.cutoutKey = newKey
                    handler.post { MusicWidgets.refreshAll(this) }
                }
            }
        }
        MusicWidgets.refreshAll(this)
    }
}
