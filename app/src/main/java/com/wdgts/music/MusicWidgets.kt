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
/** Average brightness (0..1) of the top of the cover, where the title sits. */
private fun topLuminance(art: Bitmap): Float {
    val s = Bitmap.createScaledBitmap(art, 8, 8, true)
    var sum = 0f
    for (y in 0 until 4) for (x in 0 until 8) {
        val p = s.getPixel(x, y)
        sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
    }
    return sum / 32f
}

private class DWord(val text: String, val x: Float, val base: Float, val front: Boolean)
private class DepthLayout(val place: SubjectFit.Place?, val words: List<DWord>, val size: Float, val cutOut: Boolean)

/** The last few solved layouts, so two widgets of different sizes (or the app's preview) do not push each other out. */
private object DepthCache {
    private val map = object : LinkedHashMap<String, DepthLayout>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, DepthLayout>) = size > 8
    }
    @Synchronized fun get(k: String, make: () -> DepthLayout): DepthLayout = map[k] ?: make().also { map[k] = it }
}

private fun firstBase(size: Float, pad: Float, u: Float) = pad + 6.4f * u + size * 0.86f

/** The title broken into 1, 2 or 3 lines of whole words, each with the largest size that fits [maxW] x [maxH]. */
private fun titleVariants(title: String, fp: MusicFonts.Pick, maxW: Float, maxH: Float): List<Pair<List<String>, Float>> {
    val words = title.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf(title) }
    val out = ArrayList<Pair<List<String>, Float>>()
    for (n in 1..minOf(3, words.size)) {
        val per = ceil(words.size / n.toDouble()).toInt()
        val lines = words.chunked(per).map { it.joinToString(" ") }
        if (out.any { it.first == lines }) continue
        out += lines to fitSize(lines, maxW, maxH / (lines.size * 0.95f), fp.tf, 0f, fp.fvar)
    }
    return out
}

private class W(val text: String, val x: Float, val base: Float, val rect: RectF)

/** Where each word of the left-aligned title sits at [size], with its rectangle. */
private fun wordsOf(lines: List<String>, size: Float, fp: MusicFonts.Pick, pad: Float, u: Float, memo: HashMap<String, Float>): List<W> {
    fun ww(s: String) = memo.getOrPut("$s|$size") { measureText(s, size, fp.tf, 0f, fp.fvar) }
    val space = ww(" ").let { if (it > 0.05f) it else size * 0.26f }
    val out = ArrayList<W>()
    for ((i, l) in lines.withIndex()) {
        val base = firstBase(size, pad, u) + i * size * 0.92f
        var x = pad
        for (word in l.split(" ").filter { it.isNotBlank() }) {
            val wd = ww(word)
            out += W(word, x, base, RectF(x, base - size * 0.70f, x + wd, base))
            x += wd + space
        }
    }
    return out
}

/** Share of [ours] (by area) that lies over any of [theirs]. */
private fun overlapShare(ours: List<RectF>, theirs: List<RectF>): Float {
    var hit = 0f
    var tot = 0f
    for (r in ours) {
        tot += r.width() * r.height()
        for (t in theirs) {
            val iw = minOf(r.right, t.right) - maxOf(r.left, t.left)
            val ih = minOf(r.bottom, t.bottom) - maxOf(r.top, t.top)
            if (iw > 0f && ih > 0f) hit += iw * ih
        }
    }
    return if (tot <= 0f) 0f else minOf(1f, hit / tot)
}

/**
 * The title is set as one plain block, left-aligned, in its own font at its own width, and the subject stands in front of it:
 * that overlap is the depth effect. A word the subject would hide too much of (under 55% visible) is drawn in front of the
 * subject instead, so nothing disappears. The photo may zoom in a little and slide (never out, so no gaps), the line break
 * may change and the type may be up to 15% smaller; each candidate is scored by how little that costs, how many words stay
 * behind, and how little it runs into lettering printed on the cover ([boxes]) or the artist line ([artistRect]).
 * An empty [title] (the cover already says it) leaves only the photo.
 */
