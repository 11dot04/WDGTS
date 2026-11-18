package com.wdgts.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.appwidget.AppWidgetManager
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.widgets.ImageWidget
import kotlin.math.ceil

object MusicWidgets {
    /** True when a Depth widget is on a home screen (the lyric line then wants a faster refresh). */
    fun depthPlaced(c: Context): Boolean =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, MusicDepthWidget::class.java)).isNotEmpty()

    fun refreshAll(c: Context) {
        MusicDepthWidget().refreshAll(c)
        MusicFrameWidget().refreshAll(c)
        MusicRoundWidget().refreshAll(c)
    }
}

object MusicControl {
    enum class Cmd { PLAY_PAUSE, NEXT, PREV }

    fun send(c: Context, cmd: Cmd) {
        runCatching {
            val msm = c.getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(c, MediaListener::class.java))
            val mc = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull() ?: return
            when (cmd) {
                Cmd.PLAY_PAUSE ->
                    if (mc.playbackState?.state == PlaybackState.STATE_PLAYING) mc.transportControls.pause()
                    else mc.transportControls.play()
                Cmd.NEXT -> mc.transportControls.skipToNext()
                Cmd.PREV -> mc.transportControls.skipToPrevious()
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Slowly drifting phase so each refresh moves the wave a little. */
private fun wavePhase(): Float = ((SystemClock.elapsedRealtime() % 5000L) / 5000f) * (2.0 * Math.PI).toFloat()

/** Picks the line count (1..3) that lets the title be largest within [maxW] x [maxH]. */
private fun layoutTitle(title: String, fp: MusicFonts.Pick, maxW: Float, maxH: Float): Pair<List<String>, Float> {
    val words = title.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf(title) }
    var best: Pair<List<String>, Float>? = null
    for (n in 1..minOf(3, words.size)) {
        val per = ceil(words.size / n.toDouble()).toInt()
        val lines = words.chunked(per).map { it.joinToString(" ") }
        val s = fitSize(lines, maxW, maxH / (lines.size * 0.95f), fp.tf, 0f, fp.fvar)
        if (best == null || s > best.second * 1.04f) best = lines to s
    }
    return best!!
}

abstract class MusicBase : ImageWidget() {
    private val key: String get() = this::class.java.simpleName
    protected fun controlsOn(ctx: Context) = Prefs.musicControls(ctx, key)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACT_PP -> { Haptics.click(c); MusicControl.send(c, MusicControl.Cmd.PLAY_PAUSE) }
            ACT_NEXT -> { Haptics.click(c); MusicControl.send(c, MusicControl.Cmd.NEXT) }
            ACT_PREV -> { Haptics.click(c); MusicControl.send(c, MusicControl.Cmd.PREV) }
            ACT_TOGGLE -> { Haptics.tick(c); Prefs.setMusicControls(c, key, !controlsOn(c)); refreshAll(c) }
            else -> super.onReceive(c, i)
        }
    }

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
    }

    protected fun Canvas.idleCard(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)
        label("NOTHING", 8f * u, 0.50f * h, 11f * u, p.onSurface, tf, fvar = Fonts.axes(wght = 380, wdth = 110))
        label("PLAYING", 8f * u, 0.50f * h + 11f * u, 11f * u, p.muted, tf, fvar = Fonts.axes(wght = 380, wdth = 110))
        label("Needs notification access", 8f * u, h - 8f * u, 4.6f * u, p.faint, Fonts.mono(ctx), tracking = 0.02f)
    }

    /** Three dots when the controls are hidden, a pill when they are shown (top-right toggle). */
    protected fun Canvas.toggleGlyph(on: Boolean, cx: Float, cy: Float, u: Float, color: Int) {
        val f = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
        if (on) drawRoundRect(RectF(cx - 4.2f * u, cy - 1.1f * u, cx + 4.2f * u, cy + 1.1f * u), 1.1f * u, 1.1f * u, f)
        else for (i in -1..1) drawCircle(cx + i * 3.4f * u, cy, 1.15f * u, f)
    }

    companion object {
        const val ACT_PP = "com.wdgts.MUSIC_PP"
        const val ACT_NEXT = "com.wdgts.MUSIC_NEXT"
        const val ACT_PREV = "com.wdgts.MUSIC_PREV"
        const val ACT_TOGGLE = "com.wdgts.MUSIC_TOGGLE"
    }
}


