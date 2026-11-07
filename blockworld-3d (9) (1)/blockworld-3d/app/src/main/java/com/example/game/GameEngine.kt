package com.example.game

import com.example.model.Vector3
import kotlin.math.*
import com.example.sfx.GameFeedback
import com.example.vfx.GameVfxManager
/**
 * High-performance 3D Game Engine.
 * Thread-safe execution, instantaneous face-locking to hit targets,
 * rapid combo attacks, and crash prevention.
 */
class GameEngine {

    val player = RobloxCharacter(initialPosition = Vector3(0f, 1.5f, 0f))
    val camera = ThirdPersonCamera()
    val worldManager = WorldManager()
    val particleSystem = ParticleSystem()
    var settings = GameSettings()

    // Game stats
    var score: Int = 0
    var hitCombo: Int = 0
    var comboTimer: Float = 0f

    // Thread-safe inputs from UI
    @Volatile var moveInputX: Float = 0f
    @Volatile var moveInputY: Float = 0f

    @Volatile private var pendingJump: Boolean = false
    @Volatile private var pendingAttack: Boolean = false
    @Volatile private var pendingDash: Boolean = false
    @Volatile private var pendingFireball: Boolean = false

    private data class FireballProjectile(
        val position: Vector3,
        val velocity: Vector3,
        var remainingSeconds: Float = 1.65f
    )
    private val fireballProjectiles = mutableListOf<FireballProjectile>()

    @Volatile private var pendingSwipeX: Float = 0f
    @Volatile private var pendingSwipeY: Float = 0f
    @Volatile private var pendingZoom: Float = 0f

    // Pre-allocated vectors for zero-allocation combat queries
    private val attackDir = Vector3()
    private val hitCenter = Vector3()
    private val sparkPos = Vector3()


    // Callbacks for UI/Haptics
    var onHitTarget: ((WorldObject, damage: Float, isCritical: Boolean) -> Unit)? = null
    var onObjectDestroyed: ((WorldObject) -> Unit)? = null
    var onJump: (() -> Unit)? = null
    var onDash: (() -> Unit)? = null
    var onBouncePad: (() -> Unit)? = null
    var onFireballLaunched: (() -> Unit)? = null

    // Physics constants
    private val gravity = -26.0f
    private val moveSpeed = 9.0f
    private val groundFriction = 12.0f
    private val airFriction = 2.0f
    private val fallRespawnY = -12.0f
    private companion object {
        const val FIREBALL_SPEED = 13.5f
        const val FIREBALL_GRAVITY = 4.2f
        const val FIREBALL_DAMAGE = 40f
    }

