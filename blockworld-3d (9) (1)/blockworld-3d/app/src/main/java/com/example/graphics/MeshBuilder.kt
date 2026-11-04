package com.example.graphics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.*

object MeshBuilder {

    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
            }
        }
    }

    fun createShortBuffer(indices: ShortArray): ShortBuffer {
        return ByteBuffer.allocateDirect(indices.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(indices)
                position(0)
            }
        }
    }

    /**
     * Standard Unit Cube centered at origin (-0.5 to +0.5).
     * 24 vertices (4 per face with discrete face normals for flat/blocky shading).
     */
    val CUBE_VERTICES = floatArrayOf(
        // Front face (+Z)
        -0.5f, -0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
        // Back face (-Z)
         0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
        // Top face (+Y)
        -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
        // Bottom face (-Y)
        -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
        // Right face (+X)
         0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
        // Left face (-X)
        -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f
    )

    val CUBE_NORMALS = floatArrayOf(
        // Front (+Z)
        0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,
        // Back (-Z)
        0f, 0f, -1f,  0f, 0f, -1f,  0f, 0f, -1f,  0f, 0f, -1f,
        // Top (+Y)
        0f, 1f, 0f,   0f, 1f, 0f,   0f, 1f, 0f,   0f, 1f, 0f,
        // Bottom (-Y)
        0f, -1f, 0f,  0f, -1f, 0f,  0f, -1f, 0f,  0f, -1f, 0f,
        // Right (+X)
        1f, 0f, 0f,   1f, 0f, 0f,   1f, 0f, 0f,   1f, 0f, 0f,
        // Left (-X)
        -1f, 0f, 0f,  -1f, 0f, 0f,  -1f, 0f, 0f,  -1f, 0f, 0f
    )

    val CUBE_INDICES: ShortArray = shortArrayOf(
         0,  1,  2,   0,  2,  3, // Front
         4,  5,  6,   4,  6,  7, // Back
         8,  9, 10,   8, 10, 11, // Top
        12, 13, 14,  12, 14, 15, // Bottom
        16, 17, 18,  16, 18, 19, // Right
        20, 21, 22,  20, 22, 23  // Left
    )

    /**
     * Diamond / Crystal 3D mesh (8-sided bipyramid).
     */
    val CRYSTAL_VERTICES = floatArrayOf(
        // Top pyramid
        0f, 0.7f, 0f,   -0.5f, 0f, 0.5f,   0.5f, 0f, 0.5f,
        0f, 0.7f, 0f,    0.5f, 0f, 0.5f,   0.5f, 0f, -0.5f,
        0f, 0.7f, 0f,    0.5f, 0f, -0.5f, -0.5f, 0f, -0.5f,
        0f, 0.7f, 0f,   -0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f,
        // Bottom pyramid
        0f, -0.7f, 0f,   0.5f, 0f, 0.5f,  -0.5f, 0f, 0.5f,
        0f, -0.7f, 0f,   0.5f, 0f, -0.5f,  0.5f, 0f, 0.5f,
        0f, -0.7f, 0f,  -0.5f, 0f, -0.5f,  0.5f, 0f, -0.5f,
        0f, -0.7f, 0f,  -0.5f, 0f, 0.5f,  -0.5f, 0f, -0.5f
    )

    val CRYSTAL_NORMALS: FloatArray by lazy {
        val norms = FloatArray(CRYSTAL_VERTICES.size)
        for (i in CRYSTAL_VERTICES.indices step 9) {
            val ax = CRYSTAL_VERTICES[i]
            val ay = CRYSTAL_VERTICES[i + 1]
            val az = CRYSTAL_VERTICES[i + 2]

            val bx = CRYSTAL_VERTICES[i + 3]
            val by = CRYSTAL_VERTICES[i + 4]
            val bz = CRYSTAL_VERTICES[i + 5]

            val cx = CRYSTAL_VERTICES[i + 6]
            val cy = CRYSTAL_VERTICES[i + 7]
            val cz = CRYSTAL_VERTICES[i + 8]

            val v1x = bx - ax
            val v1y = by - ay
            val v1z = bz - az

            val v2x = cx - ax
            val v2y = cy - ay
            val v2z = cz - az

            // Cross product
            var nx = v1y * v2z - v1z * v2y
            var ny = v1z * v2x - v1x * v2z
            var nz = v1x * v2y - v1y * v2x
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0.0001f) {
                nx /= len
                ny /= len
                nz /= len
            }
            for (v in 0..2) {
                norms[i + v * 3] = nx
                norms[i + v * 3 + 1] = ny
                norms[i + v * 3 + 2] = nz
            }
        }
        norms
    }

    /**
     * Generates a Sky Dome Sphere mesh with radius 1.0.
     */
    fun createSphere(rings: Int = 16, sectors: Int = 24): Pair<FloatBuffer, ShortBuffer> {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        val R = 1.0f / (rings - 1).toFloat()
        val S = 1.0f / (sectors - 1).toFloat()

        for (r in 0 until rings) {
            for (s in 0 until sectors) {
                val y = sin(-PI / 2.0 + PI * r * R).toFloat()
                val x = (cos(2.0 * PI * s * S) * sin(PI * r * R)).toFloat()
                val z = (sin(2.0 * PI * s * S) * sin(PI * r * R)).toFloat()

                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
            }
        }

        for (r in 0 until rings - 1) {
            for (s in 0 until sectors - 1) {
                val cur = (r * sectors + s).toShort()
                val next = ((r + 1) * sectors + s).toShort()
                val curNext = (cur + 1).toShort()
                val nextNext = (next + 1).toShort()

                indices.add(cur)
                indices.add(next)
                indices.add(curNext)

                indices.add(curNext)
                indices.add(next)
                indices.add(nextNext)
            }
        }

        return Pair(
            createFloatBuffer(vertices.toFloatArray()),
            createShortBuffer(indices.toShortArray())
        )
    }

    /**
     * Simple billboard quad for particles and health bars.
     */
    val QUAD_VERTICES = floatArrayOf(
        -0.5f, -0.5f, 0f,
         0.5f, -0.5f, 0f,
         0.5f,  0.5f, 0f,
        -0.5f,  0.5f, 0f
    )

    val QUAD_INDICES = shortArrayOf(0, 1, 2, 0, 2, 3)

    /**
     * 3D Wireframe Bounding Box Edges (12 lines = 24 vertices).
     * Used for bright glowing blue selection outlines.
     */
    val WIRE_CUBE_VERTICES = floatArrayOf(
        // Bottom 4 edges
        -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,
         0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,
         0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
        -0.5f, -0.5f,  0.5f,  -0.5f, -0.5f, -0.5f,
        // Top 4 edges
        -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
         0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
         0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
        -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
        // Vertical 4 pillars
        -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
         0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
         0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
        -0.5f, -0.5f,  0.5f,  -0.5f,  0.5f,  0.5f
    )

    fun createSelectionRing(segments: Int = 24, radius: Float = 1.0f): FloatArray {
        val ring = ArrayList<Float>()
        for (i in 0 until segments) {
            val a1 = (i * 2.0 * Math.PI / segments).toFloat()
            val a2 = ((i + 1) * 2.0 * Math.PI / segments).toFloat()
            ring.add(cos(a1) * radius); ring.add(0f); ring.add(sin(a1) * radius)
            ring.add(cos(a2) * radius); ring.add(0f); ring.add(sin(a2) * radius)
        }
        return ring.toFloatArray()
    }
}
