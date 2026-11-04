package com.wdgts.core

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.wdgts.R

/**
 * The actual Material 3 Expressive wavy indicators (Widget.Material3Expressive.*.Wavy).
 * A widget is a bitmap in another process, so the indicator View is inflated offscreen, laid out,
 * and drawn into a Bitmap. Determinate + static (widgets cannot animate the wave). Returns null on failure.
 */
object MaterialProgress {
    private fun inflate(c: Context, layout: Int): View =
        LayoutInflater.from(ContextThemeWrapper(c.applicationContext, R.style.OffscreenTheme)).inflate(layout, null)

    /** Wavy circular ring scaled to [sizePx] x [sizePx]. */
    fun ring(c: Context, frac: Float, sizePx: Int, color: Int, track: Int): Bitmap? = runCatching {
        val v = inflate(c, R.layout.offscreen_ring) as CircularProgressIndicator
        v.max = 1000
        v.setIndicatorColor(color)
        v.trackColor = track
        v.setProgressCompat((frac.coerceIn(0f, 1f) * 1000).toInt(), false)
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = v.measuredWidth
        val h = v.measuredHeight
        v.layout(0, 0, w, h)
        val n = maxOf(w, h).coerceAtLeast(1)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { b ->
            val cv = Canvas(b)
            val s = sizePx / n.toFloat()
            cv.scale(s, s)
            cv.translate((n - w) / 2f, (n - h) / 2f)
            v.draw(cv)
        }
    }.getOrNull()

    /** Wavy linear bar, [widthPx] wide; height follows the indicator's own thickness + amplitude. */
    fun linear(c: Context, frac: Float, widthPx: Int, color: Int, track: Int): Bitmap? = runCatching {
        val v = inflate(c, R.layout.offscreen_linear) as LinearProgressIndicator
        v.max = 1000
        v.setIndicatorColor(color)
        v.trackColor = track
        v.setProgressCompat((frac.coerceIn(0f, 1f) * 1000).toInt(), false)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        Bitmap.createBitmap(v.measuredWidth, v.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .also { v.draw(Canvas(it)) }
    }.getOrNull()
}
