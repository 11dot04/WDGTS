package com.wdgts.music

import android.graphics.Bitmap
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

    /** Stable per-track number: drives the random shape and the random font + axes. */
    val key: Long get() = (title.hashCode().toLong() shl 32) xor (artist.hashCode().toLong() and 0xffffffffL)

    fun positionMs(): Long =
        if (playing) posMs + ((SystemClock.elapsedRealtime() - posTime) * speed).toLong() else posMs

    fun progress(): Float =
        if (durationMs <= 0) 0f else (positionMs().toFloat() / durationMs).coerceIn(0f, 1f)
}
