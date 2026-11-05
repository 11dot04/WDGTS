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
import android.widget.RemoteViews
import com.wdgts.R
import com.wdgts.core.*
import com.wdgts.widgets.ImageWidget

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

abstract class MusicBase : ImageWidget() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACT_PP || i.action == ACT_NEXT || i.action == ACT_PREV) Haptics.click(c)
        when (i.action) {
            ACT_PP -> MusicControl.send(c, MusicControl.Cmd.PLAY_PAUSE)
            ACT_NEXT -> MusicControl.send(c, MusicControl.Cmd.NEXT)
            ACT_PREV -> MusicControl.send(c, MusicControl.Cmd.PREV)
            else -> super.onReceive(c, i)
        }
    }

    override fun bind(ctx: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.zone_main, broadcast(ctx, ACT_PP, 50))
    }

    protected fun Canvas.idleCard(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)
        label("NOTHING", 8f * u, 0.50f * h, 11f * u, p.onSurface, tf, fvar = Fonts.axes(wght = 380, wdth = 110))
        label("PLAYING", 8f * u, 0.50f * h + 11f * u, 11f * u, p.muted, tf, fvar = Fonts.axes(wght = 380, wdth = 110))
        label("Needs notification access", 8f * u, h - 8f * u, 4.6f * u, p.faint, Fonts.MONO, tracking = 0.02f)
    }

    companion object {
        const val ACT_PP = "com.wdgts.MUSIC_PP"
        const val ACT_NEXT = "com.wdgts.MUSIC_NEXT"
        const val ACT_PREV = "com.wdgts.MUSIC_PREV"
    }
}

// ============================================================================================
// 1. DEPTH: title behind a cut-out of the cover subject (ML Kit subject segmentation)
// ============================================================================================
class MusicDepthWidget : MusicBase() {
    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val u = minOf(w, h) / 100f
        val art = MusicState.art

        // background and ink are derived from the cover so each track has its own colourway
        val hsv = FloatArray(3)
        Color.colorToHSV(art?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) } ?: p.surface, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f); hsv[2] = 0.30f
        drawColor(Color.HSVToColor(hsv))
        hsv[1] = 0.22f; hsv[2] = 0.97f
        val ink = Color.HSVToColor(hsv)

        // title: random font + random axis settings per track, stacked and as large as it fits
        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val words = title.split(" ").filter { it.isNotBlank() }
        val lines = if (words.size <= 2) words else words.chunked(Math.ceil(words.size / 3.0).toInt()).map { it.joinToString(" ") }
        val size = fitSize(lines, w * 0.90f, h * 0.36f, fp.tf, 0f, fp.fvar)
        val lh = size * 0.92f
        lines.forEachIndexed { i, l -> label(l, w / 2f, 0.12f * h + size * 0.85f + i * lh, size, ink, fp.tf, Paint.Align.CENTER, fvar = fp.fvar) }

        // subject in front of the title
        val cut = MusicState.cutout
        if (cut != null) {
            val s = minOf(w * 0.86f / cut.width, h * 0.80f / cut.height)
            val dw = cut.width * s
            val dh = cut.height * s
            drawBitmap(cut, null, RectF(w / 2f - dw / 2f, h - dh, w / 2f + dw / 2f, h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } else if (art != null) {
            drawShapeBitmap(M3Shapes.pick(MusicState.key), w * 0.5f, h * 0.68f, h * 0.58f, art)
        }
        label(MusicState.artist.uppercase(), 6f * u, 6f * u + 4.4f * u, 4.4f * u, alpha(ink, 210), Fonts.MONO, tracking = 0.06f)
    }
}

