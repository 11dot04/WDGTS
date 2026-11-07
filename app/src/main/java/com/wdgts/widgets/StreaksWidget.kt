package com.wdgts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Transparent widget; the tiles rest directly on the wallpaper.
 * Pending tile: tertiary (lavender) with surface-coloured glyph and number.
 * Done tile: settles to the surface colour with accent glyphs.
 */
class StreaksWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = ids.forEach { render(c, m, it) }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refreshAll(c)
            else -> super.onReceive(c, i)
        }
    }

    companion object {
        private val CELLS = arrayOf(
            intArrayOf(R.id.z00, R.id.z01, R.id.z02, R.id.z03),
            intArrayOf(R.id.z10, R.id.z11, R.id.z12, R.id.z13),
            intArrayOf(R.id.z20, R.id.z21, R.id.z22, R.id.z23),
            intArrayOf(R.id.z30, R.id.z31, R.id.z32, R.id.z33),
        )
        private val glyphCache = HashMap<String, Bitmap?>()

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, StreaksWidget::class.java)).forEach { render(c, m, it) }
        }

        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val (w, h) = Render.sizePx(c, m, id, 200, 200)
            val today = LocalDate.now().toEpochDay()
            val bmp = Render.bitmap(w, h) { drawStreaks(c, w, h, today) }

            val rv = RemoteViews(c.packageName, R.layout.widget_streaks)
            rv.setImageViewBitmap(R.id.img, bmp)
            for (def in STREAKS) {
                val pi = PendingIntent.getActivity(
                    c, def.id.hashCode(),
                    Intent(c, TapActivity::class.java)
                        .setData(Uri.parse("wdgts://streak/${def.id}"))
                        .putExtra("streak", def.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                for (r in def.row until def.row + def.h) for (cc in def.col until def.col + def.w) {
                    rv.setOnClickPendingIntent(CELLS[r][cc], pi)
                }
            }
            m.updateAppWidget(id, rv)
        }

        // ------------------------------------------------------------------ icons
        /** Draws [d] into an N x N bitmap. */
        private fun render(d: Drawable, n: Int): Bitmap {
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n)
            d.draw(Canvas(b))
            return b
        }

        /** Plain (non-adaptive) icon: keep what differs from the background colour, as alpha. */
        private fun extractGlyph(src: Bitmap): Bitmap {
            val n = src.width
            val px = IntArray(n * n)
            src.getPixels(px, 0, n, 0, 0, n, n)
            val corners = intArrayOf(px[0], px[n - 1], px[(n - 1) * n], px[n * n - 1])
            val bg = corners.maxByOrNull { c -> corners.count { it == c } } ?: px[0]
            val br = Color.red(bg); val bgG = Color.green(bg); val bb = Color.blue(bg)
            for (i in px.indices) {
                val c = px[i]
                val dist = sqrt(((Color.red(c) - br).let { it * it } + (Color.green(c) - bgG).let { it * it } + (Color.blue(c) - bb).let { it * it }).toFloat())
                val a = ((dist - 28f) / 90f).coerceIn(0f, 1f) * (Color.alpha(c) / 255f)
                px[i] = (((a * 255).toInt()) shl 24) or 0x00FFFFFF
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }

        private fun cropToAlpha(b: Bitmap): Bitmap? {
            val w = b.width; val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            var l = w; var t = h; var r = -1; var bt = -1
            for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 24) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
            }
            return if (r < 0) null else Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
        }

        /** The tile glyph as an alpha mask cropped to its content, so every icon can be sized the same. */
        private fun glyph(ctx: Context, def: StreakDef): Bitmap? = glyphCache.getOrPut(def.id + AppResolver.resolve(ctx, def)) {
            runCatching {
                val res = ctx.resources.getIdentifier("ic_streak_${def.id}", "drawable", ctx.packageName)
                var mask: Bitmap? = null
                if (res != 0) mask = ctx.getDrawable(res)?.let { render(it, 256) }
                if (mask == null && !def.web) {
                    val pkg = AppResolver.resolve(ctx, def)
                    if (pkg.isNotEmpty()) {
                        val ic = ctx.packageManager.getApplicationIcon(pkg)
                        mask = if (ic is AdaptiveIconDrawable) (ic.monochrome ?: ic.foreground)?.let { render(it, 432) }
                        else extractGlyph(render(ic, 256))
                    }
                }
                mask?.let { cropToAlpha(it) }
            }.getOrNull()
        }

        private fun Canvas.globe(cx: Float, cy: Float, r: Float, color: Int) {
            val s = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = r * 0.16f }
            drawCircle(cx, cy, r, s)
            drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), s)
            drawLine(cx - r, cy, cx + r, cy, s)
            drawLine(cx - r * 0.86f, cy - r * 0.5f, cx + r * 0.86f, cy - r * 0.5f, s)
            drawLine(cx - r * 0.86f, cy + r * 0.5f, cx + r * 0.86f, cy + r * 0.5f, s)
        }

        /** Draws the glyph scaled so its LONGEST side is [target] (normalises tiny layers like NYT's). */
        private fun Canvas.icon(ctx: Context, def: StreakDef, cx: Float, cy: Float, target: Float, color: Int) {
            if (def.web) { globe(cx, cy, target * 0.5f, color); return }
            val g = glyph(ctx, def)
            if (g != null) {
                val s = target / maxOf(g.width, g.height)
                val dw = g.width * s; val dh = g.height * s
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
                drawBitmap(g, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), paint)
            } else {
                val ch = def.label.ifEmpty { def.id }.take(1).uppercase()
                label(ch, cx, cy + target * 0.36f, target, color, Fonts.display(ctx), Paint.Align.CENTER)
            }
        }

        /** Small shield with the remaining count, top-right of a tile. */
        private fun Canvas.shield(cx: Float, cy: Float, s: Float, n: Int, color: Int, mono: android.graphics.Typeface) {
            val p = Path()
            p.moveTo(cx, cy - s); p.lineTo(cx + s * 0.85f, cy - s * 0.62f); p.lineTo(cx + s * 0.85f, cy + s * 0.1f)
            p.quadTo(cx + s * 0.8f, cy + s * 0.72f, cx, cy + s); p.quadTo(cx - s * 0.8f, cy + s * 0.72f, cx - s * 0.85f, cy + s * 0.1f)
            p.lineTo(cx - s * 0.85f, cy - s * 0.62f); p.close()
            drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = s * 0.16f; it.strokeJoin = Paint.Join.ROUND })
            label("$n", cx, cy + s * 0.34f, s * 0.95f, color, mono, Paint.Align.CENTER)
        }

        private fun Canvas.drawStreaks(ctx: Context, w: Int, h: Int, today: Long) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val mono = Fonts.mono(ctx)
            val u = minOf(w, h) / 100f

            // the widget is transparent; only a faint scrim sits behind the tiles
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 11f * u, 11f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.argb(95, 0, 0, 0) })

            val inset = 8.5f * u
            val gap = 2.8f * u
            val cw = (w - 2 * inset) / 4f
            val ch = (h - 2 * inset) / 4f

            for (def in STREAKS) {
                val st = Streaks.state(ctx, def, today)
                val rule = Streaks.rule(ctx, def)
                val rect = RectF(
                    inset + def.col * cw + gap / 2, inset + def.row * ch + gap / 2,
                    inset + (def.col + def.w) * cw - gap / 2, inset + (def.row + def.h) * ch - gap / 2,
                )
                val fill = if (st.doneToday) p.surface else p.tertiary
                val ink = if (st.doneToday) p.accent else p.surface
                val rad = minOf(rect.width(), rect.height()) * 0.22f
                drawRoundRect(rect, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = fill })

                val cells = def.w * def.h
                val num = st.count.toString()
                when {
                    cells >= 4 -> {      // big: icon top-left, light-weight count bottom-right
                        icon(ctx, def, rect.left + 9.5f * u, rect.top + 9.5f * u, 12f * u, ink)
                        val (sz, fv) = fitByAxis(num, rect.width() - 10f * u, 13f * u, tf, wght = 270, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.bottom - 3.4f * u, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                        if (rule.cap > 0) shield(rect.right - 6.5f * u, rect.top + 7f * u, 3.6f * u, st.shields, ink, mono)
                    }
                    cells == 2 -> {      // wide: icon left, count right
                        icon(ctx, def, rect.left + rect.height() * 0.5f, rect.centerY(), rect.height() * 0.56f, ink)
                        val (sz, fv) = fitByAxis(num, rect.width() * 0.5f, rect.height() * 0.5f, tf, wght = 270, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.centerY() + sz * 0.35f, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                    }
                    else -> icon(ctx, def, rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.52f, ink)
                }
            }
        }
    }
}
