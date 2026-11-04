package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.Render

/** One bitmap + tap zones. Subclasses only draw ([paint]) and wire clicks ([bind]). */
abstract class ImageWidget(private val defDp: Int = 170) : AppWidgetProvider() {

    protected open val layoutRes: Int = R.layout.widget_simple
    protected abstract fun Canvas.paint(ctx: Context, w: Int, h: Int)
    protected open fun bind(ctx: Context, rv: RemoteViews) {}

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    fun refreshAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        m.getAppWidgetIds(ComponentName(c, this::class.java)).forEach { render(c, m, it) }
    }

    private fun render(c: Context, m: AppWidgetManager, id: Int) {
        val (w, h) = Render.sizePx(c, m, id, defDp, defDp)
        val bmp = Render.bitmap(w, h) { paint(c, w, h) }
        val rv = RemoteViews(c.packageName, layoutRes)
        rv.setImageViewBitmap(R.id.img, bmp)
        bind(c, rv)
        m.updateAppWidget(id, rv)
    }

    protected fun broadcast(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, this::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
