
package com.example.game

/**
 * Character direction behavior.
 */
enum class CharacterRotationMode {
    ROTATING_TO_MOVEMENT,
    NON_ROTATING_CAMERA_LOCKED
}

enum class SkyEnvironmentType {
    STYLIZED_BLUE_SKY,
    SUNSET_HORIZON,
    MIDNIGHT_SPACE,
    CUSTOM_HDR
}

enum class RightScreenTouchMode {
    GENSHIN_JOYSTICK,
    CAMERA_SWIPE
}

data class GameSettings(
    val characterRotationMode: CharacterRotationMode =
        CharacterRotationMode.ROTATING_TO_MOVEMENT,

    val rightScreenTouchMode: RightScreenTouchMode =
        RightScreenTouchMode.GENSHIN_JOYSTICK,

    val cameraSensitivity: Float = 1.0f,
    val zoomSensitivity: Float = 1.0f,
    val invertPitch: Boolean = false,
    val showCoordinates: Boolean = true,

    /**
     * Show selected-target controls in the furnishing drawer.
     */
    val showTeapotTargetControls: Boolean = true,

    val hapticEnabled: Boolean = true,









    // ============================================================
// OBJECT APPEARANCE
// Neutral values preserve the original material appearance.
// ============================================================

    val objectBrightness: Float = 1.0f,
    val objectSaturation: Float = 1.0f,
    val objectOpacity: Float = 1.0f,
    val objectContrast: Float = 1.0f,
    val objectColorTemperature: Float = 0.0f,

// This controls supported texture-alpha edges, not the
// silhouette of every 3D object.
    val objectTextureEdgeSoftness: Float = 0.0f,

// ============================================================
// FOG
// Defaults are based on the previously described renderer.
// ============================================================

    val fogEnabled: Boolean = true,

    val fogColorR: Float = 0.68f,
    val fogColorG: Float = 0.84f,
    val fogColorB: Float = 0.98f,

    val fogBrightness: Float = 1.0f,
    val fogOpacity: Float = 0.85f,
    val fogDensity: Float = 0.01f,

    val fogStartDistance: Float = 35.0f,
    val fogEndDistance: Float = 80.0f,

// 0 = less-soft transition, 1 = softer transition.
    val fogSoftness: Float = 0.0f,

// ============================================================
// SUNLIGHT AND LIGHTING
// ============================================================

    val sunlightEnabled: Boolean = true,

    val sunColorR: Float = 1.0f,
    val sunColorG: Float = 0.96f,
    val sunColorB: Float = 0.86f,

    val sunIntensity: Float = 1.0f,

    val sunDirectionX: Float = 0.45f,
    val sunDirectionY: Float = 0.8f,
    val sunDirectionZ: Float = 0.4f,

    val ambientLightIntensity: Float = 0.3f,

// ============================================================
// SKY AND ATMOSPHERE
// ============================================================

    val customSkyEnabled: Boolean = false,
//
    val skyColorR: Float = 0.42f,
    val skyColorG: Float = 0.65f,
    val skyColorB: Float = 0.95f,

    val skyBrightness: Float = 1.0f,
    val skySaturation: Float = 1.0f,
    val atmosphereStrength: Float = 1.0f,

// Must be connected to the actual sky shader.
    val skyTransitionSoftness: Float = 0.0f,



// ============================================================
// ENVIRONMENT COLOR GRADING
// Keep these separate from object appearance settings.
// ============================================================

    val environmentBrightness: Float = 1.0f,
    val environmentSaturation: Float = 1.0f,
    val environmentContrast: Float = 1.0f,
    val environmentColorTemperature: Float = 0.0f,


















    // Audio settings
    val soundEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true,
    val backgroundMusicEnabled: Boolean = true,
    val masterVolume: Float = 1.0f,
    val soundEffectsVolume: Float = 1.0f,
    val backgroundMusicVolume: Float = 0.65f,

    // Existing sky environment
    val skyEnvironment: SkyEnvironmentType =
        SkyEnvironmentType.STYLIZED_BLUE_SKY,

    // Fog
//    val fogEnabled: Boolean = true,
//    val fogColorR: Float = 0.68f,
//    val fogColorG: Float = 0.84f,
//    val fogColorB: Float = 0.98f,
//    val fogDensity: Float = 0.01f,
//    val fogStartDistance: Float = 35.0f,
//    val fogEndDistance: Float = 80.0f,

    // Custom sky and atmosphere
//    val customSkyEnabled: Boolean = false,
//    val skyColorR: Float = 0.42f,
//    val skyColorG: Float = 0.65f,
//    val skyColorB: Float = 0.95f,
//    val atmosphereStrength: Float = 1.0f,

    // Sun / directional lighting
//    val sunColorR: Float = 1.0f,
//    val sunColorG: Float = 0.96f,
//    val sunColorB: Float = 0.86f,
//    val sunIntensity: Float = 1.0f,
//    val sunDirectionX: Float = -0.4f,
//    val sunDirectionY: Float = 0.8f,
//    val sunDirectionZ: Float = -0.3f,
//    val ambientLightIntensity: Float = 0.3f,

    // Color grading; neutral values preserve the original appearance
    val colorTemperature: Float = 0.0f,
    val colorSaturation: Float = 1.0f,
    val colorBrightness: Float = 1.0f,
    val colorContrast: Float = 1.0f,


    // Existing zoom settings
    val minZoomDistance: Float = 3.0f,
    val maxZoomDistance: Float = 25.0f,
    val defaultZoomDistance: Float = 3.0f



): java.io.Serializable













































