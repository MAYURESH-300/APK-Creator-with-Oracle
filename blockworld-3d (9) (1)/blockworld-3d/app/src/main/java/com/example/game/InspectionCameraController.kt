package com.example.game

import com.example.model.Vector3

/** Snapshot/controller for non-destructive full-screen model inspection. */
class InspectionCameraController(private val camera: ThirdPersonCamera) {
    data class Snapshot(
        val yaw: Float,
        val pitch: Float,
        val distance: Float,
        val wasSpectator: Boolean,
        val spectatorTarget: Vector3,
        val spectatorElevation: Float,
        val smoothedTarget: Vector3
    )

    private var snapshot: Snapshot? = null
    private var targetRadius: Float = 1f

    val isActive: Boolean get() = snapshot != null

    fun enter(target: Vector3, referenceSize: Float = 2f): Boolean {
        if (snapshot != null) return false
        snapshot = Snapshot(
            yaw = camera.yaw,
            pitch = camera.pitch,
            distance = camera.distance,
            wasSpectator = camera.isSpectatorMode,
            spectatorTarget = camera.spectatorTarget.copy(),
            spectatorElevation = camera.spectatorElevation,
            smoothedTarget = camera.smoothedTarget.copy()
        )
        camera.isSpectatorMode = true
        camera.spectatorTarget.set(target)
        camera.spectatorElevation = 0f
        camera.smoothedTarget.set(target)
        targetRadius = (referenceSize * 0.5f).coerceAtLeast(0.5f)
        // Start far enough away to see broad platforms; small characters remain comfortably framed.
        camera.distance = maxOf(camera.distance, referenceSize * 1.1f).coerceIn(2.0f, 36.0f)
        camera.pitch = camera.pitch.coerceIn(5f, 80f)
        return true
    }

    /** One-finger drag orbits; no world object transform is modified. */
    fun orbit(deltaX: Float, deltaY: Float) {
        if (snapshot == null) return
        camera.rotate(deltaX, deltaY, sensitivity = 1.0f, invertPitch = false)
        camera.pitch = camera.pitch.coerceIn(5f, 80f)
    }

    /** Pinch scale > 1 zooms in; scale < 1 zooms out. */
    fun zoom(scale: Float) {
        if (snapshot == null || !scale.isFinite() || scale <= 0f) return
        val minDistance = (targetRadius * 0.35f).coerceIn(0.75f, 3.5f)
        val maxDistance = (targetRadius * 8f).coerceIn(18f, 80f)
        camera.distance = (camera.distance / scale).coerceIn(minDistance, maxDistance)
    }

    fun exit(): Boolean {
        val previous = snapshot ?: return false
        camera.yaw = previous.yaw
        camera.pitch = previous.pitch
        camera.distance = previous.distance
        camera.isSpectatorMode = previous.wasSpectator
        camera.spectatorTarget.set(previous.spectatorTarget)
        camera.spectatorElevation = previous.spectatorElevation
        camera.smoothedTarget.set(previous.smoothedTarget)
        snapshot = null
        targetRadius = 1f
        return true
    }

}
