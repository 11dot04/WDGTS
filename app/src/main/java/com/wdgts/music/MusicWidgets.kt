package com.wdgts.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
// 1. DEPTH: the cover full-bleed, the title anchored to the top, the cut-out of the subject on a layer
//    above the title (the iPhone depth-wallpaper look), and a "well" at the bottom with the controls
// ============================================================================================
class MusicDepthWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_depth

    override fun bind(ctx: Context, rv: RemoteViews) {
        val on = controlsOn(ctx)
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
        rv.setOnClickPendingIntent(R.id.zone_toggle, broadcast(ctx, ACT_TOGGLE, 54))
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, if (on) ACT_PREV else ACT_PP, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, if (on) ACT_NEXT else ACT_PP, 52))
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val mono = Fonts.mono(ctx)
        val u = minOf(w, h) / 100f
        val art = MusicState.art
        val on = controlsOn(ctx)
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // layer 1: the cover, full bleed, with a soft scrim so the title reads
        if (art != null) drawBitmapCover(art, full) else drawColor(p.surface)
        drawRect(full, Paint().also { it.color = Color.argb(64, 0, 0, 0) })

        // ink for the title comes from the cover
        val hsv = FloatArray(3)
        Color.colorToHSV(art?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) } ?: p.accent, hsv)
        hsv[1] = (hsv[1] * 0.6f).coerceIn(0.12f, 0.5f); hsv[2] = 0.97f
        val ink = Color.HSVToColor(hsv)

        // the title, anchored to the top: random font + random axis settings per track
        val pad = 6.5f * u
        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val (lines, size) = layoutTitle(title, fp, w - 2 * pad, 0.40f * h)
        label(MusicState.artist.uppercase(), pad, pad + 3.8f * u, 4.2f * u, alpha(ink, 215), mono, tracking = 0.06f)
        var y = pad + 3.8f * u + 2.6f * u + size * 0.86f
        for (l in lines) { label(l, pad, y, size, ink, fp.tf, fvar = fp.fvar); y += size * 0.92f }

        // layer 2: the cut-out, on the SAME transform as the cover, so it sits exactly over it
        MusicState.cutout?.let { drawBitmapCover(it, full) }

        // the well
        val wellH = if (on) 0.36f * h else 0.17f * h
        val well = RectF(5f * u, h - 5f * u - wellH, w - 5f * u, h - 5f * u)
        drawRoundRect(well, 7f * u, 7f * u, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = alpha(p.surface, 238) })
        val bx = well.left + 5f * u
        val bw = well.width() - 10f * u
        val by = well.top + if (on) 0.27f * wellH else 0.40f * wellH
        wavyProgress(bx, by, bw, MusicState.progress(), u, p.accent, alpha(p.faint, 170), wavePhase())
        if (MusicState.durationMs > 0) {
            label(clock(MusicState.positionMs()), bx, by + 6.6f * u, 3.4f * u, p.muted, mono, tracking = 0.02f)
            label(clock(MusicState.durationMs), bx + bw, by + 6.6f * u, 3.4f * u, p.muted, mono, Paint.Align.RIGHT, 0.02f)
        }
        if (on) {
            val cy = well.bottom - 0.20f * wellH - 5.2f * u
            val cx = well.centerX()
            val r = 5.4f * u
            val pill = RectF(cx - 10f * u, cy - r, cx + 10f * u, cy + r)
            drawRoundRect(pill, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
            label(if (MusicState.playing) "PAUSE" else "PLAY", cx, baselineFor(cy, 5.2f * u, tf), 5.2f * u, p.onAccent, tf,
                Paint.Align.CENTER, 0.02f, Fonts.axes(wght = 560, wdth = 105))
            val c1 = cx - 10f * u - 3.4f * u - r
            val c2 = cx + 10f * u + 3.4f * u + r
            dot(c1, cy, r, p.tertiary); skipIcon(c1, cy, r * 0.42f, p.surface, false)
            dot(c2, cy, r, p.tertiary); skipIcon(c2, cy, r * 0.42f, p.surface, true)
        }
        toggleGlyph(on, w - 9.5f * u, 8f * u, u, Color.WHITE)
    }
}