// ============================================================================================
// 1. DEPTH: the cover full-bleed, the title anchored to the top and flowed around the subject, the cut-out of the
//    subject on a layer above the type (the iPhone depth-wallpaper look), one line of synced lyrics and the buttons
//    along the bottom: previous / next on the left half (two discs at rest, one pill while playing), play / pause
//    as a pill on the right.
// ============================================================================================
private class Placed(val text: String, val x: Float, val w: Float, val line: Int)
private class Flowed(val runs: List<Placed>, val size: Float, val fvar: String?, val lines: Int, val splits: Int)
private class DepthLayout(val place: SubjectFit.Place?, val flow: Flowed?, val plain: Pair<List<String>, Float>?, val cutOut: Boolean)

private object DepthCache {
    private var key = ""
    private var value: DepthLayout? = null
    @Synchronized fun get(k: String, make: () -> DepthLayout): DepthLayout {
        value?.let { if (key == k) return it }
        return make().also { key = k; value = it }
    }
}

private fun firstBase(size: Float, pad: Float, u: Float) = pad + 6.4f * u + size * 0.86f

/** Horizontal extent (widget pixels) of the subject between two y values, or null where the row is clear. */
private fun rowExtent(c: SubjectFit.Place, s: SubjectFit.Subject, y0: Float, y1: Float): Pair<Float, Float>? {
    if (y1 < c.oy || y0 > c.oy + c.dh) return null
    val r0 = (((y0 - c.oy) / c.dh) * s.mh).toInt().coerceIn(0, s.mh - 1)
    val r1 = (((y1 - c.oy) / c.dh) * s.mh).toInt().coerceIn(0, s.mh - 1)
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (r in r0..r1) for (cx in 0 until s.mw) if (s.mask[r * s.mw + cx]) {
        val l = c.ox + cx.toFloat() / s.mw * c.dw
        val rr = c.ox + (cx + 1f) / s.mw * c.dw
        if (l < lo) lo = l
        if (rr > hi) hi = rr
    }
    return if (hi < lo) null else lo to hi
}

/**
 * Sets the words line by line and routes them around the subject: on a row the subject crosses, the words that
 * fit go to the left of it and the rest to the right (flush with the margins), so the cut-out sits in a gap in the
 * line rather than on top of a word. Returns null when a word cannot fit at this size or the block runs too low.
 */
private fun flow(
    words: List<String>, size: Float, fvar: String?, fp: MusicFonts.Pick, place: SubjectFit.Place, subj: SubjectFit.Subject,
    w: Int, pad: Float, u: Float, maxBase: Float, memo: HashMap<String, Float>,
): Flowed? {
    fun ww(s: String) = memo.getOrPut("$s|$size|$fvar") { measureText(s, size, fp.tf, 0f, fvar) }
    val space = ww(" ").let { if (it > 0.05f) it else size * 0.26f }
    val x0 = pad
    val x1 = w - pad
    val tuck = 1.0f * u                       // type may slip this far under the subject: that is the depth cue
    val pitch = size * 0.92f
    val runs = ArrayList<Placed>()
    var splits = 0
    var wi = 0
    var line = 0
    while (wi < words.size) {
        val base = firstBase(size, pad, u) + line * pitch
        if (base > maxBase || line >= 8) return null
        var slots: List<Pair<Float, Float>> = listOf(x0 to x1)
        var blocked = false
        val ext = rowExtent(place, subj, base - size * 0.74f, base + size * 0.04f)
        if (ext != null) {
            val bl = ext.first + tuck
            val br = ext.second - tuck
            if (br > bl) {
                blocked = true
                val l = x0 to minOf(x1, bl - space * 0.6f)
                val r = maxOf(x0, br + space * 0.6f) to x1
                slots = listOfNotNull(l.takeIf { it.second - it.first > 0f }, r.takeIf { it.second - it.first > 0f })
            }
        }
        var placed = 0
        for (sl in slots) {
            val avail = sl.second - sl.first
            var used = 0f
            val start = wi
            while (wi < words.size) {
                val wd = ww(words[wi])
                val add = if (wi == start) wd else space + wd
                if (used + add > avail) break
                used += add
                wi++
            }
            if (wi > start) {
                val x = if (sl.first > x0 + 0.5f) sl.second - used else sl.first
                runs += Placed(words.subList(start, wi).joinToString(" "), x, used, line)
                placed++
            }
        }
        if (placed > 1) splits++
        if (placed == 0 && !blocked) return null          // a word wider than the whole line
        line++
    }
    return Flowed(runs, size, fvar, line, splits)
}

