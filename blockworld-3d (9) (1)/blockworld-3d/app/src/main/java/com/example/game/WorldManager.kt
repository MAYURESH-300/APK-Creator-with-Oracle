package com.example.game

import com.example.model.BoundingBox3D
import com.example.model.Vector3

/**
 * Manages all 3D world platforms, targets, bounce pads, and objects.
 * Designed modularly so objects can be added, removed, swapped, or imported (Requirements 10 & 11).
 */
class WorldManager {

    private val _objects = mutableListOf<WorldObject>()
    val objects: List<WorldObject> get() = _objects

    init {
        createDefaultWorld()
    }

    fun createDefaultWorld() {
        _objects.clear()

        // The scene starts with one real GLB platform and only the two supplied cube models.
        // World positions and dimensions are the collision bounds; GLGameRenderer scales each
        // swapped mesh to those same dimensions so visuals and physics stay aligned.
        _objects.add(
            WorldObject(
                id = "platform_spawn",
                name = "Spawn Platform",
                position = Vector3(0f, 0f, 0f),
                size = Vector3(16f, 0.1f, 16f),
                color = floatArrayOf(0.72f, 0.78f, 0.72f, 1f),
                type = ObjectType.PLATFORM
            )
        )
        _objects.add(
            WorldObject(
                id = "sample_cube",
                name = "Cube",
                position = Vector3(3f, 0.8f, -3f),
                size = Vector3(1.5f, 1.5f, 1.5f),
                color = floatArrayOf(0.78f, 0.8f, 0.84f, 1f),
                type = ObjectType.PLATFORM
            )
        )
        _objects.add(
            WorldObject(
                id = "sample_green_cube",
                name = "Green Cube",
                position = Vector3(-3f, 0.8f, -3f),
                size = Vector3(1.5f, 1.5f, 1.5f),
                color = floatArrayOf(0.35f, 0.8f, 0.42f, 1f),
                type = ObjectType.PLATFORM
            )
        )
    }

    fun update(deltaTime: Float) {
        for (i in _objects.indices) {
            _objects[i].update(deltaTime)
        }
    }

    /**
     * Finds attackable objects within attack range and angle of the attacker.
     */
    fun findAttackableTargets(attackerPos: Vector3, range: Float): List<WorldObject> {
        val inRange = mutableListOf<WorldObject>()
        for (obj in _objects) {
            if (obj.isAttackable && !obj.isDestroyed) {
                val dist = attackerPos.distanceTo(obj.position)
                if (dist <= range + (obj.size.x * 0.5f)) {
                    inRange.add(obj)
                }
            }
        }
        return inRange
    }

    /**
     * Finds the nearest target for the HUD coordinate tracker.
     */
    fun findNearestTarget(playerPos: Vector3): WorldObject? {
        var closest: WorldObject? = null
        var minDist = Float.MAX_VALUE
        for (obj in _objects) {
            if (obj.isAttackable && !obj.isDestroyed) {
                val d = playerPos.distanceTo(obj.position)
                if (d < minDist) {
                    minDist = d
                    closest = obj
                }
            }
        }
        return closest
    }

    /**
     * Checks vertical platform collisions and landings.
     * Returns the highest platform top Y coordinate that the player is standing over, or null.
     */
    fun checkPlatformLanding(playerPos: Vector3, playerRadius: Float, previousY: Float, currentY: Float): PlatformLanding? {
        var highestLanding: PlatformLanding? = null

        for (obj in _objects) {
            if (obj.isDestroyed || obj.isMediaPlane) continue
            val b = obj.aabb
            // Expand horizontal bounds slightly by player radius
            if (playerPos.x >= b.minX - playerRadius && playerPos.x <= b.maxX + playerRadius &&
                playerPos.z >= b.minZ - playerRadius && playerPos.z <= b.maxZ + playerRadius
            ) {
                val surfaceY = b.maxY
                // Check if player crossed surface from above or is within tolerance of surface
                if (previousY >= surfaceY - 0.25f && currentY <= surfaceY + 0.15f) {
                    if (highestLanding == null || surfaceY > highestLanding.surfaceY) {
                        highestLanding = PlatformLanding(
                            surfaceY = surfaceY,
                            isBouncePad = obj.type == ObjectType.BOUNCE_PAD,
                            bounceForce = obj.bounceForce,
                            platform = obj
                        )
                    }
                }
            }
        }
        return highestLanding
    }

    /**
     * Allows dynamically adding new objects (Requirement 11).
     */
    fun addObject(obj: WorldObject) {
        _objects.add(obj)
    }

    /** Replace the entire live scene from an immutable edit-session snapshot. */
    fun restoreSnapshot(snapshot: List<WorldObject>) {
        _objects.clear()
        snapshot.forEach { saved ->
            _objects.add(saved.copy(
                position = saved.position.copy(),
                size = saved.size.copy(),
                color = saved.color.clone(),
                initialPosition = saved.initialPosition.copy(),
                moveAxis = saved.moveAxis.copy()
            ))
        }
    }

    /**
     * Allows dynamically removing objects.
     */
    fun removeObject(id: String): Boolean {
        return _objects.removeAll { it.id == id }
    }

    /**
     * Allows dynamically swapping objects (Requirement 11).
     */
    fun swapObject(id: String, newObj: WorldObject): Boolean {
        val index = _objects.indexOfFirst { it.id == id }
        if (index != -1) {
            _objects[index] = newObj
            return true
        }
        return false
    }

    /**
     * Resets all objects to their initial positions and max health (Requirement 9).
     */
    fun resetAll() {
        for (obj in _objects) {
            obj.reset()
        }
    }
}

data class PlatformLanding(
    val surfaceY: Float,
    val isBouncePad: Boolean,
    val bounceForce: Float,
    val platform: WorldObject
)