private fun solveDepth(
    art: Bitmap?, fg: Bitmap?, title: String, fp: MusicFonts.Pick, w: Int, h: Int, pad: Float, u: Float, maxBase: Float,
    boxes: List<CoverText.Box>, artistRect: RectF?,
): DepthLayout {
    val plainH = (minOf(0.40f * h, maxBase - (pad + 6.4f * u))).coerceAtLeast(0.12f * h)
    val variants = if (title.isBlank()) emptyList() else titleVariants(title, fp, w - 2 * pad, plainH)
    val memo = HashMap<String, Float>()
    val biggest = variants.maxByOrNull { it.second }
    fun plain(place: SubjectFit.Place?, cutOut: Boolean) = DepthLayout(
        place, biggest?.let { b -> wordsOf(b.first, b.second, fp, pad, u, memo).map { DWord(it.text, it.x, it.base, false) } } ?: emptyList(),
        biggest?.second ?: 0f, cutOut,
    )
    if (art == null) return plain(null, false)
    val subj = fg?.let { SubjectFit.subjectOf(it) }
    val readable = boxes.filter { it.h >= 0.02f }
    val mid = SubjectFit.places(art.width, art.height, w, h)
        .first { it.kf == 1f && Math.abs(it.ox - (w - it.dw) / 2f) < 1f && Math.abs(it.oy - (h - it.dh) / 2f) < 1f }
    if (biggest == null || (subj == null && readable.isEmpty())) return plain(mid, false)

    val cands = SubjectFit.placesZoomIn(art.width, art.height, w, h)
    val valid = if (subj == null) cands
    else cands.filter { SubjectFit.outside(it, subj, w, h) < 0.02f }.ifEmpty { cands.sortedBy { SubjectFit.outside(it, subj, w, h) }.take(3) }
    fun moveCost(c: SubjectFit.Place) =
        (Math.abs(c.ox - (w - c.dw) / 2f) + Math.abs(c.oy - (h - c.dh) / 2f)) / w + (c.kf - 1f) * 1.5f
    val sMax = biggest.second

    class Cand(val place: SubjectFit.Place, val words: List<W>, val front: BooleanArray, val size: Float)
    var bestScore = -1e9f
    var best: Cand? = null
    for (v in variants) for (f in floatArrayOf(1f, 0.94f, 0.88f, 0.85f)) {
        val ws = wordsOf(v.first, v.second * f, fp, pad, u, memo)
        val rects = ws.map { it.rect }
        for (c in valid) {
            val front = BooleanArray(ws.size) { i -> subj != null && 1f - SubjectFit.covered(c, subj, listOf(rects[i])) < 0.55f }
            val theirs = readable.map { RectF(c.ox + it.x0 * c.dw, c.oy + it.y0 * c.dh, c.ox + it.x1 * c.dw, c.oy + it.y1 * c.dh) }
            val score = v.second * f / sMax - 1.4f * moveCost(c) - 0.6f * front.count { it } / ws.size.toFloat() -
                2.0f * overlapShare(rects, theirs) - (artistRect?.let { overlapShare(listOf(it), theirs) } ?: 0f)
            if (score > bestScore) { bestScore = score; best = Cand(c, ws, front, v.second * f) }
        }
    }
    val b = best ?: return plain(mid, subj != null)
    return DepthLayout(b.place, b.words.mapIndexed { i, wd -> DWord(wd.text, wd.x, wd.base, b.front[i]) }, b.size, subj != null)
}

/** Greedy word wrap of a lyric line at [size]; every row but a possibly overlong single word fits [maxW]. */
private fun wrapRows(text: String, maxW: Float, size: Float, tf: android.graphics.Typeface, fv: String?): List<String> {
    val words = text.split(" ").filter { it.isNotBlank() }
    if (words.isEmpty()) return listOf(text)
    val rows = ArrayList<String>()
    var cur = ""
    for (wd in words) {
        val t = if (cur.isEmpty()) wd else "$cur $wd"
        if (cur.isNotEmpty() && measureText(t, size, tf, 0f, fv) > maxW) { rows += cur; cur = wd } else cur = t
    }
    if (cur.isNotEmpty()) rows += cur
    return rows
}

/** One lyric size for the whole song: the largest at which every line wraps to at most two rows. */
private object LyricSize {
    private var key = ""
    private var size = 0f
    @Synchronized fun forSong(maxW: Float, base: Float, floor: Float, tf: android.graphics.Typeface, fv: String?): Float {
        val lines = Lyrics.all()
        val k = "${MusicState.key}|${maxW.toInt()}|${base.toInt()}|${lines.size}"
        if (k == key) return size
        var s = base
        while (s > floor && lines.any { wrapRows(it.s, maxW, s, tf, fv).size > 2 }) s *= 0.95f
        key = k; size = s
        return s
    }
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
    drawRect(bounds, Paint().also { it.color = Color.argb(135, 255, 255, 255) })      // a light veil, so dark icons and text read on any cover
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
        val onGlass = Color.HSVToColor(floatArrayOf(hue, (sat * 0.5f).coerceIn(0.15f, 0.4f), 0.10f))
        // type over a light cover is dark, over a dark one light
        val darkType = art != null && topLuminance(art) > 0.6f
        val typeInk = if (darkType) Color.HSVToColor(floatArrayOf(hue, (sat * 0.5f).coerceIn(0.1f, 0.4f), 0.13f)) else ink

