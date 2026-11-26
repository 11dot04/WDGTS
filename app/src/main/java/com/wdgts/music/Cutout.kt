package com.wdgts.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ML Kit subject segmentation: lifts the subject of the cover art onto a transparent bitmap so the
 * song title can sit between the background and the subject. Cached per track. The model is delivered
 * through Google Play services and downloads on first use.
 */
object Cutout {
    /** What a segmentation attempt came to. [Retry] is a failure worth trying again (model not downloaded yet, timeout). */
    sealed interface Result {
        class Ok(val bitmap: Bitmap, val note: String) : Result
        class None(val note: String) : Result
        object Retry : Result
    }

    /**
     * The cut-out for this exact picture: the file name carries a fingerprint of the art, so new art for the same song
     * (many players show a placeholder first) gets a new cut-out instead of inheriting the old one. A cut-out that does
     * not look like the art it was cut from is thrown away, and "no usable subject" is remembered per picture.
     */
    fun computeFor(c: Context, art: Bitmap, key: Long, sig: Long): Result {
        val tag = "${java.lang.Long.toHexString(key)}_${java.lang.Long.toHexString(sig)}"
        val f = File(c.cacheDir, "cutout4_$tag.png")
        val none = File(c.cacheDir, "cutout4_$tag.none")
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return Result.Ok(it, "cached") } ?: f.delete()
        if (none.exists()) return Result.None("cached: ${none.readText()}")
        val fg = try {
            val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            Tasks.await(seg.process(InputImage.fromBitmap(art, 0)), 25, TimeUnit.SECONDS).foregroundBitmap
        } catch (e: Exception) {
            return Result.Retry
        }
        val why = if (fg == null) "no foreground" else reject(clean(fg, art), art)
        if (fg == null || why != null) {
            runCatching { none.writeText(why ?: "no foreground") }
            return Result.None(why ?: "no foreground")
        }
        val tidy = clean(fg, art)
        runCatching { FileOutputStream(f).use { tidy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        prune(c)
        return Result.Ok(tidy, "new")
    }

    /** Why this cut-out is not worth using, or null when it is fine. */
    private fun reject(fg: Bitmap, art: Bitmap): String? {
        val small = Bitmap.createScaledBitmap(fg, 64, 64, true)
        val px = IntArray(64 * 64)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        val coverage = px.count { (it ushr 24) > 40 } / (64f * 64f)
        if (coverage <= 0.04f) return "subject too small (${(coverage * 100).toInt()}%)"
        if (coverage >= 0.92f) return "subject is the whole picture"
        // a real cut-out has the cover's own colours: compare it with the art where it is opaque
        var n = 0
        var diff = 0L
        for (gy in 0 until 24) for (gx in 0 until 24) {
            val x = ((gx + 0.5f) / 24f * fg.width).toInt().coerceIn(0, fg.width - 1)
            val y = ((gy + 0.5f) / 24f * fg.height).toInt().coerceIn(0, fg.height - 1)
            val p = fg.getPixel(x, y)
            if ((p ushr 24) < 230) continue
            val q = art.getPixel((x.toFloat() / fg.width * art.width).toInt().coerceIn(0, art.width - 1), (y.toFloat() / fg.height * art.height).toInt().coerceIn(0, art.height - 1))
            diff += (kotlin.math.abs(((p shr 16) and 255) - ((q shr 16) and 255)) + kotlin.math.abs(((p shr 8) and 255) - ((q shr 8) and 255)) + kotlin.math.abs((p and 255) - (q and 255))) / 3
            n++
        }
        if (n >= 12 && diff / n > 36) return "colours do not match the art (${diff / n})"
        return null
    }

    /** Keeps the newest 60 cut-outs; they are full-size PNGs. Files of the earlier versions are not used any more and go. */
    private fun prune(c: Context) {
        c.cacheDir.listFiles { f -> f.name.startsWith("cutout3_") }?.forEach { it.delete() }
        val files = c.cacheDir.listFiles { f -> f.name.startsWith("cutout4_") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(60).forEach { it.delete() }
    }

    /**
     * Segmentation leaves specks and pinholes that show up as bites out of the title. Works on a 96 x 96 grid of the mask:
     * separate islands smaller than 3% of the picture are removed, and small enclosed holes (under 4%) are filled with the
     * art's own pixels. Soft edges of what stays are kept.
     */
    private fun clean(fg: Bitmap, art: Bitmap): Bitmap {
        val n = 96
        val small = Bitmap.createScaledBitmap(fg, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val on = BooleanArray(n * n) { (px[it] ushr 24) >= 110 }
        val label = IntArray(n * n)                                       // 0 unvisited, >0 island id
        val sizes = ArrayList<Int>()
        val stack = IntArray(n * n)
        fun flood(start: Int, want: Boolean, id: Int, tag: IntArray): Int {
            var sp = 0
            var count = 0
            stack[sp++] = start; tag[start] = id
            while (sp > 0) {
                val i = stack[--sp]
                count++
                val x = i % n
                val y = i / n
                if (x > 0 && on[i - 1] == want && tag[i - 1] == 0) { tag[i - 1] = id; stack[sp++] = i - 1 }
                if (x < n - 1 && on[i + 1] == want && tag[i + 1] == 0) { tag[i + 1] = id; stack[sp++] = i + 1 }
                if (y > 0 && on[i - n] == want && tag[i - n] == 0) { tag[i - n] = id; stack[sp++] = i - n }
                if (y < n - 1 && on[i + n] == want && tag[i + n] == 0) { tag[i + n] = id; stack[sp++] = i + n }
            }
            return count
        }
        for (i in 0 until n * n) if (on[i] && label[i] == 0) sizes += flood(i, true, sizes.size + 1, label)
        if (sizes.isEmpty()) return fg
        val total = (n * n).toFloat()
        val largest = sizes.indices.maxByOrNull { sizes[it] }!!
        val dropIsland = BooleanArray(sizes.size + 1) { it > 0 && sizes[it - 1] / total < 0.03f && it - 1 != largest }
        // holes: empty regions that do not reach the border
        val hole = IntArray(n * n)
        val holeSizes = ArrayList<Int>()
        val touchesBorder = ArrayList<Boolean>()
        for (i in 0 until n * n) if (!on[i] && hole[i] == 0) {
            val id = holeSizes.size + 1
            holeSizes += flood(i, false, id, hole)
            var border = false
            for (j in 0 until n * n) if (hole[j] == id && (j % n == 0 || j % n == n - 1 || j / n == 0 || j / n == n - 1)) { border = true; break }
            touchesBorder += border
        }
        val fillHole = BooleanArray(holeSizes.size + 1) { it > 0 && !touchesBorder[it - 1] && holeSizes[it - 1] / total < 0.04f }

        val w = fg.width
        val h = fg.height
        val out = IntArray(w * h)
        fg.getPixels(out, 0, w, 0, 0, w, h)
        val artPx = IntArray(w * h)
        val scaledArt = if (art.width == w && art.height == h) art else Bitmap.createScaledBitmap(art, w, h, true)
        scaledArt.getPixels(artPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val cy = (y * n / h).coerceIn(0, n - 1)
            for (x in 0 until w) {
                val ci = cy * n + (x * n / w).coerceIn(0, n - 1)
                val i = y * w + x
                if (on[ci] && dropIsland[label[ci]]) out[i] = 0
                else if (!on[ci] && fillHole[hole[ci]] && (out[i] ushr 24) < 255) out[i] = artPx[i] or (0xFF shl 24)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun compute(c: Context, art: Bitmap, key: Long): Bitmap? {
        val f = File(c.cacheDir, "cutout2_$key.png")          // v2: full-size (v1 was cropped and could not line up)
        if (f.exists()) return BitmapFactory.decodeFile(f.path)
        return runCatching {
            val seg = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
            )
            val res = Tasks.await(seg.process(InputImage.fromBitmap(art, 0)), 25, TimeUnit.SECONDS)
            val fg = res.foregroundBitmap ?: return null
            if (!hasUsableSubject(fg)) return null
            FileOutputStream(f).use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fg
        }.getOrNull()
    }

    /** A subject that is nothing, or the whole picture, gives no depth: skip it. */
    private fun hasUsableSubject(b: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(b, 64, 64, true)
        val px = IntArray(64 * 64)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        val opaque = px.count { (it ushr 24) > 40 }
        return opaque > 64 * 64 * 0.04 && opaque < 64 * 64 * 0.92
    }
}
