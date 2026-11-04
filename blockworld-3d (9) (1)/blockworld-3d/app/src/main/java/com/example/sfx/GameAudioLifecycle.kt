
package com.example.sfx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.game.GameSettingsStorage

@Composable
fun GameAudioLifecycle() {
    val context = LocalContext.current.applicationContext

    DisposableEffect(context) {
        val settings = GameSettingsStorage.loadSettings(context)

        GameAudioManager.initialize(context)
        GameAudioManager.setMasterVolume(
            if (settings.soundEnabled) 1f else 0f
        )

        if (settings.soundEnabled) {
            GameAudioManager.playBackgroundMusic()
        }

        onDispose {
            GameAudioManager.pauseBackgroundMusic()
        }
    }
}
