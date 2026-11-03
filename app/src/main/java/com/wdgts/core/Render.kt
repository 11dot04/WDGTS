package com.wdgts.core

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Render {
    /** Pixel size to draw at for this widget instance, capped to stay under RemoteViews bitmap limits. */
    fun sizePx(c: Context, m: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = m.getAppWidgetOptions(id)
        val port = c.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val wDp = (if (port) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).takeIf { it > 0 } ?: defW
        val hDp = (if (port) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).takeIf { it > 0 } ?: defH
        val d = c.resources.displayMetrics.density
        var w = wDp * d
        var h = hDp * d
        val cap = 900_000f
        if (w * h > cap) { val k = sqrt(cap / (w * h)); w *= k; h *= k }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    inline fun bitmap(w: Int, h: Int, block: Canvas.() -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).block()
        return b
    }
}
