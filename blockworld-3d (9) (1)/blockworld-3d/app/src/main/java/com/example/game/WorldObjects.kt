package com.example.game

import com.example.model.BoundingBox3D
import com.example.model.Vector3
import kotlin.math.sin

enum class ObjectType {
    PLATFORM,           // Static walking surface
    MOVING_PLATFORM,    // Oscillating platform
    BOUNCE_PAD,         // Launches player high in the air
    TRAINING_DUMMY,     // Attackable blocky dummy with hit reaction & health
    TARGET_CRYSTAL,     // Glowing attackable hovering crystal
    BREAKABLE_CRATE,    // Crate that shatters into debris
    FLOATING_DRONE,     // Hovering bot target
    MEDIA_IMAGE,        // Imported image displayed on a world-space plane
    MEDIA_VIDEO         // Imported looping video with audio displayed on a world-space plane
}

/**
 * Modular 3D World Object tracked with (X, Y, Z) coordinates.
 * Media fields are optional and used only by MEDIA_IMAGE / MEDIA_VIDEO objects.
 */
data class WorldObject(
    val id: String,
    val name: String,
    var position: Vector3,
    val size: Vector3,
    val color: FloatArray, // RGBA [r, g, b, a]
    val type: ObjectType,
    var health: Float = 100f,
    val maxHealth: Float = 100f,
    var isDestroyed: Boolean = false,
    var respawnTimer: Float = 0f,
    val respawnDuration: Float = 4.0f,
    var hitFlashTimer: Float = 0f,
    // Moving platform / hovering properties:
    val initialPosition: Vector3 = position.copy(),
    val moveAxis: Vector3 = Vector3(1f, 0f, 0f),
    val moveDistance: Float = 0f,
    val moveSpeed: Float = 1.0f,
    var movePhase: Float = 0f,
    val bounceForce: Float = 16.0f, // for BOUNCE_PAD
    // Image/video plane data. Store app-private paths only; never retain the external SAF URI.
    val mediaId: String? = null,
    val mediaFilePath: String? = null,
    val mediaThumbnailPath: String? = null,
    val mediaKind: String? = null,
    val mediaAspectRatio: Float = 1f,
    var rotationYDegrees: Float = 0f,
    var rotationXDegrees: Float = 0f,
    var rotationZDegrees: Float = 0f
) {
    val aabb: BoundingBox3D
        get() = BoundingBox3D.fromCenterAndSize(position, size)

    val isMediaPlane: Boolean
        get() = type == ObjectType.MEDIA_IMAGE || type == ObjectType.MEDIA_VIDEO

    val isAttackable: Boolean
        get() = type == ObjectType.TRAINING_DUMMY ||
                type == ObjectType.TARGET_CRYSTAL ||
                type == ObjectType.BREAKABLE_CRATE ||
                type == ObjectType.FLOATING_DRONE

    fun update(deltaTime: Float) {
        if (hitFlashTimer > 0f) {
            hitFlashTimer = (hitFlashTimer - deltaTime).coerceAtLeast(0f)
        }

        if (moveDistance > 0f && !isDestroyed) {
            movePhase += deltaTime * moveSpeed
            val offset = sin(movePhase) * moveDistance
            position.x = initialPosition.x + moveAxis.x * offset
            position.y = initialPosition.y + moveAxis.y * offset
            position.z = initialPosition.z + moveAxis.z * offset
        }

        if (isDestroyed) {
            respawnTimer -= deltaTime
            if (respawnTimer <= 0f) {
                isDestroyed = false
                health = maxHealth
                hitFlashTimer = 0f
            }
        }
    }

    /** Applies damage to this object. Returns true if destroyed by this hit. */
    fun takeDamage(damage: Float): Boolean {
        if (!isAttackable || isDestroyed) return false
        health = (health - damage).coerceAtLeast(0f)
        hitFlashTimer = 0.25f
        if (health <= 0f) {
            isDestroyed = true
            respawnTimer = respawnDuration
            return true
        }
        return false
    }

    fun reset() {
        position.set(initialPosition)
        health = maxHealth
        isDestroyed = false
        respawnTimer = 0f
        hitFlashTimer = 0f
        movePhase = 0f
    }
}
