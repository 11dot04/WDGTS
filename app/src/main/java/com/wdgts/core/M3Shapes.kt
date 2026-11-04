@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.wdgts.core

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
    val Cookie9: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val Sunny: RoundedPolygon get() = MaterialShapes.Sunny
    val VerySunny: RoundedPolygon get() = MaterialShapes.VerySunny
    val Flower: RoundedPolygon get() = MaterialShapes.Flower
    val Pill: RoundedPolygon get() = MaterialShapes.Pill
}

/** Fills [poly] fitted into a [size] box centred on (cx, cy). */
fun Canvas.drawShape(poly: RoundedPolygon, cx: Float, cy: Float, size: Float, color: Int) {
    val b = poly.calculateBounds()                       // [left, top, right, bottom]
    val s = size / maxOf(b[2] - b[0], b[3] - b[1])
    val m = Matrix().apply {
        postTranslate(-(b[0] + b[2]) / 2f, -(b[1] + b[3]) / 2f)
        postScale(s, s)
        postTranslate(cx, cy)
    }
    val path = poly.toPath().also { it.transform(m) }
    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color })
}
