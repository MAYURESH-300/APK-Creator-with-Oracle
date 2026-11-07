package com.example.vfx

import java.util.concurrent.ConcurrentLinkedQueue

/** World-space PNG effects. Coordinates must use the same world units as GLGameRenderer. */
enum class GameVfxType(
    val assetPath: String,
    val lifetimeSeconds: Float,
    val startSize: Float,
    val endSize: Float,
    val aspectRatio: Float = 1.0f
) {
    ATTACK_HIT("vfx/attack_hit.png", lifetimeSeconds = 0.42f, startSize = 0.95f, endSize = 2.25f),
    JUMP_DUST("vfx/jump_dust.png", lifetimeSeconds = 0.48f, startSize = 0.90f, endSize = 1.85f),
    DASH_TAIL("vfx/yellow_thunder.png", lifetimeSeconds = 0.34f, startSize = 1.65f, endSize = 2.45f, aspectRatio = 2.16f),
    /** User-provided transparent fireball sprite, moved by velocity in world space. */
    FIREBALL("vfx/fireball.png", lifetimeSeconds = 1.65f, startSize = 0.82f, endSize = 0.58f, aspectRatio = 1.0f)
}

data class GameVfxRequest(
    val type: GameVfxType,
    val x: Float,
    val y: Float,
    val z: Float,
    val sizeMultiplier: Float = 1.0f,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val velocityZ: Float = 0f
)

/**
 * Thread-safe event queue. Game/physics code may enqueue effects; GLGameRenderer consumes them
 * on the OpenGL thread through GameVfxRenderer.draw().
 */
object GameVfxManager {
    private val pending = ConcurrentLinkedQueue<GameVfxRequest>()

    fun spawnAttackHit(x: Float, y: Float, z: Float, sizeMultiplier: Float = 1.0f) {
        pending.offer(GameVfxRequest(GameVfxType.ATTACK_HIT, x, y, z, (sizeMultiplier * 1.25f).coerceIn(0.1f, 5f)))
    }

    fun spawnJumpDust(x: Float, y: Float, z: Float, sizeMultiplier: Float = 1.0f) {
        pending.offer(GameVfxRequest(GameVfxType.JUMP_DUST, x, y, z, sizeMultiplier.coerceIn(0.1f, 5f)))
    }

    fun spawnDashTail(x: Float, y: Float, z: Float, sizeMultiplier: Float = 1.0f) {
        pending.offer(GameVfxRequest(GameVfxType.DASH_TAIL, x, y, z, sizeMultiplier.coerceIn(0.1f, 5f)))
    }

    fun spawnFireball(
        x: Float, y: Float, z: Float,
        velocityX: Float, velocityY: Float, velocityZ: Float,
        sizeMultiplier: Float = 1.0f
    ) {
        pending.offer(
            GameVfxRequest(
                type = GameVfxType.FIREBALL,
                x = x, y = y, z = z,
                sizeMultiplier = sizeMultiplier.coerceIn(0.1f, 5f),
                velocityX = velocityX,
                velocityY = velocityY,
                velocityZ = velocityZ
            )
        )
    }

    internal fun pollPending(): GameVfxRequest? = pending.poll()

    fun clearPending() {
        pending.clear()
    }
}
