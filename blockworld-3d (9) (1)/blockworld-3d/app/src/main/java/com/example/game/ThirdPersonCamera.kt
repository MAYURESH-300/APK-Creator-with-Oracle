package com.example.game

import com.example.model.Vector3
import kotlin.math.*

/**
 * Third-person Orbit Camera with pan (yaw/pitch) and zoom in/out (Requirements 1, 3, 4).
 * Includes Genshin Impact style Spectator Camera Mode with zoom-dependent variable movement speed.
 */
class ThirdPersonCamera {

    var yaw: Float = 180f   // Horizontal orbit angle in degrees
    var pitch: Float = 10f  // Vertical elevation angle in degrees
    var distance: Float = 8.5f // Zoom distance
    /** Projection field of view in degrees; adjusted only for immersive first-person zoom. */
    @Volatile var fieldOfViewDegrees: Float = 58f

    /** True while the camera is at the player's eye position instead of orbiting behind them. */
    @Volatile var isFirstPerson: Boolean = false
        private set
    private var savedThirdPersonYaw: Float = yaw
    private var savedThirdPersonPitch: Float = pitch
    private var savedThirdPersonDistance: Float = distance
    private val firstPersonSmoothedEye = Vector3()
    private var firstPersonEyeInitialized = false

    /** Target that the renderer looks at. It differs from smoothedTarget in first-person mode. */
    val viewTarget: Vector3 = Vector3(0f, 2.6f, 0f)

    val minDistance: Float = 1.0f
    val maxDistance: Float = 28.0f

    val minPitch: Float = -80.0f
    val maxPitch: Float = 89.0f

    // Smoothed target position for buttery follow
    val smoothedTarget: Vector3 = Vector3(0f, 1.5f, 0f)

    // Calculated eye position
    val eyePosition: Vector3 = Vector3()

    // Spectator Mode (Requirement 2)
    var isSpectatorMode: Boolean = false
    val spectatorTarget: Vector3 = Vector3(0f, 1.5f, 0f)
    var spectatorElevation: Float = 1.1f

    /**
     * Swiping left/right (deltaX) and up/down (deltaY) to look around (Requirement 4).
     */
    fun rotate(deltaX: Float, deltaY: Float, sensitivity: Float = 1.0f, invertPitch: Boolean = false) {
        val sens = 0.22f * sensitivity
        yaw -= deltaX * sens
        while (yaw > 360f) yaw -= 360f
        while (yaw < 0f) yaw += 360f

        val pitchSign = if (invertPitch) -1.0f else 1.0f
        pitch += deltaY * sens * pitchSign
        pitch = pitch.coerceIn(minPitch, maxPitch)
    }

    /**
     * Zoom in and out feature (Requirement 1).
     * positive delta zooms in (closer), negative zooms out (farther).
     */
    fun zoomBy(delta: Float, zoomSensitivity: Float = 1.0f) {
        distance = (distance - delta * zoomSensitivity * 0.05f).coerceIn(minDistance, maxDistance)
    }

    fun setZoom(newDistance: Float) {
        distance = newDistance.coerceIn(minDistance, maxDistance)
    }

    /** Toggle first-person view. The original orbit-camera settings are restored when exiting. */
    fun toggleFirstPerson(playerPos: Vector3, playerRotationY: Float): Boolean {
        if (!isFirstPerson) {
            savedThirdPersonYaw = yaw
            savedThirdPersonPitch = pitch
            savedThirdPersonDistance = distance
            isFirstPerson = true
            // Character rotation 0 faces +Z, while this camera's yaw 180 looks toward +Z.
            yaw = normalizeYaw(playerRotationY + 180f)
            pitch = 0f
            firstPersonSmoothedEye.set(playerPos.x, playerPos.y + 1.42f, playerPos.z)
            firstPersonEyeInitialized = true
        } else {
            isFirstPerson = false
            yaw = savedThirdPersonYaw
            pitch = savedThirdPersonPitch
            distance = savedThirdPersonDistance.coerceIn(minDistance, maxDistance)
            smoothedTarget.set(playerPos.x, playerPos.y + 1.1f, playerPos.z)
        }
        return isFirstPerson
    }

