package com.example.game

import com.example.model.Vector3
import kotlin.math.*

enum class CharacterActionState {
    IDLE,
    WALKING,
    JUMPING,
    FALLING,
    ATTACKING,
    DASHING
}

class DashGhost {
    val position: Vector3 = Vector3()
    var rotationY: Float = 0f
    var alpha: Float = 0f
    var active: Boolean = false
}

/**
 * 3D Roblox-style blocky humanoid player character.
 * Dash cooldown removed for instant spamming, with smooth orientation lock on combat targets.
 */
class RobloxCharacter(
    val initialPosition: Vector3 = Vector3(0f, 1.5f, 0f)
) {
    val position: Vector3 = initialPosition.copy()
    val velocity: Vector3 = Vector3(0f, 0f, 0f)

    var rotationY: Float = 0f
    var targetRotationY: Float = 0f

    var isGrounded: Boolean = true
    /** Allows one additional jump while airborne; reset when grounded again. */
    private var hasUsedAirJump: Boolean = false
    var health: Float = 100f
    val maxHealth: Float = 100f

    // Action timers
    var attackTimer: Float = 0f
    val attackDuration: Float = 0.28f
    val isAttacking: Boolean get() = attackTimer > 0f

    // Dash Action (No cooldown - Requirement 1)
    var dashTimer: Float = 0f
    val dashDuration: Float = 0.22f
    var dashCooldownTimer: Float = 0f
    val dashCooldownMax: Float = 0f
    val isDashing: Boolean get() = dashTimer > 0f
    val canDash: Boolean get() = true // Dash cooldown removed!

    // Pre-allocated Dash Ghosts (Zero allocations)
    val ghosts: Array<DashGhost> = Array(6) { DashGhost() }
    private var nextGhostIndex = 0
    private var ghostSpawnTimer = 0f

    // Animation pose variables
    var walkPhase: Float = 0f
    var idlePhase: Float = 0f

    // Articulated Joint Angles in Degrees
    var torsoBob: Float = 0f
    var torsoTiltX: Float = 0f
    var torsoRotY: Float = 0f

    var leftArmRotX: Float = 0f
    var leftArmRotZ: Float = 0f

    var rightArmRotX: Float = 0f
    var rightArmRotY: Float = 0f
    var rightArmRotZ: Float = 0f

    var leftLegRotX: Float = 0f
    var rightLegRotX: Float = 0f

    var swordTrailAlpha: Float = 0f

    val actionState: CharacterActionState
        get() = when {
            isDashing -> CharacterActionState.DASHING
            isAttacking -> CharacterActionState.ATTACKING
            !isGrounded && velocity.y > 0.5f -> CharacterActionState.JUMPING
            !isGrounded -> CharacterActionState.FALLING
            velocity.x * velocity.x + velocity.z * velocity.z > 0.05f -> CharacterActionState.WALKING
            else -> CharacterActionState.IDLE
        }

    fun update(deltaTime: Float, rotationMode: CharacterRotationMode) {
        if (isGrounded) hasUsedAirJump = false
        if (attackTimer > 0f) {
            attackTimer = (attackTimer - deltaTime).coerceAtLeast(0f)
        }
        if (dashTimer > 0f) {
            dashTimer = (dashTimer - deltaTime).coerceAtLeast(0f)

            // Spawn pre-allocated dash ghost periodically
            ghostSpawnTimer += deltaTime
            if (ghostSpawnTimer >= 0.035f) {
                ghostSpawnTimer = 0f
                val g = ghosts[nextGhostIndex]
                nextGhostIndex = (nextGhostIndex + 1) % ghosts.size
                g.position.set(position)
                g.rotationY = rotationY
                g.alpha = 0.65f
                g.active = true
            }
        }

        // Update pre-allocated dash ghosts
        for (i in ghosts.indices) {
            val g = ghosts[i]
            if (g.active) {
                g.alpha -= deltaTime * 3.5f
                if (g.alpha <= 0f) {
                    g.active = false
                }
            }
        }

        // Smooth rotation interpolation
        var angleDiff = targetRotationY - rotationY
        while (angleDiff > 180f) angleDiff -= 360f
        while (angleDiff < -180f) angleDiff += 360f
        val rotSpeed = if (rotationMode == CharacterRotationMode.NON_ROTATING_CAMERA_LOCKED) 20.0f else 14.0f
        rotationY += angleDiff * (deltaTime * rotSpeed).coerceAtMost(1f)

        // Animation poses calculation
        val horizontalSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
        val isMoving = horizontalSpeed > 0.2f

        idlePhase += deltaTime * 2.5f
        if (isMoving && isGrounded) {
            walkPhase += deltaTime * horizontalSpeed * 3.5f
        }

        // Reset base joints
        torsoBob = 0f
        torsoTiltX = 0f
        torsoRotY = 0f
        leftArmRotZ = 0f
        rightArmRotY = 0f
        rightArmRotZ = 0f

        when (actionState) {
            CharacterActionState.DASHING -> {
                torsoTiltX = 35.0f
                leftArmRotX = 70.0f
                rightArmRotX = 70.0f
                leftArmRotZ = 15.0f
                rightArmRotZ = -15.0f
                leftLegRotX = -15.0f
                rightLegRotX = 20.0f
                swordTrailAlpha = 0.8f
            }

            CharacterActionState.ATTACKING -> {
                val attackProgress = (1.0f - (attackTimer / attackDuration)).coerceIn(0f, 1f)
                val sinProg = sin(attackProgress * PI.toFloat())
                swordTrailAlpha = sinProg
                torsoRotY = -sinProg * 25.0f
                torsoTiltX = sinProg * 10.0f

                val slashAngle = -65.0f + (attackProgress * 145.0f)
                rightArmRotX = slashAngle
                rightArmRotZ = -20.0f + sinProg * 30.0f

                leftArmRotX = -20.0f
                leftArmRotZ = 10.0f

                if (!isGrounded) {
                    leftLegRotX = -25.0f
                    rightLegRotX = 15.0f
                } else if (isMoving) {
                    leftLegRotX = sin(walkPhase) * 35.0f
                    rightLegRotX = -sin(walkPhase) * 35.0f
                } else {
                    leftLegRotX = 0f
                    rightLegRotX = 0f
                }
            }

            CharacterActionState.JUMPING, CharacterActionState.FALLING -> {
                swordTrailAlpha = 0f
                val verticalFactor = (velocity.y / 10.0f).coerceIn(-1.5f, 1.5f)
                leftArmRotX = -45.0f - verticalFactor * 15f
                rightArmRotX = -45.0f - verticalFactor * 15f
                leftArmRotZ = 20.0f
                rightArmRotZ = -20.0f
                leftLegRotX = 25.0f - verticalFactor * 10f
                rightLegRotX = -15.0f - verticalFactor * 10f
                torsoTiltX = if (velocity.y < 0) 10f else -5f
            }

            CharacterActionState.WALKING -> {
                swordTrailAlpha = 0f
                val swing = sin(walkPhase) * 42.0f
                leftArmRotX = swing
                rightArmRotX = -swing
                leftLegRotX = -swing
                rightLegRotX = swing
                torsoBob = abs(sin(walkPhase)) * 0.08f
                torsoTiltX = 5.0f
            }

            CharacterActionState.IDLE -> {
                swordTrailAlpha = 0f
                val breathe = sin(idlePhase) * 4.0f
                leftArmRotX = breathe
                rightArmRotX = -breathe * 0.5f
                leftArmRotZ = 6.0f + breathe * 0.5f
                rightArmRotZ = -6.0f - breathe * 0.5f
                leftLegRotX = 0f
                rightLegRotX = 0f
                torsoBob = sin(idlePhase * 1.2f) * 0.03f
            }
        }
    }

    /**
     * Executes dash move forward immediately without cooldown (Requirement 1).
     */
    fun performDash(cameraForwardXZ: Vector3): Boolean {
        dashTimer = dashDuration
        dashCooldownTimer = 0f
        ghostSpawnTimer = 0.05f

        val rad = Math.toRadians(rotationY.toDouble())
        val dashDirX = sin(rad).toFloat()
        val dashDirZ = cos(rad).toFloat()

        val dashSpeed = 26.0f
        velocity.x = dashDirX * dashSpeed
        velocity.z = dashDirZ * dashSpeed
        velocity.y = 2.0f
        return true
    }

    /**
     * Executes attack slash. Resets immediately on rapid taps so combo never hitches.
     */
    fun performAttack(): Boolean {
        attackTimer = attackDuration
        return true
    }

    /**
     * Executes jump.
     */
    fun performJump(jumpPower: Float = 11.5f): Boolean {
        if (isGrounded) {
            velocity.y = jumpPower
            isGrounded = false
            hasUsedAirJump = false
            return true
        }
        // One air jump is available during JUMPING/FALLING. A successful air jump
        // refreshes upward velocity so the player can recover or change jump timing.
        if (!hasUsedAirJump) {
            velocity.y = jumpPower * 0.9f
            hasUsedAirJump = true
            return true
        }
        return false
    }

    fun resetTo(spawnPos: Vector3) {
        position.set(spawnPos)
        hasUsedAirJump = false
        velocity.set(0f, 0f, 0f)
        rotationY = 0f
        targetRotationY = 0f
        isGrounded = true
        health = maxHealth
        attackTimer = 0f
        dashTimer = 0f
        dashCooldownTimer = 0f
        for (g in ghosts) g.active = false
    }
}
