@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.wdgts.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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

/** Centre-crops [bmp] into the shape with an anti-aliased edge. */
fun Canvas.drawShapeBitmap(poly: RoundedPolygon, cx: Float, cy: Float, size: Float, bmp: Bitmap) {
    val dst = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    val layer = saveLayer(dst, null)
    val side = minOf(bmp.width, bmp.height)
    val src = Rect((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
    drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    val mask = Paint(Paint.ANTI_ALIAS_FLAG).also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    drawPath(shapePath(poly, cx, cy, size), mask)
    restoreToCount(layer)
}