// ============================================================================================
// 2. FRAME: cover in a different M3 shape per song, wavy progress, controls
// ============================================================================================
class MusicFrameWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_frame

    override fun bind(ctx: Context, rv: RemoteViews) {
        super.bind(ctx, rv)
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, ACT_PREV, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, ACT_NEXT, 52))
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Layout follows Google's unreleased M3E player concept: cover in a shape, huge title, PLAY pill + previous, next + wavy progress. */
    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)

        // cover, centred, cropped to a shape that changes from song to song
        val poly = M3Shapes.pick(MusicState.key)
        val art = MusicState.art
        val cs = 0.40f * h
        val ccy = 0.235f * h
        if (art != null) drawShapeBitmap(poly, w / 2f, ccy, cs, art) else drawShape(poly, w / 2f, ccy, cs, p.tile)

        // huge title: random font + random axis settings per track, sized to span the card
        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val ts = fitSize(listOf(title), 0.90f * w, 0.20f * h, fp.tf, 0f, fp.fvar)
        label(title, w / 2f, 0.575f * h, ts, p.accent, fp.tf, Paint.Align.CENTER, fvar = fp.fvar)

        val pad = 0.06f * w
        val rh = 0.115f * h
        val r = rh / 2f
        val ink = p.surface

        // row 1: PLAY pill + previous
        val y1 = 0.715f * h
        val pill = RectF(pad, y1 - r, 0.66f * w, y1 + r)
        drawRoundRect(pill, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
        val lbl = if (MusicState.playing) "PAUSE" else "PLAY"
        label(lbl, pill.centerX(), baselineFor(y1, 6.8f * u, tf), 6.8f * u, p.onAccent, tf, Paint.Align.CENTER,
            0.02f, Fonts.axes(wght = 520, wdth = 105))
        val prev = RectF(0.70f * w, y1 - r, w - pad, y1 + r)
        drawRoundRect(prev, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
        skipIcon(prev.centerX(), y1, r * 0.42f, ink, false)

        // row 2: next + wavy progress with a thumb, times underneath
        val y2 = 0.855f * h
        val next = RectF(pad, y2 - r, pad + 0.26f * w, y2 + r)
        drawRoundRect(next, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.tertiary })
        skipIcon(next.centerX(), y2, r * 0.42f, ink, true)

        val bx = next.right + 4f * u
        val bw = w - pad - bx
        val frac = MusicState.progress()
        val bar = MaterialProgress.linear(ctx, frac, bw.toInt(), p.accent, alpha(p.faint, 150))
        if (bar != null) drawBitmap(bar, bx, y2 - bar.height / 2f, null)
        else hairline(bx, y2, bx + bw, y2, p.faint, 0.8f * u)
        val tx = bx + frac * bw
        drawRoundRect(RectF(tx - 0.7f * u, y2 - 4.6f * u, tx + 0.7f * u, y2 + 4.6f * u), 0.7f * u, 0.7f * u,
            Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
        if (MusicState.durationMs > 0) {
            label(clock(MusicState.positionMs()), bx, 0.965f * h, 3.8f * u, p.muted, Fonts.MONO, tracking = 0.02f)
            label(clock(MusicState.durationMs), w - pad, 0.965f * h, 3.8f * u, p.muted, Fonts.MONO, Paint.Align.RIGHT, 0.02f)
        }
    }
}

// ============================================================================================
// 3. ROUND: scalloped cover inside a Material 3 Expressive wavy circular progress ring
// ============================================================================================
class MusicRoundWidget : MusicBase() {
    override val layoutRes: Int = R.layout.widget_music_round

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val side = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val u = side / 100f

        // soft backdrop disc so the ring reads on any wallpaper
        drawCircle(cx, cy, side / 2f, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.surface })
        if (!MusicState.hasSession) {
            label("♪", cx, baselineFor(cy, 22f * u, tf), 22f * u, p.faint, tf, Paint.Align.CENTER)
            return
        }

        val ring = MaterialProgress.ring(ctx, MusicState.progress(), (side * 0.98f).toInt(), p.accent, alpha(p.faint, 140))
        if (ring != null) drawBitmap(ring, cx - ring.width / 2f, cy - ring.height / 2f, null)

        val art = MusicState.art
        val artSize = side * 0.60f
        if (art != null) drawShapeBitmap(M3Shapes.Cookie12, cx, cy, artSize, art)
        else drawShape(M3Shapes.Cookie12, cx, cy, artSize, p.tile)

        if (!MusicState.playing) {
            drawShape(M3Shapes.Cookie12, cx, cy, artSize, alpha(p.surface, 150))
            playIcon(cx, cy, 7f * u, p.onSurface)
        }
    }
}
