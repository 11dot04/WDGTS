package com.wdgts.hud.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * A glyph read straight from its SVG: filled paths plus stroked rounded rects (the unit rings).
 * Coordinates stay in SVG user units; [draw] scales them by the HUD unit, so no conversion step
 * and no density maths. Supports exactly what the design set uses: <path d>, <rect> with rx/stroke,
 * fill-rule, width/height on <svg>. A viewBox that differs from width/height (Material Symbols: 0 -960 960 960 shown
 * at 8.6) is mapped onto the box. Groups, transforms, gradients and text are not supported.
 */
class SvgGlyph(
    val width: Float,
    val height: Float,
    private val fills: List<Path>,
    private val rings: List<Ring>,
) {
    class Ring(val rect: RectF, val radius: Float, val strokeWidth: Float)

    /** Bounds of the filled artwork itself, ignoring the padding the viewport may carry. */
    val ink: RectF by lazy {
        val r = RectF()
        var first = true
        val tmp = RectF()
        for (p in fills) {
            p.computeBounds(tmp, true)
            if (tmp.isEmpty) continue
            if (first) { r.set(tmp); first = false } else r.union(tmp)
        }
        if (first) r.set(0f, 0f, width, height)
        r
    }

    /**
     * The same artwork scaled so its ink is [inkHeight] tall, centred in a box [boxHeight] high and a little wider than the ink.
     * For glyphs drawn small inside a big viewport (the shaka), so every icon reads at the same size. Rings are not carried over.
     */
    fun fitted(inkHeight: Float, boxHeight: Float, sidePad: Float = 1.2f): SvgGlyph {
        val b = ink
        val k = inkHeight / b.height()
        val w = b.width() * k + 2f * sidePad
        val m = android.graphics.Matrix().apply {
            postTranslate(-b.centerX(), -b.centerY())
            postScale(k, k)
            postTranslate(w / 2f, boxHeight / 2f)
        }
        return SvgGlyph(w, boxHeight, fills.map { p -> Path(p).also { it.transform(m) } }, emptyList())
    }

    fun draw(c: Canvas, x: Float, y: Float, unit: Float, fill: Paint, stroke: Paint) {
        val save = c.save()
        c.translate(x, y)
        c.scale(unit, unit)
        for (p in fills) c.drawPath(p, fill)
        for (r in rings) {
            stroke.strokeWidth = r.strokeWidth
            c.drawRoundRect(r.rect, r.radius, r.radius, stroke)
        }
        c.restoreToCount(save)
    }

    companion object {
        private val TAG = Regex("""<(svg|path|rect)\b([^>]*)>""")
        private val ATTR = Regex("""([a-zA-Z-]+)\s*=\s*"([^"]*)"""")

        private fun num(s: String?): Float? = s?.removeSuffix("px")?.trim()?.toFloatOrNull()

        fun parse(svg: String): SvgGlyph? {
            var w: Float? = null
            var h: Float? = null
            val fills = ArrayList<Path>()
            val rings = ArrayList<Ring>()
            var view: android.graphics.Matrix? = null
            for (m in TAG.findAll(svg)) {
                val attrs = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                when (m.groupValues[1]) {
                    "svg" -> {
                        w = num(attrs["width"])
                        h = num(attrs["height"])
                        val vb = attrs["viewBox"]?.trim()?.split(Regex("[ ,]+"))
                        val wf = w
                        val hf = h
                        if ((wf == null || hf == null) && vb != null && vb.size == 4) {
                            w = vb[2].toFloatOrNull()
                            h = vb[3].toFloatOrNull()
                        } else if (vb != null && vb.size == 4 && wf != null && hf != null) {
                            val vx = vb[0].toFloatOrNull(); val vy = vb[1].toFloatOrNull()
                            val vw = vb[2].toFloatOrNull(); val vh = vb[3].toFloatOrNull()
                            if (vx != null && vy != null && vw != null && vh != null && vw > 0f && vh > 0f &&
                                (vx != 0f || vy != 0f || vw != wf || vh != hf)
                            ) {
                                val mat = android.graphics.Matrix()
                                mat.postTranslate(-vx, -vy)
                                mat.postScale(wf / vw, hf / vh)
                                view = mat
                            }
                        }
                    }
                    "path" -> {
                        val d = attrs["d"] ?: continue
                        val p = try {
                            PathParser.createPathFromPathData(d)
                        } catch (e: RuntimeException) {
                            continue
                        }
                        if (attrs["fill-rule"] == "evenodd") p.fillType = Path.FillType.EVEN_ODD
                        view?.let { p.transform(it) }
                        fills += p
                    }
                    "rect" -> {
                        val x = num(attrs["x"]) ?: 0f
                        val y = num(attrs["y"]) ?: 0f
                        val rw = num(attrs["width"]) ?: continue
                        val rh = num(attrs["height"]) ?: continue
                        val rx = num(attrs["rx"]) ?: 0f
                        val r = RectF(x, y, x + rw, y + rh)
                        val stroked = attrs["stroke"].let { it != null && it != "none" }
                        if (stroked) {
                            rings += Ring(r, rx, num(attrs["stroke-width"]) ?: 1f)
                        } else {
                            fills += Path().apply { addRoundRect(r, rx, rx, Path.Direction.CW) }
                        }
                    }
                }
            }
            val ww = w ?: return null
            val hh = h ?: return null
            return SvgGlyph(ww, hh, fills, rings)
        }
    }
}
