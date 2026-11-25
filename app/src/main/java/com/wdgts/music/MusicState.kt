package com.wdgts.music

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock

/** In-memory now-playing snapshot, written by MediaListener, read by the three music widgets. */
object MusicState {
    @Volatile var hasSession = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var pkg = ""
    @Volatile var art: Bitmap? = null
    @Volatile var playing = false
    @Volatile var durationMs = 0L
    @Volatile var posMs = 0L
    @Volatile var posTime = 0L          // SystemClock.elapsedRealtime() when posMs was sampled
    @Volatile var speed = 1f
    @Volatile var cutout: Bitmap? = null
    @Volatile var cutoutKey = 0L
    /** Fingerprint of the art the cut-out was made from, and what the last attempt said (for the debug dump). */
    @Volatile var artSig = 0L
    @Volatile var cutoutSig = ""
    @Volatile var cutoutNote = ""
    /** The session being mirrored; the HUD's pause button drives it. */
    @Volatile var controller: MediaController? = null

    /** Stable per-track number: drives the random shape and the random font + axes. */
    val key: Long get() = (title.hashCode().toLong() shl 32) xor (artist.hashCode().toLong() and 0xffffffffL)

    /** What the Depth widget is working from, for working out a wrong cut-out or missing art. */
    fun debug(): String {
        val cut = cutout
        return "track=$title / $artist ($pkg)\n" +
            "art=${art?.let { "${it.width}x${it.height}" } ?: "none"} sig=${java.lang.Long.toHexString(artSig)}\n" +
            "cutout=${cut?.let { "${it.width}x${it.height}" } ?: "none"} for=$cutoutSig note=$cutoutNote\n" +
            "lyrics=${Lyrics.all().size} lines"
    }

    fun positionMs(): Long =
        if (playing) posMs + ((SystemClock.elapsedRealtime() - posTime) * speed).toLong() else posMs

    fun progress(): Float =
        if (durationMs <= 0) 0f else (positionMs().toFloat() / durationMs).coerceIn(0f, 1f)
}