private fun narrowed(f: String): String {
    val lo = if (f.contains("'XOPQ'")) 40 else 75
    return Regex("'wdth' (-?\\d+)").replace(f) { "'wdth' ${(it.groupValues[1].toInt() * 0.8f).toInt().coerceAtLeast(lo)}" }
}

private fun solveDepth(art: Bitmap?, fg: Bitmap?, title: String, fp: MusicFonts.Pick, w: Int, h: Int, pad: Float, u: Float, maxBase: Float): DepthLayout {
    val plainH = (minOf(0.40f * h, maxBase - (pad + 6.4f * u))).coerceAtLeast(0.12f * h)
    val plain = layoutTitle(title, fp, w - 2 * pad, plainH)
    if (art == null) return DepthLayout(null, null, plain, false)
    val cands = SubjectFit.places(art.width, art.height, w, h)
    val subj = fg?.let { SubjectFit.subjectOf(it) }
    if (subj == null) {
        val mid = cands.first { it.kf == 1f && Math.abs(it.ox - (w - it.dw) / 2f) < 1f && Math.abs(it.oy - (h - it.dh) / 2f) < 1f }
        return DepthLayout(mid, null, plain, false)
    }

    val words = title.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf(title) }
    val memo = HashMap<String, Float>()
    val longest = words.maxOf { measureText(it, 100f, fp.tf, 0f, fp.fvar) }
    val sMax = minOf(0.30f * h, 100f * (w - 2 * pad) / longest)
    val variants = ArrayList<String?>().apply { add(fp.fvar); fp.fvar?.let { if (it.contains("'wdth'")) add(narrowed(it)) } }

    // The order of precedence: the subject stays where the photo puts it and the type bends around it. Only when
    // the type cannot be set at a decent size around the subject there does the photo move, and then as little as
    // possible. (Searching both freely at once pushed the subject away from the type.)
    fun moveCost(c: SubjectFit.Place) = (Math.abs(c.ox - (w - c.dw) / 2f) + Math.abs(c.oy - (h - c.dh) / 2f)) / w + (1f - c.kf) * 1.2f

    /** The largest type that flows around the subject at [c] (not smaller than [minSize]) and the share of it the subject covers. */
    fun bestAt(c: SubjectFit.Place, minSize: Float): Pair<Flowed, Float>? {
        var bf: Flowed? = null
        var bov = 0f
        var bs = -1f
        for ((vi, v) in variants.withIndex()) {
            var size = sMax
            for (i in 0 until 14) {
                if (size < minSize) break
                val f = flow(words, size, v, fp, c, subj, w, pad, u, maxBase, memo)
                if (f != null) {
                    val rects = f.runs.map {
                        val by = firstBase(size, pad, u) + it.line * size * 0.92f
                        RectF(it.x, by - size * 0.70f, it.x + it.w, by)
                    }
                    val ov = SubjectFit.covered(c, subj, rects)
                    if (ov <= 0.10f) {
                        val sc = size * (if (vi > 0) 0.97f else 1f) - f.splits * 0.004f * sMax - ov * sMax
                        if (sc > bs) { bs = sc; bf = f; bov = ov }
                        break                                   // sizes run downwards: the first fit is the largest
                    }
                }
                size *= 0.9f
            }
        }
        return bf?.let { it to bov }
    }

    val valid = cands.filter { SubjectFit.outside(it, subj, w, h) < 0.02f }.sortedBy { moveCost(it) }
    var bestPlace: SubjectFit.Place? = null
    var bestFlow: Flowed? = null

    // pass 1: the least-moved placements at which the type is still big (a few equally-moved ones compete on size)
    val decent = maxOf(0.075f * h, 0.35f * sMax)
    var m0 = -1f
    var bestScore = -1f
    for (c in valid) {
        val mv = moveCost(c)
        if (m0 >= 0f && mv > m0 + 0.04f) break
        val r = bestAt(c, decent) ?: continue
        if (m0 < 0f) m0 = mv
        val sc = r.first.size - r.second * sMax
        if (sc > bestScore) { bestScore = sc; bestPlace = c; bestFlow = r.first }
    }
    // pass 2: nothing is big enough anywhere near: take any size, trading it against how far the photo moves
    if (bestFlow == null) {
        var best = -99f
        for (c in valid) {
            val r = bestAt(c, 0.045f * h) ?: continue
            val sc = r.first.size / sMax - moveCost(c) * 1.2f - r.second - (r.first.lines - 3).coerceAtLeast(0) * 0.05f
            if (sc > best) { best = sc; bestPlace = c; bestFlow = r.first }
        }
    }
    val bp = bestPlace
    val bf = bestFlow
    return if (bp != null && bf != null) DepthLayout(bp, bf, null, true)
    else DepthLayout(SubjectFit.centred(cands, subj, w, h), null, plain, false)
}