        // geometry of the bottom: buttons, then the lyric line above them; the title may run down to the lyric line
        val pad = 6.5f * u
        val rh = 0.165f * h
        val btnTop = h - 0.05f * h - rh
        val lyricSize = 4.6f * u
        val lyricBase = btnTop - 3.4f * u
        val lyricsOn = MusicPrefs.lyrics(ctx)
        val lyricRows = if (lyricsOn) 2 else 1                          // two rows are always kept free, so the title never shifts mid-song
        val maxBase = minOf(0.64f * h, lyricBase - lyricSize * (1f + 1.14f * (lyricRows - 1)) - 3f * u)

        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val fg = MusicState.cutout
        // leave out what the cover already says in readable lettering
        val hideDup = MusicPrefs.hideDuplicates(ctx)
        val boxes = MusicState.coverText
        val hideTitle = hideDup && CoverText.shows(boxes, MusicState.title, 0.04f)
        val hideArtist = hideDup && CoverText.shows(boxes, MusicState.artist, 0.018f)
        val artistSize = 4.2f * u
        val artistText = MusicState.artist.uppercase()
        val artistRect = if (hideArtist || artistText.isBlank()) null else RectF(
            pad, pad + 3.8f * u - artistSize * 0.78f, pad + measureText(artistText, artistSize, Fonts.mono(ctx), 0.06f), pad + 3.8f * u + artistSize * 0.2f,
        )
        val ck = "${MusicState.key}|$w|$h|${SubjectFit.fingerprint(art)}|${SubjectFit.fingerprint(fg)}|$lyricRows|${MusicState.coverTextSig}|$hideTitle|$hideArtist"
        val lay = DepthCache.get(ck) { solveDepth(art, fg, if (hideTitle) "" else title, fp, w, h, pad, u, maxBase, boxes, artistRect) }
        val place = lay.place

        // layer 1: the cover (its zoomed-out margins are a blurred, subject-free copy), with a soft scrim
        if (art != null && place != null) with(SubjectFit) { drawPlaced(art, place, w, h, false, 11f * u, fg) } else drawColor(p.surface)
        drawRect(full, Paint().also { it.color = Color.argb(64, 0, 0, 0) })

        // layer 2: the title, behind the subject
        for (wd in lay.words) if (!wd.front) label(wd.text, wd.x, wd.base, lay.size, typeInk, fp.tf, fvar = fp.fvar)

        // layer 3: the subject in front of the type, darkened by the same amount as the scrim so it matches the cover
        if (lay.cutOut && fg != null && place != null) {
            val dim = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.75f, 0f, 0f, 0f, 0f, 0f, 0.75f, 0f, 0f, 0f, 0f, 0f, 0.75f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
            drawBitmap(fg, null, place.rect, Paint(Paint.FILTER_BITMAP_FLAG).also { it.colorFilter = dim })
        }

        // words the subject would have hidden too much of stand in front of it instead
        for (wd in lay.words) if (wd.front) label(wd.text, wd.x, wd.base, lay.size, typeInk, fp.tf, fvar = fp.fvar)

        // a gradient so the lyric line and the buttons read on any cover
        drawRect(0f, 0.5f * h, w.toFloat(), h.toFloat(), Paint().also {
            it.shader = LinearGradient(0f, 0.5f * h, 0f, h.toFloat(), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
        })

        // the artist line is above everything but the buttons
        if (!hideArtist) {
            val ap = Paint(textPaint(artistSize, alpha(typeInk, 235), Fonts.mono(ctx), Paint.Align.LEFT, 0.06f, null))
            ap.setShadowLayer(1.8f * u, 0f, 0f, if (darkType) Color.argb(120, 255, 255, 255) else Color.argb(170, 0, 0, 0))
            drawText(artistText, pad, pad + 3.8f * u, ap)
        }

        // the lyric line being sung: one size for the whole song, wrapping to a second row instead of shrinking
        val line = if (lyricsOn) Lyrics.current(MusicState.positionMs()) else null
        if (line != null) {
            val fv = Fonts.axes(wght = 520, wdth = 100)
            val maxW = w - 2 * pad
            val ls = LyricSize.forSong(maxW, lyricSize, 3.4f * u, tf, fv)
            var rows = wrapRows(line, maxW, ls, tf, fv)
            if (rows.size > 2) rows = listOf(rows[0], ellipsize(rows.drop(1).joinToString(" "), maxW, ls, tf))
            else rows = rows.map { if (measureText(it, ls, tf, 0f, fv) > maxW) ellipsize(it, maxW, ls, tf) else it }
            val lp = Paint(textPaint(ls, alpha(ink, 245), tf, Paint.Align.LEFT, 0f, fv))
            lp.setShadowLayer(1.6f * u, 0f, 0f, Color.argb(190, 0, 0, 0))
            for ((i, r) in rows.withIndex()) drawText(r, pad, lyricBase - (rows.size - 1 - i) * ls * 1.14f, lp)
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
        val icon = if (glassOn) onGlass else skipInk
        val labelInk = if (glassOn) onGlass else playInk
        val isz = rh * 0.44f                                         // Material Symbols Sharp, outlined
        if (playing) {
            MusicIcons.draw(this, ctx, "music_prev", left.left + left.width() * 0.27f, cy, isz, icon)
            MusicIcons.draw(this, ctx, "music_next", left.left + left.width() * 0.73f, cy, isz, icon)
        } else {
            MusicIcons.draw(this, ctx, "music_prev", prev.centerX(), cy, isz, icon)
            MusicIcons.draw(this, ctx, "music_next", next.centerX(), cy, isz, icon)
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
