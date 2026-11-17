package com.wdgts.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

/**
 * Subject-aware placement of a photo behind type, shared by the Wikipedia and Depth music widgets.
 * The cut-out of the subject (ML Kit, same pixel size as the photo) gives a coarse footprint; the photo is then
 * shifted or slightly zoomed out so the subject stays inside the box and covers as little of the type as possible.
 */
object SubjectFit {
    /** The subject's footprint on a coarse grid, in 0..1 coordinates of the image. */
    class Subject(val mask: BooleanArray, val mw: Int, val mh: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun hit(u: Float, v: Float): Boolean {
            if (u < 0f || v < 0f || u >= 1f || v >= 1f) return false
            return mask[(v * mh).toInt() * mw + (u * mw).toInt()]
        }
    }

    fun subjectOf(fg: Bitmap): Subject? {
        val mw = 48
        val mh = (48f * fg.height / fg.width).toInt().coerceIn(8, 128)
        val px = IntArray(mw * mh)
        Bitmap.createScaledBitmap(fg, mw, mh, true).getPixels(px, 0, mw, 0, 0, mw, mh)
        val mask = BooleanArray(mw * mh) { (px[it] ushr 24) > 90 }
        var x0 = mw; var x1 = -1; var y0 = mh; var y1 = -1
        for (y in 0 until mh) for (x in 0 until mw) if (mask[y * mw + x]) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        return Subject(mask, mw, mh, x0 / mw.toFloat(), y0 / mh.toFloat(), (x1 + 1) / mw.toFloat(), (y1 + 1) / mh.toFloat())
    }

    /** Where the image sits: scale factor against "cover" ([kf]), and the scaled image's rectangle in widget pixels. */
    class Place(val kf: Float, val ox: Float, val oy: Float, val dw: Float, val dh: Float) {
        val rect: RectF get() = RectF(ox, oy, ox + dw, oy + dh)
        val covers get() = true
    }

    /** Candidate placements: cover scale and two slight zoom-outs, each at five offsets along both axes. */
    fun places(aw: Int, ah: Int, w: Int, h: Int): List<Place> {
        val k0 = maxOf(w / aw.toFloat(), h / ah.toFloat())
        val out = ArrayList<Place>()
        for (kf in floatArrayOf(1f, 0.92f, 0.85f)) {
            val dw = aw * k0 * kf
            val dh = ah * k0 * kf
            for (i in 0 until 5) for (j in 0 until 5) out += Place(kf, (w - dw) * i / 4f, (h - dh) * j / 4f, dw, dh)
        }
        return out
    }

    /** How far the subject's box sticks out of the widget, as a share of the width. */
    fun outside(c: Place, s: Subject, w: Int, h: Int): Float {
        val bx0 = c.ox + s.x0 * c.dw; val bx1 = c.ox + s.x1 * c.dw
        val by0 = c.oy + s.y0 * c.dh; val by1 = c.oy + s.y1 * c.dh
        return (maxOf(0f, -bx0) + maxOf(0f, bx1 - w) + maxOf(0f, -by0) + maxOf(0f, by1 - h)) / w
    }

    /** Share of the text rectangles the subject would cover (sampled on a 14 x 3 grid per rectangle). */
    fun covered(c: Place, s: Subject, rects: List<RectF>): Float {
        var n = 0; var tot = 0
        for (r in rects) for (xi in 0 until 14) for (yi in 0 until 3) {
            val px = r.left + r.width() * (xi + 0.5f) / 14f
            val py = r.top + r.height() * (yi + 0.5f) / 3f
            tot++
            if (s.hit((px - c.ox) / c.dw, (py - c.oy) / c.dh)) n++
        }
        return if (tot == 0) 0f else n / tot.toFloat()
    }

    /** Placement penalty independent of the type: subject outside the box, zooming out. */
    fun cost(c: Place, s: Subject, w: Int, h: Int) = outside(c, s, w, h) * 3f + (1f - c.kf) * 0.5f

    /** With no cut-out: the subject centred (and inside) the box. */
    fun centred(cands: List<Place>, s: Subject, w: Int, h: Int): Place = cands.minByOrNull { c ->
        val cx = c.ox + (s.x0 + s.x1) / 2f * c.dw
        val cy = c.oy + (s.y0 + s.y1) / 2f * c.dh
        cost(c, s, w, h) + (Math.abs(cx - w / 2f) + Math.abs(cy - h / 2f)) / w * 0.4f
    }!!

    /**
     * Draws [art] at [pl]. When it does not cover the box (zoomed out), a "cover" copy is the underlay and the placed
     * one fades into it over [feather] px, so the photo never ends in a hard edge. [grey] desaturates both.
     */
    fun Canvas.drawPlaced(art: Bitmap, pl: Place, w: Int, h: Int, grey: Boolean, feather: Float) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).also {
            if (grey) it.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val kc = maxOf(w / art.width.toFloat(), h / art.height.toFloat())
        val cw = art.width * kc
        val ch = art.height * kc
        val dst = pl.rect
        val gaps = pl.dw < w - 1f || pl.dh < h - 1f
        if (gaps) drawBitmap(art, null, RectF((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f), paint)
        if (gaps) saveLayer(dst, null)
        drawBitmap(art, null, dst, paint)
        if (gaps) {
            val clear = Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            fun band(l: Float, t: Float, r: Float, b: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
                clear.shader = LinearGradient(x0, y0, x1, y1, 0x00FFFFFF, -1, Shader.TileMode.CLAMP)
                drawRect(l, t, r, b, clear)
            }
            val f = feather
            if (dst.left > 0.5f) band(dst.left, dst.top, dst.left + f, dst.bottom, dst.left, 0f, dst.left + f, 0f)
            if (dst.right < w - 0.5f) band(dst.right - f, dst.top, dst.right, dst.bottom, dst.right, 0f, dst.right - f, 0f)
            if (dst.top > 0.5f) band(dst.left, dst.top, dst.right, dst.top + f, 0f, dst.top, 0f, dst.top + f)
            if (dst.bottom < h - 0.5f) band(dst.left, dst.bottom - f, dst.right, dst.bottom, 0f, dst.bottom, 0f, dst.bottom - f)
            restore()
        }
    }
}