    private fun normalizeYaw(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    /** Unit direction the camera is looking, including pitch. */
    val forwardDirection: Vector3
        get() {
            val yawRad = Math.toRadians(yaw.toDouble())
            val pitchRad = Math.toRadians(pitch.toDouble())
            val cosPitch = cos(pitchRad).toFloat()
            return Vector3(
                -sin(yawRad).toFloat() * cosPitch,
                sin(pitchRad).toFloat(),
                -cos(yawRad).toFloat() * cosPitch
            ).normalized()
        }

    fun toggleSpectatorMode(playerPos: Vector3): Boolean {
        isSpectatorMode = !isSpectatorMode
        if (isSpectatorMode) {
            spectatorTarget.set(playerPos)
            spectatorElevation = 1.1f
            smoothedTarget.set(playerPos)
            smoothedTarget.y += spectatorElevation
        }
        return isSpectatorMode
    }

    fun moveSpectator(inputX: Float, inputY: Float, deltaTime: Float) {
        val camForward = forwardXZ
        val camRight = rightXZ

        val moveDirX = camRight.x * inputX + camForward.x * inputY
        val moveDirZ = camRight.z * inputX + camForward.z * inputY
        val len = sqrt(moveDirX * moveDirX + moveDirZ * moveDirZ)

        if (len > 0.02f) {
            val normX = moveDirX / len
            val normZ = moveDirZ / len

            // Genshin Impact Zoom-Dependent Speed:
            // Faster when zoomed out, slower and fine-grained when zoomed in
            val zoomRatio = (distance / 8.5f).coerceIn(0.35f, 3.5f)
            val moveSpeed = 14.0f * zoomRatio * len.coerceAtMost(1f)

            spectatorTarget.x += normX * moveSpeed * deltaTime
            spectatorTarget.z += normZ * moveSpeed * deltaTime
        }
    }

    fun update(playerPos: Vector3, deltaTime: Float) {
        val targetPos = if (isSpectatorMode) spectatorTarget else playerPos
        val desiredTargetY = if (isSpectatorMode) targetPos.y + spectatorElevation else targetPos.y + 1.1f

        val lerpFactor = (deltaTime * if (isSpectatorMode) 14f else 10f).coerceIn(0.01f, 1f)
        smoothedTarget.x += (targetPos.x - smoothedTarget.x) * lerpFactor
        smoothedTarget.y += (desiredTargetY - smoothedTarget.y) * lerpFactor
        smoothedTarget.z += (targetPos.z - smoothedTarget.z) * lerpFactor

        if (isFirstPerson && !isSpectatorMode) {
            val eyeFollow = (deltaTime * 18f).coerceIn(0.01f, 1f)
            if (!firstPersonEyeInitialized) {
                firstPersonSmoothedEye.set(playerPos.x, playerPos.y + 1.42f, playerPos.z)
                firstPersonEyeInitialized = true
            }
            firstPersonSmoothedEye.x += (playerPos.x - firstPersonSmoothedEye.x) * eyeFollow
            firstPersonSmoothedEye.y += (playerPos.y + 1.42f - firstPersonSmoothedEye.y) * eyeFollow
            firstPersonSmoothedEye.z += (playerPos.z - firstPersonSmoothedEye.z) * eyeFollow
            eyePosition.set(firstPersonSmoothedEye)
            val look = forwardDirection
            viewTarget.set(
                eyePosition.x + look.x * 12f,
                eyePosition.y + look.y * 12f,
                eyePosition.z + look.z * 12f
            )
            return
        }

        // Compute eye position in spherical coordinates for orbit/spectator modes.
        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawRad = Math.toRadians(yaw.toDouble())

        val cosPitch = cos(pitchRad).toFloat()
        val sinPitch = sin(pitchRad).toFloat()
        val sinYaw = sin(yawRad).toFloat()
        val cosYaw = cos(yawRad).toFloat()

        eyePosition.x = smoothedTarget.x + distance * cosPitch * sinYaw
        eyePosition.y = smoothedTarget.y + distance * sinPitch
        eyePosition.z = smoothedTarget.z + distance * cosPitch * cosYaw
        viewTarget.set(smoothedTarget)
    }

    val forwardXZ: Vector3
        get() {
            val yawRad = Math.toRadians(yaw.toDouble())
            return Vector3(-sin(yawRad).toFloat(), 0f, -cos(yawRad).toFloat()).normalized()
        }

    val rightXZ: Vector3
        get() {
            val yawRad = Math.toRadians(yaw.toDouble())
            return Vector3(cos(yawRad).toFloat(), 0f, -sin(yawRad).toFloat()).normalized()
        }

    val northCompassAngle: Float
        get() = -yaw
}
