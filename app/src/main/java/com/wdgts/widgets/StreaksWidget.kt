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
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.PathParser
import com.wdgts.R
import com.wdgts.core.*
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

        /** Crops to the artwork but ignores stray specks (see GlyphMask.trim), so one far pixel cannot shrink a glyph. */
        private fun cropToAlpha(b: Bitmap): Bitmap? = GlyphMask.trim(b)

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
                        mask = if (ic is AdaptiveIconDrawable) adaptiveGlyph(ic) else extractGlyph(render(ic, 256))
                    }
                }
                mask?.let { cropToAlpha(it) }
            }.getOrNull()
        }

        /**
         * An adaptive icon as a glyph. The themed (monochrome) layer when the app has one. Otherwise the background and
         * foreground layers drawn full-bleed on top of each other, keeping whatever differs from the background colour, like an
         * auto-themed icon: a white owl on green leaves the green owl, a white head on a dark badge leaves the head. (The
         * foreground's own alpha would give a solid blob for icons whose foreground is a filled shape.)
         */
        private fun adaptiveGlyph(ic: AdaptiveIconDrawable): Bitmap? {
            ic.monochrome?.let { return render(it, 432) }
            val n = 432
            val comp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val cv = Canvas(comp)
            ic.background?.let { it.setBounds(0, 0, n, n); it.draw(cv) }
            ic.foreground?.let { it.setBounds(0, 0, n, n); it.draw(cv) }
            // a transparent background has no colour to subtract: use the foreground's shape
            return if (Color.alpha(comp.getPixel(2, 2)) > 200) extractGlyph(comp) else ic.foreground?.let { render(it, n) }
        }


        /** Material "public" globe (Apache 2.0), the two continent shapes only (the holes of the filled icon), in a 24-unit box. */
        private val CONTINENTS: Path by lazy {
            PathParser.createPathFromPathData(
                "M11 19.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93z" +
                    "M17.9 17.39c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z",
            )
        }

        /** A line globe: a ring with the continents solid, like the KWGT card (not a filled disc with the continents cut out). */
        private fun Canvas.globe(cx: Float, cy: Float, r: Float, color: Int) {
            val k = (r * 2f) / 20f            // the artwork spans 2..22 of the 24-unit box
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL; it.color = color }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = color; it.strokeWidth = 1.5f * k }
            save()
            translate(cx - 12f * k, cy - 12f * k)
            scale(k, k)
            drawCircle(12f, 12f, 9.25f, ring.also { it.strokeWidth = 1.5f })
            drawPath(CONTINENTS, fill)
            restore()
        }

        /** Draws the glyph scaled so its LONGEST side is [target] (normalises tiny layers like NYT's). */
        private fun Canvas.icon(ctx: Context, def: StreakDef, cx: Float, cy: Float, target: Float, color: Int) {
            if (def.web) { globe(cx, cy, target * 0.5f, color); return }
            val g = glyph(ctx, def)
            if (g != null) {
                // optical size: dense square art is drawn a little smaller, thin art a little larger
                val s = target * GlyphMask.optical(GlyphMask.coverage(g)) / maxOf(g.width, g.height)
                val dw = g.width * s; val dh = g.height * s
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
                drawBitmap(g, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), paint)
            } else {
                val ch = def.label.ifEmpty { def.id }.take(1).uppercase()
                label(ch, cx, cy + target * 0.36f, target, color, Fonts.display(ctx), Paint.Align.CENTER)
            }
        }

        /** A small, quiet shield: filled in a translucent ink with the remaining count knocked out of it. */
        private fun Canvas.shield(cx: Float, cy: Float, s: Float, n: Int, color: Int, mono: android.graphics.Typeface) {
            val layer = saveLayer(cx - s * 1.4f, cy - s * 1.4f, cx + s * 1.4f, cy + s * 1.4f, null)
            val p = Path()
            p.moveTo(cx, cy - s); p.lineTo(cx + s * 0.85f, cy - s * 0.62f); p.lineTo(cx + s * 0.85f, cy + s * 0.1f)
            p.quadTo(cx + s * 0.8f, cy + s * 0.72f, cx, cy + s); p.quadTo(cx - s * 0.8f, cy + s * 0.72f, cx - s * 0.85f, cy + s * 0.1f)
            p.lineTo(cx - s * 0.85f, cy - s * 0.62f); p.close()
            drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL; it.color = alpha(color, 120) })
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.typeface = mono; it.textSize = s * 1.05f; it.textAlign = Paint.Align.CENTER
                it.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            drawText("$n", cx, cy + s * 0.36f, clear)
            restoreToCount(layer)
        }

        /** Material-style shapes for the 1x1 tiles (they carry no text): a polar curve r = R (1 + a cos(k t)) fitted inside the tile. */
        private enum class Kind(val lobes: Int, val amp: Float) { COOKIE9(9, 0.045f), CLOVER4(4, 0.13f), FLOWER8(8, 0.09f), CUSHION(4, -0.10f), COOKIE12(12, 0.03f) }
        private val SHAPES = arrayOf(Kind.COOKIE9, Kind.CLOVER4, Kind.CUSHION, Kind.FLOWER8, Kind.COOKIE12)

        private fun tileShape(r: RectF, k: Kind): Path {
            val cx = r.centerX(); val cy = r.centerY()
            val rad = minOf(r.width(), r.height()) / 2f / (1f + abs(k.amp)) * 1.0f
            val p = Path()
            val n = 160
            for (i in 0..n) {
                val t = (2.0 * Math.PI * i / n).toFloat()
                val rr = rad * (1f + k.amp * cos(k.lobes * t))
                val x = cx + rr * cos(t); val y = cy + rr * sin(t)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            return p
        }

        private fun Canvas.drawStreaks(ctx: Context, w: Int, h: Int, today: Long) {
            val p = Palette.of(ctx)
            val tf = Fonts.display(ctx)
            val mono = Fonts.mono(ctx)
            val u = minOf(w, h) / 100f

            // the widget is transparent; only a faint scrim sits behind the tiles
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 11f * u, 11f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.argb(95, 0, 0, 0) })

            val inset = 7.4f * u                          // KWGT: tiles start 42 px in on a 486 px card
            val gap = 2.8f * u
            val cw = (w - 2 * inset) / 4f
            val ch = (h - 2 * inset) / 4f
            val shapes = Prefs.streakShapes(ctx)
            val countSize = (ch - gap) * 0.64f          // every count, in every tile, is this size

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
                val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = fill }
                val shapeKind = if (shapes && def.w == 1 && def.h == 1) SHAPES[STREAKS.indexOf(def) % SHAPES.size] else null
                if (shapeKind != null) drawPath(tileShape(rect, shapeKind), tilePaint) else drawRoundRect(rect, rad, rad, tilePaint)

                val cells = def.w * def.h
                val num = st.count.toString()
                when {
                    cells >= 4 -> {      // big: icon top-left, light-weight count bottom-right
                        icon(ctx, def, rect.left + 11f * u, rect.top + 11.5f * u, 11.5f * u, ink)   // KWGT icons are 49-66 px on a 486 px card
                        val (sz, fv) = fitByAxis(num, rect.width() - 10f * u, countSize, tf, wght = 380, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.bottom - 3.4f * u, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                        if (rule.cap > 0) shield(rect.right - 5.6f * u, rect.top + 6.2f * u, 3.0f * u, st.shields, ink, mono)
                    }
                    cells == 2 -> {      // wide: icon left, count right
                        icon(ctx, def, rect.left + rect.height() * 0.52f, rect.centerY(), rect.height() * 0.64f, ink)
                        val (sz, fv) = fitByAxis(num, rect.width() * 0.62f, countSize, tf, wght = 380, wdthMax = 100)
                        label(num, rect.right - 3.6f * u, rect.centerY() + sz * 0.35f, sz, ink, tf, Paint.Align.RIGHT, -0.02f, fv)
                    }
                    else -> icon(ctx, def, rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.62f, ink)
                }
            }
        }
    }
}
