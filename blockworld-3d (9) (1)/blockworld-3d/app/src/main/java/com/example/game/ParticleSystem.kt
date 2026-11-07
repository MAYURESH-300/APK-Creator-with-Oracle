package com.example.game

import com.example.model.Vector3
import kotlin.random.Random

/**
 * High-performance, zero-allocation pooled 3D particle system.
 * Eliminates garbage collection stutter and completely prevents ConcurrentModificationException
 * by using pre-allocated fixed arrays.
 */
class PooledParticle {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var vz: Float = 0f
    var r: Float = 1f
    var g: Float = 1f
    var b: Float = 1f
    var a: Float = 1f
    var size: Float = 0.15f
    var life: Float = 0f
    var maxLife: Float = 0.5f
    var active: Boolean = false

    val alpha: Float
        get() = if (maxLife > 0f) (life / maxLife).coerceIn(0f, 1f) * a else 0f
}

class ParticleSystem {
    companion object {
        const val MAX_PARTICLES = 96
    }

    val pool: Array<PooledParticle> = Array(MAX_PARTICLES) { PooledParticle() }
    private var nextPoolIndex = 0

    fun update(deltaTime: Float) {
        val gravity = -14.0f
        for (i in 0 until MAX_PARTICLES) {
            val p = pool[i]
            if (!p.active) continue

            p.life -= deltaTime
            if (p.life <= 0f) {
                p.active = false
            } else {
                p.x += p.vx * deltaTime
                p.y += p.vy * deltaTime
                p.z += p.vz * deltaTime
                p.vy += gravity * deltaTime
            }
        }
    }

    private fun acquireParticle(): PooledParticle {
        // Fast round-robin allocation
        val p = pool[nextPoolIndex]
        nextPoolIndex = (nextPoolIndex + 1) % MAX_PARTICLES
        return p
    }

    fun spawnHitSparks(pos: Vector3, color: FloatArray, count: Int = 12) {
        val spawnCount = count.coerceAtMost(16)
        val cr = if (color.isNotEmpty()) color[0] else 1f
        val cg = if (color.size > 1) color[1] else 0.8f
        val cb = if (color.size > 2) color[2] else 0.2f
        val ca = if (color.size > 3) color[3] else 1f

        for (i in 0 until spawnCount) {
            val p = acquireParticle()
            p.x = pos.x
            p.y = pos.y
            p.z = pos.z
            p.vx = (Random.nextFloat() - 0.5f) * 6.5f
            p.vy = Random.nextFloat() * 4.5f + 1.2f
            p.vz = (Random.nextFloat() - 0.5f) * 6.5f
            p.r = cr
            p.g = cg
            p.b = cb
            p.a = ca
            p.size = Random.nextFloat() * 0.12f + 0.08f
            p.life = Random.nextFloat() * 0.3f + 0.2f
            p.maxLife = p.life
            p.active = true
        }
    }

    fun spawnBreakExplosion(pos: Vector3, baseColor: FloatArray, count: Int = 20) {
        val spawnCount = count.coerceAtMost(24)
        val cr = if (baseColor.isNotEmpty()) baseColor[0] else 1f
        val cg = if (baseColor.size > 1) baseColor[1] else 0.5f
        val cb = if (baseColor.size > 2) baseColor[2] else 0.2f

        for (i in 0 until spawnCount) {
            val p = acquireParticle()
            p.x = pos.x
            p.y = pos.y
            p.z = pos.z
            p.vx = (Random.nextFloat() - 0.5f) * 8.5f
            p.vy = Random.nextFloat() * 6.5f + 1.5f
            p.vz = (Random.nextFloat() - 0.5f) * 8.5f
            p.r = cr
            p.g = cg
            p.b = cb
            p.a = 1.0f
            p.size = Random.nextFloat() * 0.22f + 0.12f
            p.life = Random.nextFloat() * 0.5f + 0.35f
            p.maxLife = p.life
            p.active = true
        }
    }

    fun spawnJumpPuff(pos: Vector3, count: Int = 8) {
        val spawnCount = count.coerceAtMost(10)
        for (i in 0 until spawnCount) {
            val p = acquireParticle()
            p.x = pos.x
            p.y = pos.y + 0.05f
            p.z = pos.z
            p.vx = (Random.nextFloat() - 0.5f) * 3.5f
            p.vy = Random.nextFloat() * 1.5f + 0.2f
            p.vz = (Random.nextFloat() - 0.5f) * 3.5f
            p.r = 0.92f
            p.g = 0.95f
            p.b = 1.0f
            p.a = 0.65f
            p.size = 0.16f
            p.life = 0.3f
            p.maxLife = 0.3f
            p.active = true
        }
    }

    fun clear() {
        for (i in 0 until MAX_PARTICLES) {
            pool[i].active = false
        }
    }
}
