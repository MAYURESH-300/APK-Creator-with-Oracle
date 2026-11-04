package com.example.sfx

import com.example.vfx.GameVfxManager

/** Convenience calls for pairing a one-shot SFX with its corresponding world-space VFX. */
object GameFeedback {
    fun onPlayerJump(x: Float, feetY: Float, z: Float) {
        GameAudioManager.play(GameSound.JUMP)
        GameVfxManager.spawnJumpDust(x, feetY, z)
    }

    /** Call only after the combat engine confirms a successful hit. */
    fun onAttackHit(x: Float, y: Float, z: Float) {
        GameAudioManager.play(GameSound.ATTACK_HIT)
        GameVfxManager.spawnAttackHit(x, y, z)
    }
}