// ============================================================================================
// 2. FRAME: Google's M3E player concept. Controls hidden: big cover + huge title + wavy progress.
//    Controls shown (top-right toggle): smaller cover, PLAY pill + previous, next + wavy progress.
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

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val mono = Fonts.mono(ctx)
        val u = minOf(w, h) / 100f
        val on = controlsOn(ctx)
        drawColor(p.surface)

        // cover in a shape that changes from song to song
        val poly = M3Shapes.pick(MusicState.key)
        val art = MusicState.art
        val cs = if (on) 0.36f * h else 0.58f * h
        val ccy = if (on) 0.21f * h else 0.33f * h
        if (art != null) drawShapeBitmap(poly, w / 2f, ccy, cs, art) else drawShape(poly, w / 2f, ccy, cs, p.tile)

        // title: random font + axes per track, as large as the card allows
        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val ts = fitSize(listOf(title), 0.90f * w, if (on) 0.15f * h else 0.19f * h, fp.tf, 0f, fp.fvar)
        label(title, w / 2f, if (on) 0.555f * h else 0.755f * h, ts, p.accent, fp.tf, Paint.Align.CENTER, fvar = fp.fvar)

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
            val rh = 0.125f * h
            val r = rh / 2f
            val y1 = 0.705f * h
            val pill = RectF(pad, y1 - r, 0.66f * w, y1 + r)
            drawRoundRect(pill, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
            label(if (MusicState.playing) "PAUSE" else "PLAY", pill.centerX(), baselineFor(y1, 6.8f * u, tf), 6.8f * u, p.onAccent, tf,
                Paint.Align.CENTER, 0.02f, Fonts.axes(wght = 540, wdth = 105))
            val prev = RectF(0.70f * w, y1 - r, w - pad, y1 + r)
            drawRoundRect(prev, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
            skipIcon(prev.centerX(), y1, r * 0.42f, p.surface, false)

            val y2 = 0.855f * h
            val next = RectF(pad, y2 - r, pad + 0.26f * w, y2 + r)
            drawRoundRect(next, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
            skipIcon(next.centerX(), y2, r * 0.42f, p.surface, true)
            val bx = next.right + 4f * u
            val bw = w - pad - bx
            val end = wavyProgress(bx, y2, bw, frac, u, p.accent, track, phase)
            drawRoundRect(RectF(end - 0.7f * u, y2 - 4.4f * u, end + 0.7f * u, y2 + 4.4f * u), 0.7f * u, 0.7f * u,
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
// 3. ROUND: a scalloped backdrop, the cover in its own shape, a wavy progress bar underneath
// ============================================================================================
class MusicRoundWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_round

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.flex(ctx)
        val mono = Fonts.mono(ctx)
        val side = minOf(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val u = side / 100f

        drawShape(M3Shapes.Cookie12, cx, cy, side, p.surface)             // scalloped backdrop
        if (!MusicState.hasSession) {
            label("♪", cx, baselineFor(cy, 22f * u, tf), 22f * u, p.faint, tf, Paint.Align.CENTER)
            return
        }

        val poly = M3Shapes.pick(MusicState.key)
        val art = MusicState.art
        val cs = 0.46f * side
        val ccy = cy - 0.10f * side
        if (art != null) drawShapeBitmap(poly, cx, ccy, cs, art) else drawShape(poly, cx, ccy, cs, p.tile)
        if (!MusicState.playing) {
            drawShape(poly, cx, ccy, cs, alpha(p.surface, 150))
            playIcon(cx, ccy, 5.5f * u, p.onSurface)
        }

        val bw = 0.52f * side
        wavyProgress(cx - bw / 2, cy + 0.27f * side, bw, MusicState.progress(), u, p.accent, alpha(p.faint, 160), wavePhase())
        val name = ellipsize(MusicState.title.uppercase(), 0.56f * side, 4.2f * u, mono)
        label(name, cx, cy + 0.42f * side, 4.2f * u, p.muted, mono, Paint.Align.CENTER, 0.04f)
    }
}
