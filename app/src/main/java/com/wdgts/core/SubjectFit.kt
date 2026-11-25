package com.wdgts.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    /**
     * Placements that only zoom in (up to 18%) and then slide the photo inside the box it still covers, so a move never
     * leaves a gap to fill with scrim or blur. Offsets are never positive: the photo always reaches every edge.
     */
    fun placesZoomIn(aw: Int, ah: Int, w: Int, h: Int): List<Place> {
        val k0 = maxOf(w / aw.toFloat(), h / ah.toFloat())
        val out = ArrayList<Place>()
        for (kf in floatArrayOf(1f, 1.06f, 1.12f, 1.18f)) {
            val dw = aw * k0 * kf
            val dh = ah * k0 * kf
            val seen = HashSet<Long>()
            for (i in 0 until 5) for (j in 0 until 5) {
                val ox = minOf(0f, (w - dw) * i / 4f)
                val oy = minOf(0f, (h - dh) * j / 4f)
                if (seen.add((Math.round(ox) * 100003L) + Math.round(oy))) out += Place(kf, ox, oy, dw, dh)
            }
        }
        return out
    }

    /** A cheap content fingerprint (size plus 36 sampled pixels): the same picture gives the same number even as a new Bitmap instance. */
    fun fingerprint(b: Bitmap?): Long {
        if (b == null) return 0L
        var h = b.width * 31L + b.height
        return runCatching {
            val n = 6
            for (i in 0 until n) for (j in 0 until n) {
                val x = (b.width * (i + 0.5f) / n).toInt().coerceIn(0, b.width - 1)
                val y = (b.height * (j + 0.5f) / n).toInt().coerceIn(0, b.height - 1)
                h = (h xor b.getPixel(x, y).toLong()) * 1099511628211L
            }
            h
        }.getOrDefault(h)
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

    private class Under(val fpArt: Long, val fpFg: Long, val out: Bitmap)
    private val unders = ArrayList<Under>()

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        val k = (2 * r + 1).toFloat()
        val rowPre = FloatArray(w + 1)
        for (y in 0 until h) {
            for (x in 0 until w) rowPre[x + 1] = rowPre[x] + src[y * w + x]
            for (x in 0 until w) tmp[y * w + x] = (rowPre[minOf(w, x + r + 1)] - rowPre[maxOf(0, x - r)]) / k
        }
        val colPre = FloatArray(h + 1)
        for (x in 0 until w) {
            for (y in 0 until h) colPre[y + 1] = colPre[y] + tmp[y * w + x]
            for (y in 0 until h) dst[y * w + x] = (colPre[minOf(h, y + r + 1)] - colPre[maxOf(0, y - r)]) / k
        }
        return dst
    }

    /**
     * A soft, out-of-focus stand-in for the photo, with the subject painted out: the colours around the subject
     * are diffused into the hole (normalised blur at growing radii), then everything is softened. It is what shows
     * where a zoomed-out photo does not reach the edge, so the subject is never seen twice.
     */
    fun underlay(art: Bitmap, fg: Bitmap?): Bitmap {
        val fa = fingerprint(art)
        val ff = fingerprint(fg)
        synchronized(unders) { unders.firstOrNull { it.fpArt == fa && it.fpFg == ff }?.let { return it.out } }
        val mw = 64
        val mh = (mw * art.height.toFloat() / art.width).toInt().coerceIn(16, 128)
        val n = mw * mh
        val px = IntArray(n)
        Bitmap.createScaledBitmap(art, mw, mh, true).getPixels(px, 0, mw, 0, 0, mw, mh)
        val known = FloatArray(n) { 1f }
        if (fg != null) {
            val fp = IntArray(n)
            Bitmap.createScaledBitmap(fg, mw, mh, true).getPixels(fp, 0, mw, 0, 0, mw, mh)
            for (i in 0 until n) if ((fp[i] ushr 24) > 40) known[i] = 0f
            repeat(2) {                                    // grow the hole so the subject's soft edge goes too
                val c = known.copyOf()
                for (y in 0 until mh) for (x in 0 until mw) {
                    val i = y * mw + x
                    if (c[i] > 0.5f && ((x > 0 && c[i - 1] < 0.5f) || (x < mw - 1 && c[i + 1] < 0.5f) ||
                            (y > 0 && c[i - mw] < 0.5f) || (y < mh - 1 && c[i + mw] < 0.5f))) known[i] = 0f
                }
            }
        }
        val ch = Array(3) { c -> FloatArray(n) { ((px[it] shr (16 - 8 * c)) and 0xFF).toFloat() } }
        val out = Array(3) { ch[it].copyOf() }
        val done = BooleanArray(n) { known[it] > 0.5f }
        for (r in intArrayOf(3, 8, 20, 48)) {
            val q = boxBlur(known, mw, mh, r)
            val pc = Array(3) { c -> boxBlur(FloatArray(n) { ch[c][it] * known[it] }, mw, mh, r) }
            for (i in 0 until n) if (!done[i] && q[i] > 0.02f) {
                for (c in 0 until 3) out[c][i] = pc[c][i] / q[i]
                done[i] = true
            }
        }
        var mean = floatArrayOf(128f, 128f, 128f)
        val kn = known.sum()
        if (kn > 0f) mean = FloatArray(3) { c -> var t = 0f; for (i in 0 until n) t += ch[c][i] * known[i]; t / kn }
        for (i in 0 until n) if (!done[i]) for (c in 0 until 3) out[c][i] = mean[c]
        val soft = Array(3) { boxBlur(boxBlur(out[it], mw, mh, 2), mw, mh, 2) }
        val res = IntArray(n) {
            Color.rgb(soft[0][it].toInt().coerceIn(0, 255), soft[1][it].toInt().coerceIn(0, 255), soft[2][it].toInt().coerceIn(0, 255))
        }
        val bmp = Bitmap.createBitmap(res, mw, mh, Bitmap.Config.ARGB_8888)
        synchronized(unders) { if (unders.size >= 3) unders.removeAt(0); unders += Under(fa, ff, bmp) }
        return bmp
    }

    /**
     * Draws [art] at [pl]. When it does not cover the box (zoomed out), a blurred, subject-free [underlay] is drawn
     * first and the placed photo fades into it over [feather] px, so the photo never ends in a hard edge and the
     * subject never shows twice. [fg] is the subject's cut-out; [grey] desaturates both layers.
     */
    fun Canvas.drawPlaced(art: Bitmap, pl: Place, w: Int, h: Int, grey: Boolean, feather: Float, fg: Bitmap? = null) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).also {
            if (grey) it.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val dst = pl.rect
        val gaps = pl.dw < w - 1f || pl.dh < h - 1f
        if (gaps) {
            val under = underlay(art, fg)
            val kc = maxOf(w / under.width.toFloat(), h / under.height.toFloat())
            val cw = under.width * kc
            val ch = under.height * kc
            drawBitmap(under, null, RectF((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f), paint)
        }
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
