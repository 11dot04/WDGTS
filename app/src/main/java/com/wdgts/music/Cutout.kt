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
    fun compute(c: Context, art: Bitmap, key: Long): Bitmap? {
        val f = File(c.cacheDir, "cutout_$key.png")
        if (f.exists()) return BitmapFactory.decodeFile(f.path)
        return runCatching {
            val seg = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
            )
            val res = Tasks.await(seg.process(InputImage.fromBitmap(art, 0)), 25, TimeUnit.SECONDS)
            val fg = res.foregroundBitmap ?: return null
            val cropped = cropToSubject(fg) ?: return null
            FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cropped
        }.getOrNull()
    }

    /** Trims transparent borders; rejects "subject = whole picture" (no depth to show). */
    private fun cropToSubject(b: Bitmap): Bitmap? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        var opaque = 0
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                opaque++
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
            }
        }
        if (r < 0 || opaque < w * h * 0.04 || opaque > w * h * 0.92) return null
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
    }
}
