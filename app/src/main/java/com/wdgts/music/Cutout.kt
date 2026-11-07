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