/** Frosted glass: the blurred cover with a light veil, cut to the given pills. */
private fun Canvas.glass(art: Bitmap, shapes: List<RectF>, w: Int, h: Int) {
    val bounds = RectF(0f, shapes.minOf { it.top } - 2f, w.toFloat(), shapes.maxOf { it.bottom } + 2f)
    val sc = saveLayer(bounds, null)
    val under = SubjectFit.underlay(art, null)
    val kc = maxOf(w / under.width.toFloat(), h / under.height.toFloat())
    val cw = under.width * kc
    val ch = under.height * kc
    drawBitmap(under, null, RectF((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
    drawRect(bounds, Paint().also { it.color = Color.argb(70, 255, 255, 255) })
    val inv = Path()
    for (r in shapes) inv.addRoundRect(r, r.height() / 2f, r.height() / 2f, Path.Direction.CW)
    inv.fillType = Path.FillType.INVERSE_WINDING
    drawPath(inv, Paint(Paint.ANTI_ALIAS_FLAG).also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    restoreToCount(sc)
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = 1f; it.color = Color.argb(90, 255, 255, 255) }
    for (r in shapes) drawRoundRect(r, r.height() / 2f, r.height() / 2f, edge)
}

class MusicDepthWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_depth

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, ACT_PREV, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, ACT_NEXT, 52))
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        Lyrics.ensure(ctx)
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val u = minOf(w, h) / 100f
        val art = MusicState.art
        val playing = MusicState.playing
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // colours from the cover: a light tint for the title, a vivid one and a deep one for the buttons
        val hsv = FloatArray(3)
        Color.colorToHSV(art?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) } ?: p.accent, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val ink = Color.HSVToColor(floatArrayOf(hue, (sat * 0.6f).coerceIn(0.12f, 0.5f), 0.97f))
        val vivid = Color.HSVToColor(floatArrayOf(hue, (sat * 1.1f).coerceIn(0.35f, 0.8f), 0.98f))
        val deep = Color.HSVToColor(floatArrayOf(hue, (sat * 0.8f).coerceIn(0.25f, 0.7f), 0.16f))

        // geometry of the bottom: buttons, then the lyric line above them; the title may run down to the lyric line
        val pad = 6.5f * u
        val rh = 0.165f * h
        val btnTop = h - 0.05f * h - rh
        val lyricSize = 4.6f * u
        val lyricBase = btnTop - 3.4f * u
        val maxBase = minOf(0.64f * h, lyricBase - lyricSize - 3f * u)

        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val fg = MusicState.cutout
        val ck = "${MusicState.key}|$w|$h|${System.identityHashCode(art)}|${System.identityHashCode(fg)}"
        val lay = DepthCache.get(ck) { solveDepth(art, fg, title, fp, w, h, pad, u, maxBase) }
        val place = lay.place

        // layer 1: the cover (its zoomed-out margins are a blurred, subject-free copy), with a soft scrim
        if (art != null && place != null) with(SubjectFit) { drawPlaced(art, place, w, h, false, 11f * u, fg) } else drawColor(p.surface)
        drawRect(full, Paint().also { it.color = Color.argb(64, 0, 0, 0) })

        // layer 2: the title, flowed around the subject
        val fl = lay.flow
        if (fl != null) {
            for (r in fl.runs) label(r.text, r.x, firstBase(fl.size, pad, u) + r.line * fl.size * 0.92f, fl.size, ink, fp.tf, fvar = fl.fvar)
        } else lay.plain?.let { (lines, size) ->
            var y = firstBase(size, pad, u)
            for (l in lines) { label(l, pad, y, size, ink, fp.tf, fvar = fp.fvar); y += size * 0.92f }
        }

        // layer 3: the subject in front of the type, darkened by the same amount as the scrim so it matches the cover
        if (lay.cutOut && fg != null && place != null) {
            val dim = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.75f, 0f, 0f, 0f, 0f, 0f, 0.75f, 0f, 0f, 0f, 0f, 0f, 0.75f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
            drawBitmap(fg, null, place.rect, Paint(Paint.FILTER_BITMAP_FLAG).also { it.colorFilter = dim })
        }

        // a gradient so the lyric line and the buttons read on any cover
        drawRect(0f, 0.5f * h, w.toFloat(), h.toFloat(), Paint().also {
            it.shader = LinearGradient(0f, 0.5f * h, 0f, h.toFloat(), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
        })

        // the artist line is above everything but the buttons
        val ap = Paint(textPaint(4.2f * u, alpha(ink, 235), Fonts.mono(ctx), Paint.Align.LEFT, 0.06f, null))
        ap.setShadowLayer(1.8f * u, 0f, 0f, Color.argb(170, 0, 0, 0))
        drawText(MusicState.artist.uppercase(), pad, pad + 3.8f * u, ap)

        // the lyric line being sung
        val line = if (MusicPrefs.lyrics(ctx)) Lyrics.current(MusicState.positionMs()) else null
        if (line != null) {
            val fv = Fonts.axes(wght = 520, wdth = 100)
            var ls = lyricSize
            val maxW = w - 2 * pad
            while (ls > 3.5f * u && measureText(line, ls, tf, 0f, fv) > maxW) ls *= 0.94f
            val shown = if (measureText(line, ls, tf, 0f, fv) > maxW) ellipsize(line, maxW, ls, tf) else line
            val lp = Paint(textPaint(ls, alpha(ink, 245), tf, Paint.Align.LEFT, 0f, fv))
            lp.setShadowLayer(1.6f * u, 0f, 0f, Color.argb(190, 0, 0, 0))
            drawText(shown, pad, lyricBase, lp)
        }

        // buttons: previous/next on the left half, play/pause on the right
        val px = 0.065f * w
        val gap = 0.03f * w
        val left = RectF(px, btnTop, w / 2f - gap / 2f, btnTop + rh)
        val right = RectF(w / 2f + gap / 2f, btnTop, w - px, btnTop + rh)
        val prev: RectF
        val next: RectF
        if (playing) { prev = left; next = left }                  // one pill; the two icons share it
        else {
            prev = RectF(left.left, btnTop, left.left + rh, btnTop + rh)
            next = RectF(left.right - rh, btnTop, left.right, btnTop + rh)
        }
        val shapes = if (playing) listOf(left, right) else listOf(prev, next, right)
        val glassOn = MusicPrefs.buttonStyle(ctx) == 1 && art != null
        val skipFill = ink
        val skipInk = deep
        val playFill = vivid
        val playInk = deep
        val cy = btnTop + rh / 2f
        val ir = rh * 0.20f
        if (glassOn && art != null) {
            glass(art, shapes, w, h)
        } else {
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = skipFill }
            for (s in shapes) if (s !== right) drawRoundRect(s, rh / 2f, rh / 2f, fillP)
            drawRoundRect(right, rh / 2f, rh / 2f, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = playFill })
        }
        val icon = if (glassOn) Color.WHITE else skipInk
        val labelInk = if (glassOn) Color.WHITE else playInk
        if (playing) {
            skipIcon(left.left + left.width() * 0.27f, cy, ir, icon, false)
            skipIcon(left.left + left.width() * 0.73f, cy, ir, icon, true)
        } else {
            skipIcon(prev.centerX(), cy, ir, icon, false)
            skipIcon(next.centerX(), cy, ir, icon, true)
        }
        label(if (playing) "PAUSE" else "PLAY", right.centerX(), baselineFor(cy, 6.2f * u, tf), 6.2f * u, labelInk, tf,
            Paint.Align.CENTER, 0.02f, Fonts.axes(wght = 580, wdth = 105))
    }
}

