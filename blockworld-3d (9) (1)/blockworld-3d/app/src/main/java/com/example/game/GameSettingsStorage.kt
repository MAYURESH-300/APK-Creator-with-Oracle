package com.example.game

import android.content.Context

/**
 * Persistent storage manager for GameSettings using Android SharedPreferences.
 * Automatically saves user configuration changes so they persist when closing and reopening the game.
 */
object GameSettingsStorage {
    private const val PREFS_NAME = "blockworld_game_settings"

    fun loadSettings(context: Context): GameSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val rotationModeStr = prefs.getString("characterRotationMode", CharacterRotationMode.ROTATING_TO_MOVEMENT.name)
        val rotationMode = try {
            CharacterRotationMode.valueOf(rotationModeStr ?: CharacterRotationMode.ROTATING_TO_MOVEMENT.name)
        } catch (_: Exception) {
            CharacterRotationMode.ROTATING_TO_MOVEMENT
        }

        val touchModeStr = prefs.getString("rightScreenTouchMode", RightScreenTouchMode.GENSHIN_JOYSTICK.name)
        val touchMode = try {
            RightScreenTouchMode.valueOf(touchModeStr ?: RightScreenTouchMode.GENSHIN_JOYSTICK.name)
        } catch (_: Exception) {
            RightScreenTouchMode.GENSHIN_JOYSTICK
        }

        val skyEnvStr = prefs.getString("skyEnvironment", SkyEnvironmentType.STYLIZED_BLUE_SKY.name)
        val skyEnv = try {
            SkyEnvironmentType.valueOf(skyEnvStr ?: SkyEnvironmentType.STYLIZED_BLUE_SKY.name)
        } catch (_: Exception) {
            SkyEnvironmentType.STYLIZED_BLUE_SKY
        }

        return GameSettings(
            characterRotationMode = rotationMode,
            rightScreenTouchMode = touchMode,
            cameraSensitivity = prefs.getFloat("cameraSensitivity", 1.0f),
            zoomSensitivity = prefs.getFloat("zoomSensitivity", 1.0f),
            invertPitch = prefs.getBoolean("invertPitch", false),
            showCoordinates = prefs.getBoolean("showCoordinates", true),
            showTeapotTargetControls = prefs.getBoolean("showTeapotTargetControls", true),
            hapticEnabled = prefs.getBoolean("hapticEnabled", true),
            soundEnabled = prefs.getBoolean("soundEnabled", true),
            soundEffectsEnabled = prefs.getBoolean("soundEffectsEnabled", true),
            backgroundMusicEnabled = prefs.getBoolean("backgroundMusicEnabled", true),
            masterVolume = prefs.getFloat("masterVolume", 1.0f).coerceIn(0f, 1f),
            soundEffectsVolume = prefs.getFloat("soundEffectsVolume", 1.0f).coerceIn(0f, 1f),
            backgroundMusicVolume = prefs.getFloat("backgroundMusicVolume", 0.65f).coerceIn(0f, 1f),
            skyEnvironment = skyEnv,
            defaultZoomDistance = prefs.getFloat("defaultZoomDistance", 8.0f)
        )
    }

    fun saveSettings(context: Context, settings: GameSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("characterRotationMode", settings.characterRotationMode.name)
            putString("rightScreenTouchMode", settings.rightScreenTouchMode.name)
            putFloat("cameraSensitivity", settings.cameraSensitivity)
            putFloat("zoomSensitivity", settings.zoomSensitivity)
            putBoolean("invertPitch", settings.invertPitch)
            putBoolean("showCoordinates", settings.showCoordinates)
            putBoolean("showTeapotTargetControls", settings.showTeapotTargetControls)
            putBoolean("hapticEnabled", settings.hapticEnabled)
            putBoolean("soundEnabled", settings.soundEnabled)
            putBoolean("soundEffectsEnabled", settings.soundEffectsEnabled)
            putBoolean("backgroundMusicEnabled", settings.backgroundMusicEnabled)
            putFloat("masterVolume", settings.masterVolume.coerceIn(0f, 1f))
            putFloat("soundEffectsVolume", settings.soundEffectsVolume.coerceIn(0f, 1f))
            putFloat("backgroundMusicVolume", settings.backgroundMusicVolume.coerceIn(0f, 1f))
            putString("skyEnvironment", settings.skyEnvironment.name)
            putFloat("defaultZoomDistance", settings.defaultZoomDistance)
            apply()
        }
    }
}
