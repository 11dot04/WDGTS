@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.wdgts.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * The real Material 3 Expressive shape set. MaterialShapes entries are plain RoundedPolygons,
 * so they draw onto a widget Canvas without any Compose UI.
 */
object M3Shapes {
    val Cookie12: RoundedPolygon get() = MaterialShapes.Cookie12Sided
    val Sunny: RoundedPolygon get() = MaterialShapes.Sunny

    /** Shapes that read well as badges, frames and fill-ups. */
    val pool: List<RoundedPolygon> by lazy {
        listOf(
            MaterialShapes.Gem, MaterialShapes.Bun, MaterialShapes.Cookie6Sided, MaterialShapes.Cookie9Sided,
            MaterialShapes.Cookie12Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Clover8Leaf,
            MaterialShapes.Sunny, MaterialShapes.VerySunny, MaterialShapes.Flower, MaterialShapes.Puffy,
            MaterialShapes.PuffyDiamond, MaterialShapes.SoftBurst, MaterialShapes.Burst,
            MaterialShapes.Pentagon, MaterialShapes.Ghostish, MaterialShapes.Heart,
        )
    }

    /** Stable "random": the same seed always gives the same shape. */
    fun pick(seed: Long): RoundedPolygon =
        pool[Math.floorMod(seed xor (seed ushr 17), pool.size.toLong()).toInt()]
}

fun shapePath(poly: RoundedPolygon, cx: Float, cy: Float, size: Float): Path {
    val b = poly.calculateBounds()                       // [left, top, right, bottom]
    val s = size / maxOf(b[2] - b[0], b[3] - b[1])
    val m = Matrix().apply {
        postTranslate(-(b[0] + b[2]) / 2f, -(b[1] + b[3]) / 2f)
        postScale(s, s)
        postTranslate(cx, cy)
    }
    return poly.toPath().also { it.transform(m) }
}

/** Fills [poly] fitted into a [size] box centred on (cx, cy). */
fun Canvas.drawShape(poly: RoundedPolygon, cx: Float, cy: Float, size: Float, color: Int) {
    drawPath(shapePath(poly, cx, cy, size), Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color })
}

/** The centre-crop source rectangle of a bitmap for a destination of another aspect ratio. */
fun coverSrc(bw: Int, bh: Int, dst: RectF): Rect {
    val da = dst.width() / dst.height()
    return if (bw.toFloat() / bh > da) {
        val sw = (bh * da).toInt().coerceAtLeast(1)
        Rect((bw - sw) / 2, 0, (bw + sw) / 2, bh)
    } else {
        val sh = (bw / da).toInt().coerceAtLeast(1)
        Rect(0, (bh - sh) / 2, bw, (bh + sh) / 2)
    }
}

/** Centre-crops [bmp] over [dst]. Two bitmaps of the same size drawn this way line up exactly. */
fun Canvas.drawBitmapCover(bmp: Bitmap, dst: RectF, paint: Paint? = null) {
    drawBitmap(bmp, coverSrc(bmp.width, bmp.height, dst), dst, paint ?: Paint(Paint.FILTER_BITMAP_FLAG))
}

/** Paints the (centre-cropped) bitmap THROUGH the shape: a bitmap shader, so the edge is anti-aliased. */
fun Canvas.drawShapeBitmap(poly: RoundedPolygon, cx: Float, cy: Float, size: Float, bmp: Bitmap) {
    val dst = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    val src = coverSrc(bmp.width, bmp.height, dst)
    val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(Matrix().also { it.setRectToRect(RectF(src), dst, Matrix.ScaleToFit.FILL) })
    drawPath(shapePath(poly, cx, cy, size), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.shader = shader })
}