// ============================================================================================
// 2. FRAME: Google's M3E player concept. Controls hidden: big cover + huge title + wavy progress.
//    Controls shown (top-right toggle): smaller cover, PLAY pill + previous, next + wavy progress.
//    A long title wraps onto up to three lines and the cover gives way to it.
// ============================================================================================
class MusicFrameWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_frame

    override fun bind(ctx: Context, rv: RemoteViews) {
        val on = controlsOn(ctx)
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
        rv.setOnClickPendingIntent(R.id.zone_toggle, broadcast(ctx, ACT_TOGGLE, 54))
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, if (on) ACT_PREV else ACT_PP, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, if (on) ACT_NEXT else ACT_PP, 52))
    }

    /** Draws a wrapped title centred in the band [top, bottom]. */
    private fun Canvas.titleBlock(t: Pair<List<String>, Float>, fp: MusicFonts.Pick, cx: Float, top: Float, bottom: Float, color: Int) {
        val (lines, size) = t
        val content = size * 0.92f * (lines.size - 1) + size * 0.72f
        var y = top + (bottom - top - content) / 2f + size * 0.72f
        for (l in lines) { label(l, cx, y, size, color, fp.tf, Paint.Align.CENTER, fvar = fp.fvar); y += size * 0.92f }
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val mono = Fonts.mono(ctx)
        val u = minOf(w, h) / 100f
        val on = controlsOn(ctx)
        drawColor(p.surface)

        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val maxW = 0.90f * w

        // cover in a shape that changes from song to song; with the controls shown the cover steps aside so the
        // buttons can be as big as Google's player concept draws them
        val art = MusicState.art
        val poly = M3Shapes.pick(MusicState.key)
        if (!on) {
            // a long title takes room from the cover: try smaller covers and keep one only if the type gains clearly
            fun avail(cs: Float) = 0.82f * h - (0.04f * h + cs) - 0.015f * h
            var cs = 0.58f * h
            var t = layoutTitle(title, fp, maxW, avail(cs))
            for (c2 in floatArrayOf(0.52f * h, 0.46f * h)) {
                val t2 = layoutTitle(title, fp, maxW, avail(c2))
                if (t2.second > t.second * 1.18f) { t = t2; cs = c2 }
            }
            val ccy = 0.04f * h + cs / 2f
            if (art != null) drawShapeBitmap(poly, w / 2f, ccy, cs, art) else drawShape(poly, w / 2f, ccy, cs, p.tile)
            titleBlock(t, fp, w / 2f, 0.04f * h + cs + 0.015f * h, 0.82f * h, p.accent)
        } else {
            label(ellipsize(MusicState.artist.uppercase(), 0.62f * w, 3.8f * u, mono), w / 2f, 0.135f * h, 3.8f * u, p.muted, mono,
                Paint.Align.CENTER, 0.06f)
            titleBlock(layoutTitle(title, fp, maxW, 0.31f * h), fp, w / 2f, 0.17f * h, 0.49f * h, p.accent)
        }

        val pad = 0.08f * w
        val frac = MusicState.progress()
        val phase = wavePhase()
        val track = alpha(p.faint, 160)

        if (!on) {
            val y = 0.875f * h
            val end = wavyProgress(pad, y, w - 2 * pad, frac, u, p.accent, track, phase)
            drawRoundRect(RectF(end - 0.7f * u, y - 4.6f * u, end + 0.7f * u, y + 4.6f * u), 0.7f * u, 0.7f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
            if (MusicState.durationMs > 0) {
                label(clock(MusicState.positionMs()), pad, 0.95f * h, 3.6f * u, p.muted, mono, tracking = 0.02f)
                label(clock(MusicState.durationMs), w - pad, 0.95f * h, 3.6f * u, p.muted, mono, Paint.Align.RIGHT, 0.02f)
            }
        } else {
            // two rows of big buttons, as in the concept: PLAY pill + previous disc, next disc + wavy progress
            val rh = 0.18f * h
            val r = rh / 2f
            val side = 0.26f * w
            val gap = 0.03f * w
            val y1 = 0.60f * h
            val pill = RectF(pad, y1 - r, w - pad - side - gap, y1 + r)
            drawRoundRect(pill, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
            label(if (MusicState.playing) "PAUSE" else "PLAY", pill.centerX(), baselineFor(y1, 9f * u, tf), 9f * u, p.onAccent, tf,
                Paint.Align.CENTER, 0.02f, Fonts.axes(wght = 540, wdth = 105))
            val prev = RectF(w - pad - side, y1 - r, w - pad, y1 + r)
            drawRoundRect(prev, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
            skipIcon(prev.centerX(), y1, r * 0.42f, p.surface, false)

            val y2 = 0.83f * h
            val next = RectF(pad, y2 - r, pad + side, y2 + r)
            drawRoundRect(next, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
            skipIcon(next.centerX(), y2, r * 0.42f, p.surface, true)
            val bx = next.right + gap
            val bw = w - pad - bx
            val end = wavyProgress(bx, y2, bw, frac, u, p.accent, track, phase)
            drawRoundRect(RectF(end - 0.7f * u, y2 - 5.4f * u, end + 0.7f * u, y2 + 5.4f * u), 0.7f * u, 0.7f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
            if (MusicState.durationMs > 0) {
                label(clock(MusicState.positionMs()), bx, 0.955f * h, 3.4f * u, p.muted, mono, tracking = 0.02f)
                label(clock(MusicState.durationMs), bx + bw, 0.955f * h, 3.4f * u, p.muted, mono, Paint.Align.RIGHT, 0.02f)
            }
        }
        toggleGlyph(on, w - 9.5f * u, 8f * u, u, p.onSurface)
    }
}

// ============================================================================================
// 3. ROUND: Google's player concept, round. At rest (nothing playing, or paused) a dark near-round shape holds
//    concentric contour lines; while a track plays the same shape becomes the frame of its cover. A previous/next
//    pill sits on its upper right and the play disc on its lower left, both overlapping the edge. No text.
// ============================================================================================
class MusicRoundWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_round

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, ACT_PREV, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, ACT_NEXT, 52))
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val side = minOf(w, h).toFloat()
        val ox = (w - side) / 2f
        val oy = (h - side) / 2f
        val u = side / 100f
        fun fx(f: Float) = ox + f * side
        fun fy(f: Float) = oy + f * side

        val blob = 0.96f * side
        val bx = fx(0.5f)
        val by = fy(0.5f)
        val poly = if (MusicState.hasSession) M3Shapes.pickRound(MusicState.key) else M3Shapes.Cookie12
        val art = MusicState.art
        val framed = MusicState.hasSession && MusicState.playing && art != null

        if (framed && art != null) {
            drawShapeBitmap(poly, bx, by, blob, art)
        } else {
            drawShape(poly, bx, by, blob, p.surface)
            // contour lines: scaled copies of the same shape, drifting up and to the left as they shrink
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.strokeWidth = 0.42f * u; it.color = alpha(p.faint, 150)
            }
            for (i in 0 until 7) {
                val s = 0.90f - i * 0.105f
                val dx = -(1f - s) * 0.34f * blob
                val dy = -(1f - s) * 0.28f * blob
                drawPath(shapePath(poly, bx + dx, by + dy, blob * s), ring)
            }
        }

        // previous / next pill, upper right, overlapping the edge; solid over a cover, muted over the dark shape
        val pill = RectF(fx(0.50f), fy(0.10f), fx(0.90f), fy(0.29f))
        val rr = pill.height() / 2f
        val keyFill = if (framed) alpha(p.surface, 240) else p.muted
        val keyInk = if (framed) p.onSurface else p.surface
        drawRoundRect(pill, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = keyFill })
        skipIcon(pill.left + pill.width() * 0.27f, pill.centerY(), rr * 0.40f, keyInk, false)
        skipIcon(pill.left + pill.width() * 0.73f, pill.centerY(), rr * 0.40f, keyInk, true)

        // play disc, lower left
        val pr = 0.14f * side
        val pc = fx(0.24f)
        val pcy = fy(0.77f)
        drawCircle(pc, pcy, pr, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = if (framed) p.accent else p.muted })
        val di = if (framed) p.onAccent else p.surface
        if (MusicState.playing) pauseIcon(pc, pcy, pr * 0.34f, di) else playIcon(pc + pr * 0.06f, pcy, pr * 0.36f, di)
    }
}
