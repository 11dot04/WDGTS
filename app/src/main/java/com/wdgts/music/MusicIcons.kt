package com.wdgts.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.wdgts.hud.engine.SvgGlyph

/** The Depth buttons' icons: Material Symbols, Sharp style, outlined, from assets/glyphs (music_prev, music_next). */
object MusicIcons {
    private val cache = HashMap<String, SvgGlyph?>()

    private fun glyph(ctx: Context, name: String): SvgGlyph? = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching { ctx.assets.open("glyphs/$name.svg").bufferedReader().use { SvgGlyph.parse(it.readText()) } }.getOrNull()
        }
    }

    /** Draws [name] centred on ([cx], [cy]) with its longest side [size] pixels. */
    fun draw(c: Canvas, ctx: Context, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val g = glyph(ctx, name) ?: return
        val ink = g.ink
        val u = size / maxOf(ink.width(), ink.height())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = color }
        g.draw(c, cx - ink.centerX() * u, cy - ink.centerY() * u, u, fill, stroke)
    }
}
