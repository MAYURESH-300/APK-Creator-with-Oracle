package com.example.environment

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import java.util.Locale

/**
 * Visual controls for the 3D rendering pipeline.
 *
 * Keep new visual controls here so that the UI, renderer and future
 * effects all use one settings model.
 */
data class VisualEnvironmentSettings(
    // 0.0 = black, 1.0 = normal, 2.0 = double brightness
    val objectBrightness: Float = 1.0f,

    // Sky tint.
    val skyColorHex: String = "#FFFFFF",
    val skyTintStrength: Float = 0.0f,
    val skyBrightness: Float = 1.0f,

    // Distance fog.
    val fogEnabled: Boolean = false,
    val fogColorHex: String = "#AFCFFF",
    val fogDensity: Float = 0.035f,

    // Additive fog scattering. This is not full-screen bloom.
    val fogGlow: Float = 0.25f
)

object EnvironmentVisualSettingsStore {

    private const val PREFS_NAME = "blockworld_visual_environment"

    private const val KEY_OBJECT_BRIGHTNESS = "object_brightness"

    private const val KEY_SKY_COLOR = "sky_color"
    private const val KEY_SKY_TINT_STRENGTH = "sky_tint_strength"
    private const val KEY_SKY_BRIGHTNESS = "sky_brightness"

    private const val KEY_FOG_ENABLED = "fog_enabled"
    private const val KEY_FOG_COLOR = "fog_color"
    private const val KEY_FOG_DENSITY = "fog_density"
    private const val KEY_FOG_GLOW = "fog_glow"

    private val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")

    @Volatile
    private var settings = VisualEnvironmentSettings()

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var initialized = false

    /**
     * Call when the application starts, before the game renderer begins
     * drawing. Also called automatically by update().
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return

        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val defaults = VisualEnvironmentSettings()

        settings = VisualEnvironmentSettings(
            objectBrightness = prefs.getFloat(
                KEY_OBJECT_BRIGHTNESS,
                defaults.objectBrightness
            ),

            skyColorHex = normalizeHex(
                prefs.getString(KEY_SKY_COLOR, defaults.skyColorHex),
                defaults.skyColorHex
            ),

            skyTintStrength = prefs.getFloat(
                KEY_SKY_TINT_STRENGTH,
                defaults.skyTintStrength
            ),

            skyBrightness = prefs.getFloat(
                KEY_SKY_BRIGHTNESS,
                defaults.skyBrightness
            ),

            fogEnabled = prefs.getBoolean(
                KEY_FOG_ENABLED,
                defaults.fogEnabled
            ),

            fogColorHex = normalizeHex(
                prefs.getString(KEY_FOG_COLOR, defaults.fogColorHex),
                defaults.fogColorHex
            ),

            fogDensity = prefs.getFloat(
                KEY_FOG_DENSITY,
                defaults.fogDensity
            ),

            fogGlow = prefs.getFloat(
                KEY_FOG_GLOW,
                defaults.fogGlow
            )
        ).sanitized()

        preferences = prefs
        initialized = true
    }

    /**
     * Safe, read-only snapshot for the rendering thread.
     */
    fun snapshot(): VisualEnvironmentSettings = settings

    /**
     * Update settings and persist them immediately.
     *
     * For example:
     * update(context) { it.copy(objectBrightness = 1.5f) }
     */
    @Synchronized
    fun update(
        context: Context,
        transform: (VisualEnvironmentSettings) -> VisualEnvironmentSettings
    ): VisualEnvironmentSettings {

        initialize(context)

        val updated = transform(settings).sanitized()
        settings = updated

        preferences?.edit()?.let { editor ->
            editor.putFloat(
                KEY_OBJECT_BRIGHTNESS,
                updated.objectBrightness
            )

            editor.putString(KEY_SKY_COLOR, updated.skyColorHex)
            editor.putFloat(
                KEY_SKY_TINT_STRENGTH,
                updated.skyTintStrength
            )
            editor.putFloat(
                KEY_SKY_BRIGHTNESS,
                updated.skyBrightness
            )

            editor.putBoolean(
                KEY_FOG_ENABLED,
                updated.fogEnabled
            )
            editor.putString(KEY_FOG_COLOR, updated.fogColorHex)
            editor.putFloat(
                KEY_FOG_DENSITY,
                updated.fogDensity
            )
            editor.putFloat(
                KEY_FOG_GLOW,
                updated.fogGlow
            )

            editor.apply()
        }

        return updated
    }

    /**
     * Converts #RRGGBB into Android's packed ARGB color integer.
     */
    fun colorInt(hex: String): Int {
        return try {
            Color.parseColor(normalizeHex(hex, "#FFFFFF"))
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }
    }

    private fun normalizeHex(
        value: String?,
        fallback: String
    ): String {
        val candidate = value
            ?.trim()
            ?.let {
                if (it.startsWith("#")) it else "#$it"
            }
            ?: fallback

        return if (hexPattern.matches(candidate)) {
            candidate.uppercase(Locale.US)
        } else {
            fallback
        }
    }

    private fun VisualEnvironmentSettings.sanitized() = copy(
        objectBrightness = objectBrightness.coerceIn(0.0f, 2.0f),

        skyColorHex = normalizeHex(skyColorHex, "#FFFFFF"),
        skyTintStrength = skyTintStrength.coerceIn(0.0f, 1.0f),
        skyBrightness = skyBrightness.coerceIn(0.0f, 2.0f),

        fogColorHex = normalizeHex(fogColorHex, "#AFCFFF"),
        fogDensity = fogDensity.coerceIn(0.0f, 0.08f),
        fogGlow = fogGlow.coerceIn(0.0f, 2.0f)
    )
}