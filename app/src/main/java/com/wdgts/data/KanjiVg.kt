package com.wdgts.data

import android.content.Context
import android.graphics.Path
import androidx.core.graphics.PathParser
import java.io.File

/**
 * Stroke geometry from KanjiVG (CC BY-SA 3.0, (c) Ulrich Apel). Paths come in stroke order,
 * in a 109 x 109 box. Files are cached locally after the first fetch.
 */
object KanjiVg {
    const val BOX = 109f

    fun load(c: Context, kanji: String, allowNetwork: Boolean): List<Path>? {
        val hex = String.format("%05x", kanji.codePointAt(0))
        val f = File(c.filesDir, "kvg_$hex.svg")
        val svg = when {
            f.exists() -> f.readText()
            allowNetwork -> Http.get("https://raw.githubusercontent.com/KanjiVG/kanjivg/master/kanji/$hex.svg", 6000)
                ?.also { f.writeText(it) }
            else -> null
        } ?: return null
        val paths = Regex("""<path\b[^>]*?\sd="([^"]+)"""").findAll(svg).mapNotNull { m ->
            runCatching { PathParser.createPathFromPathData(m.groupValues[1]) }.getOrNull()
        }.toList()
        return paths.ifEmpty { null }
    }
}
