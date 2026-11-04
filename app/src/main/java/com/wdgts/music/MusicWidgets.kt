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
        rv.setOnClickPendingIntent(R.id.zone_prev, broadcast(ctx, ACT_PREV, 51))
        rv.setOnClickPendingIntent(R.id.zone_next, broadcast(ctx, ACT_NEXT, 52))
        rv.setOnClickPendingIntent(R.id.zone_play, broadcast(ctx, ACT_PP, 53))
    }

    override fun Canvas.paint(ctx: Context, w: Int, h: Int) {
        if (!MusicState.hasSession) { idleCard(ctx, w, h); return }
        val p = Palette.of(ctx)
        val tf = Fonts.display(ctx)
        val u = minOf(w, h) / 100f
        drawColor(p.surface)

        // framed cover, top-left; the shape is seeded by the track
        val poly = M3Shapes.pick(MusicState.key)
        val art = MusicState.art
        val frame = 0.50f * minOf(w, h)
        val fx = 6f * u + frame / 2f
        val fy = 6f * u + frame / 2f
        if (art != null) drawShapeBitmap(poly, fx, fy, frame, art) else drawShape(poly, fx, fy, frame, p.tile)
        dot(w - 8f * u, 8f * u, 2.4f * u, p.onSurface)

        label(MusicState.artist, w - 6f * u, 0.49f * h, 5.8f * u, p.accent, tf, Paint.Align.RIGHT)

        val fp = MusicFonts.pick(ctx, MusicState.key)
        val title = if (fp.upper) MusicState.title.uppercase() else MusicState.title
        val ts = fitSize(listOf(title), w - 12f * u, 13f * u, fp.tf, 0f, fp.fvar)
        label(title, 6f * u, 0.64f * h, ts, p.accent, fp.tf, fvar = fp.fvar)

        // M3 Expressive wavy linear progress (the real Material Components indicator)
        val bar = MaterialProgress.linear(ctx, MusicState.progress(), (w - 12f * u).toInt(), p.accent, alpha(p.faint, 150))
        if (bar != null) drawBitmap(bar, 6f * u, 0.685f * h, null)

        // controls
        val r = 0.082f * w
        val cy = 0.875f * h
        val c1 = 6f * u + r
        val c2 = c1 + 2f * r + 2.2f * u
        dot(c1, cy, r, p.tertiary); skipIcon(c1, cy, r * 0.42f, p.onAccent, false)
        dot(c2, cy, r, p.tertiary); skipIcon(c2, cy, r * 0.42f, p.onAccent, true)
        val pill = RectF(0.52f * w, cy - r, w - 6f * u, cy + r)
        drawRoundRect(pill, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = p.accent })
        val lbl = if (MusicState.playing) "PAUSE" else "PLAY"
        label(lbl, pill.centerX(), baselineFor(cy, 6.6f * u, tf), 6.6f * u, p.onAccent, tf, Paint.Align.CENTER,
            0.04f, Fonts.axes(wght = 760, wdth = 110))
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
