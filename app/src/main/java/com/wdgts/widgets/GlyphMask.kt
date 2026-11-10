package com.wdgts.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.sqrt

/**
 * Turns an app icon into a clean white-on-transparent silhouette: the adaptive icon's monochrome layer when it
 * has one, otherwise its foreground. Shared by the streaks widget and the HUD chips.
 */
object GlyphMask {
    private const val SIZE = 192
    private val cache = HashMap<String, Bitmap?>()

    /** The trimmed silhouette for [pkg], or null if the app or its icon cannot be read. */
    fun forPackage(ctx: Context, pkg: String): Bitmap? {
        synchronized(cache) {
            if (cache.containsKey(pkg)) return cache[pkg]
            val m = runCatching { build(ctx, pkg) }.getOrNull()
            cache[pkg] = m
            return m
        }
    }

    private fun build(ctx: Context, pkg: String): Bitmap? {
        val icon = ctx.packageManager.getApplicationIcon(pkg)
        val layer: Drawable = if (icon is AdaptiveIconDrawable) (icon.monochrome ?: icon.foreground ?: return null) else icon
        // adaptive layers are 108dp with a 72dp safe zone; draw the whole layer so nothing is clipped
        val raw = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        val pad = if (icon is AdaptiveIconDrawable) 0 else SIZE / 8
        layer.setBounds(pad, pad, SIZE - pad, SIZE - pad)
        layer.draw(c)
        return trim(raw)
    }

    /**
     * Crops to the artwork but ignores stray specks: edges are placed where 0.4% of the ink lies outside,
     * so one far-away pixel no longer shrinks the whole glyph.
     */
    fun trim(b: Bitmap): Bitmap? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val col = LongArray(w)
        val row = LongArray(h)
        var total = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val a = (px[y * w + x] ushr 24).toLong()
            col[x] += a
            row[y] += a
            total += a
        }
        if (total == 0L) return null
        val cut = (total * 0.004).toLong()
        fun edge(sum: LongArray, fromStart: Boolean): Int {
            var acc = 0L
            if (fromStart) {
                for (i in sum.indices) { acc += sum[i]; if (acc > cut) return i }
                return 0
            }
            for (i in sum.indices.reversed()) { acc += sum[i]; if (acc > cut) return i }
            return sum.lastIndex
        }
        val l = edge(col, true)
        val r = edge(col, false)
        val t = edge(row, true)
        val bt = edge(row, false)
        if (r <= l || bt <= t) return null
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
    }

    /** Fraction of the trimmed bitmap that is ink (0..1). */
    fun coverage(b: Bitmap): Float {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var s = 0L
        for (p in px) s += (p ushr 24)
        return s / (255f * px.size)
    }

    /**
     * Optical size: dense, square artwork is drawn smaller and thin artwork larger so every icon looks the same size.
     * Returns the factor to apply to the nominal size.
     */
    fun optical(coverage: Float): Float = sqrt(0.55f / coverage.coerceAtLeast(0.08f)).coerceIn(0.82f, 1.22f)
}
