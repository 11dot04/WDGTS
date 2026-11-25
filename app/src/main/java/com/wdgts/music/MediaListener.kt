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
import com.wdgts.core.SubjectFit
import com.wdgts.hud.HudHooks
import com.wdgts.hud.HudPrefs
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
    private var beat = 0

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = sync()
        override fun onPlaybackStateChanged(state: PlaybackState?) = sync()
        override fun onSessionDestroyed() = sync()
    }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    private val tick = object : Runnable {
        override fun run() {
            if (MusicState.playing) {
                MusicWidgets.refreshAll(this@MediaListener)
                // the HUD's progress rings only need a redraw now and then
                if (++beat % 6 == 0 && HudPrefs.musicLayout(this@MediaListener).let { it == 0 || it == 4 }) HudWidget().refreshAll(this@MediaListener)
            }
            // with synced lyrics on a Depth widget, wake up for the next line instead of every five seconds
            val next = if (MusicState.playing && MusicWidgets.depthPlaced(this@MediaListener)) Lyrics.msToNext(MusicState.positionMs()) else -1L
            handler.postDelayed(this, if (next > 0) next.coerceIn(700L, 5_000L) else 5_000L)
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

    private var cutoutFor = ""                              // the art being segmented right now
    private val cutoutTries = HashMap<String, Int>()
    private var textFor = ""                                // the art whose lettering is being read right now
    private val textTries = HashMap<String, Int>()

    private fun sync() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        if (c == null) {
            MusicState.hasSession = false
            MusicState.playing = false
            MusicState.controller = null
            MusicWidgets.refreshAll(this)
            return
        }
        MusicState.controller = c
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
        val art = MusicState.art
        val sig = SubjectFit.fingerprint(art)
        val want = "$newKey:$sig"
        if (newKey != oldKey || sig != MusicState.artSig) {              // another song, or the same song with new art
            MusicState.cutout = null
            MusicState.cutoutSig = ""
            MusicState.coverText = emptyList()
            MusicState.coverTextSig = ""
        }
        MusicState.artSig = sig
        if (art != null && MusicState.cutoutSig != want && cutoutFor != want) {
            cutoutFor = want
            thread(name = "wdgts-cutout") {
                val r = Cutout.computeFor(applicationContext, art, newKey, sig)
                if (MusicState.key == newKey && MusicState.artSig == sig) when (r) {
                    is Cutout.Result.Ok -> { MusicState.cutout = r.bitmap; MusicState.cutoutSig = want; MusicState.cutoutNote = r.note }
                    is Cutout.Result.None -> { MusicState.cutout = null; MusicState.cutoutSig = want; MusicState.cutoutNote = r.note }
                    Cutout.Result.Retry -> {
                        MusicState.cutoutNote = "failed, will retry"
                        val n = (cutoutTries[want] ?: 0) + 1
                        cutoutTries[want] = n
                        if (n <= 3) handler.postDelayed({ sync() }, 20_000L)
                    }
                }
                cutoutFor = ""
                handler.post { MusicWidgets.refreshAll(this); HudWidget().refreshAll(this) }
            }
        }
        if (art != null && MusicState.coverTextSig != want && textFor != want) {
            textFor = want
            thread(name = "wdgts-covertext") {
                val boxes = CoverText.scan(applicationContext, art, newKey, sig)
                if (MusicState.key == newKey && MusicState.artSig == sig) {
                    if (boxes != null) {
                        MusicState.coverText = boxes
                        MusicState.coverTextSig = want
                    } else {
                        val n = (textTries[want] ?: 0) + 1
                        textTries[want] = n
                        if (n <= 2) handler.postDelayed({ sync() }, 20_000L)
                    }
                }
                textFor = ""
                handler.post { MusicWidgets.refreshAll(this) }
            }
        }
        Lyrics.ensure(this)
        MusicWidgets.refreshAll(this)
    }
}
