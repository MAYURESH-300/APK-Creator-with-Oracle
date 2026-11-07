package com.example.teapot

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.min

/** Small isometric thumbnail made from the model's real triangle geometry. */
@Composable
fun ModelMeshPreview(model: Custom3DMesh, modifier: Modifier = Modifier) {
    val triangles = remember(model.id) { model.buildPreviewTriangles(maxTriangles = 72) }
    val materialColor = remember(model.id) {
        floatArrayOf(
            model.defaultColor.getOrElse(0) { 0.4f }.coerceIn(0f, 1f),
            model.defaultColor.getOrElse(1) { 0.7f }.coerceIn(0f, 1f),
            model.defaultColor.getOrElse(2) { 0.9f }.coerceIn(0f, 1f)
        )
    }

    Canvas(modifier = modifier) {
        val vertexCount = triangles.size / 3
        if (vertexCount < 3 || size.width <= 0f || size.height <= 0f) return@Canvas

        val px = FloatArray(vertexCount)
        val py = FloatArray(vertexCount)
        val depth = FloatArray(vertexCount)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (i in 0 until vertexCount) {
            val x = triangles[i * 3]
            val y = triangles[i * 3 + 1]
            val z = triangles[i * 3 + 2]
            // Fixed isometric camera: recognisable front/top/side faces for most props.
            px[i] = x - z
            py[i] = (x + z) * 0.38f - y
            depth[i] = x + z + y
            minX = min(minX, px[i]); maxX = max(maxX, px[i])
            minY = min(minY, py[i]); maxY = max(maxY, py[i])
        }

        val modelWidth = (maxX - minX).coerceAtLeast(0.001f)
        val modelHeight = (maxY - minY).coerceAtLeast(0.001f)
        val padding = size.minDimension * 0.10f
        val scale = min((size.width - padding * 2f) / modelWidth, (size.height - padding * 2f) / modelHeight)
        val drawnWidth = modelWidth * scale
        val drawnHeight = modelHeight * scale
        val offsetX = (size.width - drawnWidth) * 0.5f
        val offsetY = (size.height - drawnHeight) * 0.5f
        for (i in 0 until vertexCount) {
            px[i] = offsetX + (px[i] - minX) * scale
            py[i] = offsetY + (py[i] - minY) * scale
        }

        val triangleIds = (0 until vertexCount / 3).toMutableList()
        triangleIds.sortBy { tri ->
            (depth[tri * 3] + depth[tri * 3 + 1] + depth[tri * 3 + 2]) / 3f
        }

        triangleIds.forEach { tri ->
            val a = tri * 3
            val x0 = triangles[(a) * 3]; val y0 = triangles[(a) * 3 + 1]; val z0 = triangles[(a) * 3 + 2]
            val x1 = triangles[(a + 1) * 3]; val y1 = triangles[(a + 1) * 3 + 1]; val z1 = triangles[(a + 1) * 3 + 2]
            val x2 = triangles[(a + 2) * 3]; val y2 = triangles[(a + 2) * 3 + 1]; val z2 = triangles[(a + 2) * 3 + 2]
            val e1x = x1 - x0; val e1y = y1 - y0; val e1z = z1 - z0
            val e2x = x2 - x0; val e2y = y2 - y0; val e2z = z2 - z0
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val len = kotlin.math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(0.0001f)
            val light = ((nx / len) * -0.35f + (ny / len) * 0.8f + (nz / len) * 0.45f).coerceIn(0f, 1f)
            val brightness = 0.34f + light * 0.72f
            val color = Color(
                (materialColor[0] * brightness).coerceIn(0f, 1f),
                (materialColor[1] * brightness).coerceIn(0f, 1f),
                (materialColor[2] * brightness).coerceIn(0f, 1f),
                1f
            )
            val path = Path().apply {
                moveTo(px[a], py[a])
                lineTo(px[a + 1], py[a + 1])
                lineTo(px[a + 2], py[a + 2])
                close()
            }
            drawPath(path, color)
            drawPath(path, Color(0x55FFFFFF), style = Stroke(width = 0.45f))
        }
    }
}
