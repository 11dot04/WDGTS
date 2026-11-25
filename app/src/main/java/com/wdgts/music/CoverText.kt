package com.wdgts.music

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * The lettering printed on a cover, found once per picture (ML Kit text recognition, Latin script) and cached next to the
 * cut-out. The Depth widget uses it two ways: it keeps its own type clear of the cover's, and it leaves out its title or
 * artist line when the cover already says it. Drawing only compares a few strings; the recognition itself never runs in a draw.
 */
object CoverText {
    /** One recognised line, as fractions (0..1) of the cover. */
    class Box(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val h: Float get() = y1 - y0
    }

    private fun hex(v: Long) = java.lang.Long.toHexString(v)

    /** The lines on this exact picture; null means the attempt failed and is worth repeating (model still downloading, timeout). */
    fun scan(c: Context, art: Bitmap, key: Long, sig: Long): List<Box>? {
        val f = File(c.cacheDir, "covertext1_${hex(key)}_${hex(sig)}.txt")
        if (f.exists()) runCatching { return parse(f.readText()) }
        val boxes = try {
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val res = Tasks.await(rec.process(InputImage.fromBitmap(art, 0)), 20, TimeUnit.SECONDS)
            val out = ArrayList<Box>()
            for (b in res.textBlocks) for (l in b.lines) {
                val r = l.boundingBox ?: continue
                val t = l.text.replace('\n', ' ').trim()
                if (t.length < 2) continue
                out += Box(t, r.left / art.width.toFloat(), r.top / art.height.toFloat(), r.right / art.width.toFloat(), r.bottom / art.height.toFloat())
            }
            out
        } catch (e: Exception) {
            return null
        }
        runCatching { f.writeText(boxes.joinToString("\n") { "${it.x0}\t${it.y0}\t${it.x1}\t${it.y1}\t${it.text}" }) }
        prune(c)
        return boxes
    }

    private fun parse(s: String): List<Box> = s.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t', limit = 5)
        if (p.size < 5) null else {
            val n = p.take(4).map { it.toFloatOrNull() ?: return@mapNotNull null }
            Box(p[4], n[0], n[1], n[2], n[3])
        }
    }

    private fun prune(c: Context) {
        val files = c.cacheDir.listFiles { f -> f.name.startsWith("covertext1_") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(80).forEach { it.delete() }
    }

    private val STOP = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with", "x")

    private fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in STOP }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun same(a: String, b: String) = a == b || (a.length >= 5 && b.length >= 5 && lev(a, b) <= 1)

    /**
     * True when the cover itself shows [text] (at least 80% of its words, one misread letter forgiven in longer words) in
     * lettering at least [minHeight] of the cover tall, which is big enough to read on the widget.
     */
    fun shows(boxes: List<Box>, text: String, minHeight: Float): Boolean {
        val want = tokens(text)
        if (want.isEmpty() || boxes.isEmpty()) return false
        val found = HashSet<String>()
        var biggest = 0f
        for (b in boxes) {
            val have = tokens(b.text)
            var hit = false
            for (w in want) if (have.any { same(it, w) }) { found += w; hit = true }
            if (hit) biggest = maxOf(biggest, b.h)
        }
        return found.size >= Math.ceil(want.size * 0.8).toInt() && biggest >= minHeight
    }
}
