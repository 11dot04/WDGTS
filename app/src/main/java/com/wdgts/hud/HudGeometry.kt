package com.wdgts.hud

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.wdgts.hud.engine.Hud
import com.wdgts.hud.engine.HudSizing

/**
 * The widget's pixel size as the launcher reports it, used to size the tap zones in RemoteViews (which cannot
 * measure anything itself). The bitmap is drawn at the size ImageWidget passes to paint(); both go through
 * [HudSizing], so lines and zones agree up to the launcher's rounding.
 */
object HudGeometry {
    class Geo(val widthPx: Int, val heightPx: Int, val size: HudSizing.Size) {
        val pitchPx get() = Hud.LINE_PITCH * size.unit
        val topPx get() = Hud.PAD_V * size.unit
    }

    fun of(ctx: Context): Geo {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = mgr.getAppWidgetIds(ComponentName(ctx, HudWidget::class.java)).firstOrNull()
        val o = id?.let { mgr.getAppWidgetOptions(it) }
        val d = ctx.resources.displayMetrics.density
        val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: 320
        val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: 150
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        return Geo(w, h, HudSizing.of(w, h))
    }
}