    fun update(deltaTime: Float) {
        val dt = deltaTime.coerceIn(0.001f, 0.05f)

        // 1. Process queued Camera Gestures
        processCameraInput()

        // 2. Consume queued Actions safely on GLThread
        if (pendingJump) {
            pendingJump = false
            executeJump()
        }
        if (pendingDash) {
            pendingDash = false
            executeDash()
        }
        if (pendingAttack) {
            pendingAttack = false
            executeAttack()
        }
        if (pendingFireball) {
            pendingFireball = false
            executeFireball()
        }

        // 3. Update Combo Decay
        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) {
                hitCombo = 0
            }
        }

        // 4. Player Movement & Facing Direction
        handlePlayerMovement(dt)

        // 5. Physics & Platform Collisions
        handlePlayerPhysics(dt)

        // 6. Character Animation Updates
        player.update(dt, settings.characterRotationMode)

        // 7. World Objects Updates
        worldManager.update(dt)

        // 7b. Fireball projectile simulation uses world-space positions and can damage attackable targets.
        updateFireballProjectiles(dt)

        // 8. Particle System Updates
        particleSystem.update(dt)

        // 9. Camera Follow Target
        camera.update(player.position, dt)
    }

    private fun processCameraInput() {
        val sx = pendingSwipeX
        val sy = pendingSwipeY
        if (sx != 0f || sy != 0f) {
            pendingSwipeX = 0f
            pendingSwipeY = 0f
            camera.rotate(sx, sy, settings.cameraSensitivity, settings.invertPitch)
        }

        val z = pendingZoom
        if (z != 0f) {
            pendingZoom = 0f
            camera.zoomBy(z, settings.zoomSensitivity)
        }
    }

    private fun handlePlayerMovement(dt: Float) {
        if (camera.isSpectatorMode) {
            // Requirement 2: Spectator Mode movement scaled with zoom distance
            camera.moveSpectator(moveInputX, moveInputY, dt)
            val friction = if (player.isGrounded) groundFriction else airFriction
            player.velocity.x += (-player.velocity.x) * (friction * dt).coerceAtMost(1f)
            player.velocity.z += (-player.velocity.z) * (friction * dt).coerceAtMost(1f)
            return
        }

        if (!player.isDashing) {
            val camForward = camera.forwardXZ
            val camRight = camera.rightXZ

            val moveDirX = camRight.x * moveInputX + camForward.x * moveInputY
            val moveDirZ = camRight.z * moveInputX + camForward.z * moveInputY

            val inputLength = sqrt(moveDirX * moveDirX + moveDirZ * moveDirZ)

            if (inputLength > 0.05f) {
                val normX = moveDirX / inputLength
                val normZ = moveDirZ / inputLength
                val targetSpeed = moveSpeed * inputLength.coerceAtMost(1f)

                val accel = if (player.isGrounded) 45.0f else 18.0f
                player.velocity.x += (normX * targetSpeed - player.velocity.x) * (accel * dt).coerceAtMost(1f)
                player.velocity.z += (normZ * targetSpeed - player.velocity.z) * (accel * dt).coerceAtMost(1f)

                when (settings.characterRotationMode) {
                    CharacterRotationMode.ROTATING_TO_MOVEMENT -> {
                        val heading = Math.toDegrees(atan2(normX.toDouble(), normZ.toDouble())).toFloat()
                        player.targetRotationY = heading
                    }
                    CharacterRotationMode.NON_ROTATING_CAMERA_LOCKED -> {
                        val camHeading = Math.toDegrees(atan2(camForward.x.toDouble(), camForward.z.toDouble())).toFloat()
                        player.targetRotationY = camHeading
                    }
                }
            } else {
                val friction = if (player.isGrounded) groundFriction else airFriction
                player.velocity.x += (-player.velocity.x) * (friction * dt).coerceAtMost(1f)
                player.velocity.z += (-player.velocity.z) * (friction * dt).coerceAtMost(1f)

                if (settings.characterRotationMode == CharacterRotationMode.NON_ROTATING_CAMERA_LOCKED) {
                    val camHeading = Math.toDegrees(atan2(camForward.x.toDouble(), camForward.z.toDouble())).toFloat()
                    player.targetRotationY = camHeading
                }
            }
        }
    }

    private fun handlePlayerPhysics(dt: Float) {
        val previousX = player.position.x
        val previousY = player.position.y
        val previousZ = player.position.z

        if (!player.isGrounded || player.isDashing) {
            player.velocity.y += gravity * dt
        }

        // Resolve horizontal movement independently on X then Z. This prevents the player
        // capsule from tunnelling through solid cube/object sides while still allowing sliding
        // along a wall and jumping onto an object's top surface.
        val radius = 0.45f
        val characterHeight = 1.8f
        val proposedX = previousX + player.velocity.x * dt
        if (collidesWithSolidAt(proposedX, previousY, previousZ, radius, characterHeight)) {
            player.position.x = previousX
            player.velocity.x = 0f
        } else {
            player.position.x = proposedX
        }

        val proposedZ = previousZ + player.velocity.z * dt
        if (collidesWithSolidAt(player.position.x, previousY, proposedZ, radius, characterHeight)) {
            player.position.z = previousZ
            player.velocity.z = 0f
        } else {
            player.position.z = proposedZ
        }

        player.position.y = previousY + player.velocity.y * dt

        val landing = worldManager.checkPlatformLanding(
            playerPos = player.position,
            playerRadius = 0.45f,
            previousY = previousY,
            currentY = player.position.y
        )

        if (landing != null && player.velocity.y <= 0.1f) {
            player.position.y = landing.surfaceY
            player.velocity.y = 0f
            player.isGrounded = true

            if (landing.isBouncePad) {
                player.velocity.y = landing.bounceForce
                player.isGrounded = false
                particleSystem.spawnJumpPuff(player.position, 12)
                onBouncePad?.invoke()
            }
        } else {
            // Always verify support, including low spawn platforms whose top is near y = 0.
            // A height threshold here prevents the player from becoming airborne when walking
            // off a low platform, effectively disabling falling until the player jumps elsewhere.
            if (player.isGrounded) {
                val underLanding = worldManager.checkPlatformLanding(
                    playerPos = player.position,
                    playerRadius = 0.45f,
                    previousY = player.position.y + 0.1f,
                    currentY = player.position.y - 0.2f
                )
                if (underLanding == null) {
                    player.isGrounded = false
                }
            }
        }

        if (player.position.y < fallRespawnY) {
            respawnPlayerAtNearestSafePlatform()
        }
    }

    /** Axis-aligned solid collision using the player's X/Z radius and vertical body interval. */
    private fun collidesWithSolidAt(
        x: Float,
        feetY: Float,
        z: Float,
        radius: Float,
        height: Float
    ): Boolean {
        val headY = feetY + height
        for (obj in worldManager.objects) {
            // Imported media are visual/editable planes, never solid colliders. The player and
            // other world objects can pass through them, including when they sit inside a mesh.
            if (obj.isDestroyed || obj.isMediaPlane) continue
            val box = obj.aabb
            // A character already at/above the object's top is allowed to stand on it.
            // Thin floor platforms therefore never block horizontal movement across their top.
            if (feetY >= box.maxY - 0.025f || headY <= box.minY + 0.025f) continue
            val overlapsX = x + radius > box.minX && x - radius < box.maxX
            val overlapsZ = z + radius > box.minZ && z - radius < box.maxZ
            if (overlapsX && overlapsZ) return true
        }
        return false
    }

    private fun executeJump() {
        if (player.performJump()) {
            val jumpPosition = player.position
            // Sprite dust sits low at the character's trailing foot/back, not under the whole body.
            val facingRad = Math.toRadians(player.rotationY.toDouble())
            val backX = -kotlin.math.sin(facingRad).toFloat()
            val backZ = -kotlin.math.cos(facingRad).toFloat()
            GameVfxManager.spawnJumpDust(
                jumpPosition.x + backX * 0.32f,
                jumpPosition.y - 0.72f,
                jumpPosition.z + backZ * 0.32f,
                0.72f
            )
            GameFeedback.onPlayerJump(
                jumpPosition.x,
                jumpPosition.y - 0.85f,
                jumpPosition.z
            )
            onJump?.invoke()
        }
    }

    private fun executeDash() {
        if (player.performDash(camera.forwardXZ)) {
            val p = player.position
            // Put the lightning streak behind the character, opposite the dash direction.
            val dashX = camera.forwardXZ.x
            val dashZ = camera.forwardXZ.z
            GameVfxManager.spawnDashTail(
                p.x - dashX * 0.95f,
                p.y + 0.95f,
                p.z - dashZ * 0.95f,
                1.0f
            )
            particleSystem.spawnJumpPuff(p, 6)
            onDash?.invoke()
        }
    }

    private fun executeFireball() {
        if (!player.performAttack()) return

        // Preserve the existing first-person horizontal launch convention, but keep the
        // camera's vertical component unchanged: reversing Y made aiming up shoot down.
        // In third-person mode forwardDirection already follows camera pitch directly.
        val cameraDirection = camera.forwardDirection
        val direction = if (camera.isFirstPerson) {
            Vector3(-cameraDirection.x, cameraDirection.y, -cameraDirection.z).normalized()
        } else {
            // Third-person pitch describes camera elevation: positive pitch places the camera
            // above the target and therefore looks down. Reverse only Y for projectile aiming.
            Vector3(cameraDirection.x, -cameraDirection.y, cameraDirection.z).normalized()
        }
        val start = Vector3(
            player.position.x + direction.x * 0.75f,
            player.position.y + 1.15f + direction.y * 0.4f,
            player.position.z + direction.z * 0.75f
        )
        // Do not add a fixed upward impulse: it overrules camera pitch, especially when
        // the player aims down. Gravity is applied by updateFireballProjectiles().
        val velocity = Vector3(
            direction.x * FIREBALL_SPEED,
            direction.y * FIREBALL_SPEED,
            direction.z * FIREBALL_SPEED
        )
        fireballProjectiles.add(
            FireballProjectile(position = start.copy(), velocity = velocity.copy())
        )
        // Sprite motion matches this world-space projectile. Add your transparent PNG at
        // app/src/main/assets/vfx/fireball.png; missing art does not crash the game.
        GameVfxManager.spawnFireball(
            x = start.x, y = start.y, z = start.z,
            velocityX = velocity.x, velocityY = velocity.y, velocityZ = velocity.z
        )
        onFireballLaunched?.invoke()
    }

    private fun updateFireballProjectiles(dt: Float) {
        if (fireballProjectiles.isEmpty()) return
        val iterator = fireballProjectiles.iterator()
        while (iterator.hasNext()) {
            val projectile = iterator.next()
            projectile.remainingSeconds -= dt
            projectile.velocity.y -= FIREBALL_GRAVITY * dt
            projectile.position.add(
                projectile.velocity.x * dt,
                projectile.velocity.y * dt,
                projectile.velocity.z * dt
            )

            val target = worldManager.objects.asSequence()
                .filter { it.isAttackable && !it.isDestroyed }
                .map { it to it.position.distanceTo(projectile.position) }
                .filter { (obj, distance) -> distance <= 0.72f + maxOf(obj.size.x, obj.size.y, obj.size.z) * 0.45f }
                .minByOrNull { it.second }
                ?.first

            if (target != null) {
                val impactX = target.position.x
                val impactY = target.position.y + target.size.y * 0.35f
                val impactZ = target.position.z
                val critical = hitCombo > 2
                val damage = if (critical) FIREBALL_DAMAGE * 1.4f else FIREBALL_DAMAGE
                val destroyed = target.takeDamage(damage)
                GameFeedback.onAttackHit(impactX, impactY, impactZ)
                hitCombo++
                comboTimer = 2.5f
                score += if (critical) 60 else 40
                try { onHitTarget?.invoke(target, damage, critical) } catch (_: Exception) {}
                if (destroyed) {
                    score += 150
                    particleSystem.spawnBreakExplosion(target.position, target.color, 20)
                    try { onObjectDestroyed?.invoke(target) } catch (_: Exception) {}
                }
                iterator.remove()
            } else if (projectile.remainingSeconds <= 0f || projectile.position.y < -15f) {
                iterator.remove()
            }
        }
    }

    private fun executeAttack() {
        if (player.performAttack()) {
            val attackRange = 2.6f
            val rad = Math.toRadians(player.rotationY.toDouble())
            attackDir.x = sin(rad).toFloat()
            attackDir.y = 0f
            attackDir.z = cos(rad).toFloat()

            hitCenter.x = player.position.x + attackDir.x * 1.1f
            hitCenter.y = player.position.y + 0.8f
            hitCenter.z = player.position.z + attackDir.z * 1.1f

            val hitTargets = worldManager.findAttackableTargets(hitCenter, attackRange)

            if (hitTargets.isNotEmpty()) {
                // Requirement 2: Character faces target object on hit
                var closestTarget: WorldObject? = null
                var closestDist = Float.MAX_VALUE
                for (i in hitTargets.indices) {
                    val t = hitTargets[i]
                    val d = player.position.distanceTo(t.position)
                    if (d < closestDist) {
                        closestDist = d
                        closestTarget = t
                    }
                }

                if (closestTarget != null) {
                    val dx = closestTarget.position.x - player.position.x
                    val dz = closestTarget.position.z - player.position.z
                    val targetHeading = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
                    player.rotationY = targetHeading
                    player.targetRotationY = targetHeading
                }

                for (i in hitTargets.indices) {
                    val target = hitTargets[i]
                    val isCrit = hitCombo > 2
                    val damage = if (isCrit) 35f else 25f
                    val destroyed = target.takeDamage(damage)

                    /* ================= ATTACK HIT SFX + VFX ================= */

                    sparkPos.x = target.position.x
                    sparkPos.y = target.position.y + target.size.y * 0.4f
                    sparkPos.z = target.position.z

// Existing particle effect
                    particleSystem.spawnHitSparks(sparkPos, target.color, 12)

// New attack impact sound and visual effect
                    GameFeedback.onAttackHit(
                        sparkPos.x,
                        sparkPos.y,
                        sparkPos.z
                    )

                    /* ================= END ATTACK HIT SFX + VFX ================= */

                    hitCombo++
                    comboTimer = 2.5f
                    score += if (isCrit) 50 else 25

                    try {
                        onHitTarget?.invoke(target, damage, isCrit)
                    } catch (_: Exception) {}

                    if (destroyed) {
                        score += 150
                        particleSystem.spawnBreakExplosion(target.position, target.color, 20)
                        try {
                            onObjectDestroyed?.invoke(target)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * UI Thread endpoints: Queue requests safely.
     */
    fun performJump() {
        pendingJump = true
    }

    fun performDash() {
        pendingDash = true
    }

    fun performAttack() {
        pendingAttack = true
    }

    fun performFireball() {
        pendingFireball = true
    }

    fun rotateCamera(deltaX: Float, deltaY: Float) {
        pendingSwipeX += deltaX
        pendingSwipeY += deltaY
    }

    fun zoomCamera(delta: Float) {
        pendingZoom += delta
    }

    fun respawnPlayerAtNearestSafePlatform() {
        player.resetTo(Vector3(0f, 1.5f, 0f))
        camera.distance = settings.defaultZoomDistance
        camera.yaw = 180f
        camera.pitch = 22f
    }

    fun resetGame() {
        player.resetTo(Vector3(0f, 1.5f, 0f))
        fireballProjectiles.clear()
        worldManager.resetAll()
        particleSystem.clear()
        camera.distance = settings.defaultZoomDistance
        camera.yaw = 180f
        camera.pitch = 22f
        score = 0
        hitCombo = 0
        comboTimer = 0f
    }
}
