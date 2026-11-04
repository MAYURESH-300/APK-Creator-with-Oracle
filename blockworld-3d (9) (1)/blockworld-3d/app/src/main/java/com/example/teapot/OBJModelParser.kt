package com.example.teapot

import com.example.graphics.MeshBuilder
import com.example.model.Vector3
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object OBJModelParser {

    fun parse(
        inputStream: InputStream,
        modelId: String,
        modelName: String,
        category: String = "Imported",
        defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1.0f)
    ): Custom3DMesh {
        val rawPositions = ArrayList<FloatArray>()
        val rawNormals = ArrayList<FloatArray>()

        val outPositions = ArrayList<Float>()
        val outNormals = ArrayList<Float>()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine

            val tokens = trimmed.split("\\s+".toRegex())
            when (tokens[0]) {
                "v" -> {
                    if (tokens.size >= 4) {
                        val x = tokens[1].toFloatOrNull() ?: 0f
                        val y = tokens[2].toFloatOrNull() ?: 0f
                        val z = tokens[3].toFloatOrNull() ?: 0f
                        rawPositions.add(floatArrayOf(x, y, z))

                        minX = min(minX, x)
                        minY = min(minY, y)
                        minZ = min(minZ, z)
                        maxX = max(maxX, x)
                        maxY = max(maxY, y)
                        maxZ = max(maxZ, z)
                    }
                }
                "vn" -> {
                    if (tokens.size >= 4) {
                        val nx = tokens[1].toFloatOrNull() ?: 0f
                        val ny = tokens[2].toFloatOrNull() ?: 1f
                        val nz = tokens[3].toFloatOrNull() ?: 0f
                        rawNormals.add(floatArrayOf(nx, ny, nz))
                    }
                }
                "f" -> {
                    // Face: e.g. f v1/vt1/vn1 v2/vt2/vn2 v3/vt3/vn3 or quad triangulation
                    val faceVertices = tokens.drop(1)
                    if (faceVertices.size >= 3) {
                        // Triangulate fan: (0, i, i + 1)
                        for (i in 1 until faceVertices.size - 1) {
                            addVertex(faceVertices[0], rawPositions, rawNormals, outPositions, outNormals)
                            addVertex(faceVertices[i], rawPositions, rawNormals, outPositions, outNormals)
                            addVertex(faceVertices[i + 1], rawPositions, rawNormals, outPositions, outNormals)
                        }
                    }
                }
            }
        }

        // Calculate size bounding box
        val sizeX = if (maxX >= minX) (maxX - minX).coerceAtLeast(0.5f) else 1f
        val sizeY = if (maxY >= minY) (maxY - minY).coerceAtLeast(0.5f) else 1f
        val sizeZ = if (maxZ >= minZ) (maxZ - minZ).coerceAtLeast(0.5f) else 1f

        // If no normals were specified in OBJ, compute face flat normals
        if (outNormals.isEmpty() && outPositions.isNotEmpty()) {
            for (i in 0 until outPositions.size step 9) {
                if (i + 8 < outPositions.size) {
                    val p1x = outPositions[i]; val p1y = outPositions[i + 1]; val p1z = outPositions[i + 2]
                    val p2x = outPositions[i + 3]; val p2y = outPositions[i + 4]; val p2z = outPositions[i + 5]
                    val p3x = outPositions[i + 6]; val p3y = outPositions[i + 7]; val p3z = outPositions[i + 8]

                    val v1x = p2x - p1x; val v1y = p2y - p1y; val v1z = p2z - p1z
                    val v2x = p3x - p1x; val v2y = p3y - p1y; val v2z = p3z - p1z

                    var nx = v1y * v2z - v1z * v2y
                    var ny = v1z * v2x - v1x * v2z
                    var nz = v1x * v2y - v1y * v2x
                    val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
                    nx /= len; ny /= len; nz /= len

                    repeat(3) {
                        outNormals.add(nx); outNormals.add(ny); outNormals.add(nz)
                    }
                }
            }
        }

        val posArray = outPositions.toFloatArray()
        val normArray = outNormals.toFloatArray()

        return Custom3DMesh(
            id = modelId,
            name = modelName,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(posArray),
            normalBuffer = MeshBuilder.createFloatBuffer(normArray),
            vertexCount = posArray.size / 3,
            defaultColor = defaultColor,
            boundsSize = Vector3(sizeX, sizeY, sizeZ),
            isUserImported = true
        )
    }

    private fun addVertex(
        token: String,
        positions: List<FloatArray>,
        normals: List<FloatArray>,
        outPos: ArrayList<Float>,
        outNorm: ArrayList<Float>
    ) {
        val parts = token.split("/")
        val vIdx = parts[0].toIntOrNull()?.let { if (it > 0) it - 1 else positions.size + it } ?: 0
        val pos = if (vIdx in positions.indices) positions[vIdx] else floatArrayOf(0f, 0f, 0f)
        outPos.add(pos[0]); outPos.add(pos[1]); outPos.add(pos[2])

        if (parts.size >= 3 && parts[2].isNotEmpty()) {
            val nIdx = parts[2].toIntOrNull()?.let { if (it > 0) it - 1 else normals.size + it } ?: 0
            val norm = if (nIdx in normals.indices) normals[nIdx] else floatArrayOf(0f, 1f, 0f)
            outNorm.add(norm[0]); outNorm.add(norm[1]); outNorm.add(norm[2])
        }
    }
}