//package com.example.game
//
///**
// * Character Direction Modes (Requirement 5):
// * - ROTATING_TO_MOVEMENT: Character faces the direction they are walking (Roblox default)
// * - NON_ROTATING_CAMERA_LOCKED: Character always faces the camera forward direction, strafing left/right
// */
//enum class CharacterRotationMode {
//    ROTATING_TO_MOVEMENT,
//    NON_ROTATING_CAMERA_LOCKED
//}
//
//enum class SkyEnvironmentType {
//    STYLIZED_BLUE_SKY,
//    SUNSET_HORIZON,
//    MIDNIGHT_SPACE,
//    CUSTOM_HDR
//}
//
//enum class RightScreenTouchMode {
//    GENSHIN_JOYSTICK,   // Finger drag on right side screen moves dynamic Genshin Impact joystick
//    CAMERA_SWIPE        // Finger drag on right side screen pans camera
//}
//
//data class GameSettings(
//    val characterRotationMode: CharacterRotationMode = CharacterRotationMode.ROTATING_TO_MOVEMENT,
//    val rightScreenTouchMode: RightScreenTouchMode = RightScreenTouchMode.GENSHIN_JOYSTICK,
//    val cameraSensitivity: Float = 1.0f,
//    val zoomSensitivity: Float = 1.0f,
//    val invertPitch: Boolean = false,
//    val showCoordinates: Boolean = true,
//    /** Show selected-target ribbon/chips/subcategory controls in the furnishing drawer. Category tabs and model cards always remain visible. */
//    val showTeapotTargetControls: Boolean = true,
//    val hapticEnabled: Boolean = true,
//    /** Master audio switch; retained for compatibility with older saved settings. */
//    val soundEnabled: Boolean = true,
//    val soundEffectsEnabled: Boolean = true,
//    val backgroundMusicEnabled: Boolean = true,
//    val masterVolume: Float = 1.0f,
//    val soundEffectsVolume: Float = 1.0f,
//    val backgroundMusicVolume: Float = 0.65f,
//    val skyEnvironment: SkyEnvironmentType = SkyEnvironmentType.STYLIZED_BLUE_SKY,
//    val minZoomDistance: Float = 3.0f,
//    val maxZoomDistance: Float = 25.0f,
//    val defaultZoomDistance: Float = 8.0f
//)
