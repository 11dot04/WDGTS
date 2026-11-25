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
        val f = File(c.cacheDir, "cutout3_$tag.png")
        val none = File(c.cacheDir, "cutout3_$tag.none")
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return Result.Ok(it, "cached") } ?: f.delete()
        if (none.exists()) return Result.None("cached: ${none.readText()}")
        val fg = try {
            val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            Tasks.await(seg.process(InputImage.fromBitmap(art, 0)), 25, TimeUnit.SECONDS).foregroundBitmap
        } catch (e: Exception) {
            return Result.Retry
        }
        val why = if (fg == null) "no foreground" else reject(fg, art)
        if (fg == null || why != null) {
            runCatching { none.writeText(why ?: "no foreground") }
            return Result.None(why ?: "no foreground")
        }
        runCatching { FileOutputStream(f).use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        prune(c)
        return Result.Ok(fg, "new")
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

    /** Keeps the newest 60 cut-outs; they are full-size PNGs. */
    private fun prune(c: Context) {
        val files = c.cacheDir.listFiles { f -> f.name.startsWith("cutout3_") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(60).forEach { it.delete() }
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
