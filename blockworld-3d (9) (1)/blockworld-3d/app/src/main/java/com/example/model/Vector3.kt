package com.example.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D Vector for coordinates and physics.
 * Coordinate convention:
 * +X = East / Right
 * +Y = Up
 * +Z = South / Backward, -Z = North / Forward
 */
data class Vector3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vector3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = Vector3(x / scalar, y / scalar, z / scalar)

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthSquared(): Float = x * x + y * y + z * z

    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun distanceToXZ(other: Vector3): Float {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }

    fun normalized(): Vector3 {
        val len = length()
        return if (len > 0.0001f) Vector3(x / len, y / len, z / len) else Vector3(0f, 0f, 0f)
    }

    fun set(nx: Float, ny: Float, nz: Float) {
        x = nx
        y = ny
        z = nz
    }

    fun set(other: Vector3) {
        x = other.x
        y = other.y
        z = other.z
    }

    fun add(dx: Float, dy: Float, dz: Float) {
        x += dx
        y += dy
        z += dz
    }

    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    fun copy(): Vector3 = Vector3(x, y, z)

    companion object {
        val ZERO get() = Vector3(0f, 0f, 0f)
        val UP get() = Vector3(0f, 1f, 0f)
        val FORWARD get() = Vector3(0f, 0f, -1f) // North is -Z
        val RIGHT get() = Vector3(1f, 0f, 0f)

        fun lerp(a: Vector3, b: Vector3, t: Float): Vector3 {
            val clampedT = t.coerceIn(0f, 1f)
            return Vector3(
                a.x + (b.x - a.x) * clampedT,
                a.y + (b.y - a.y) * clampedT,
                a.z + (b.z - a.z) * clampedT
            )
        }
    }
}

/**
 * Axis-Aligned Bounding Box for 3D physics and object interaction.
 */
data class BoundingBox3D(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float
) {
    fun intersects(other: BoundingBox3D): Boolean {
        return minX <= other.maxX && maxX >= other.minX &&
                minY <= other.maxY && maxY >= other.minY &&
                minZ <= other.maxZ && maxZ >= other.minZ
    }

    fun containsPoint(p: Vector3): Boolean {
        return p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ
    }

    companion object {
        fun fromCenterAndSize(center: Vector3, size: Vector3): BoundingBox3D {
            val hx = size.x * 0.5f
            val hy = size.y * 0.5f
            val hz = size.z * 0.5f
            return BoundingBox3D(
                center.x - hx, center.y - hy, center.z - hz,
                center.x + hx, center.y + hy, center.z + hz
            )
        }
    }
}
