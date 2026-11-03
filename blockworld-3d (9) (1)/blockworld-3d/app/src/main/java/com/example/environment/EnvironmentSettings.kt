package com.example.environment

data class EnvironmentSettings(

    // Fog
    val fogEnabled: Boolean = false,
    val fogColorR: Float = 0.65f,
    val fogColorG: Float = 0.72f,
    val fogColorB: Float = 0.80f,
    val fogDensity: Float = 0.015f,
    val fogStart: Float = 10f,
    val fogEnd: Float = 80f,

    // Sky
    val skyOverrideEnabled: Boolean = false,
    val skyColorR: Float = 0.42f,
    val skyColorG: Float = 0.65f,
    val skyColorB: Float = 0.95f,
    val atmosphere: Float = 0f,

    // Sun and lighting
    val sunLightingEnabled: Boolean = false,
    val sunColorR: Float = 1f,
    val sunColorG: Float = 0.93f,
    val sunColorB: Float = 0.78f,
    val sunIntensity: Float = 1f,
    val sunDirectionX: Float = -0.4f,
    val sunDirectionY: Float = -1f,
    val sunDirectionZ: Float = -0.2f,
    val ambientIntensity: Float = 0.35f,

    // Color grading
    val temperature: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val contrast: Float = 1f
) {
    fun clamped(): EnvironmentSettings {
        val safeFogStart = fogStart.coerceIn(0f, 999f)

        return copy(
            fogColorR = fogColorR.coerceIn(0f, 1f),
            fogColorG = fogColorG.coerceIn(0f, 1f),
            fogColorB = fogColorB.coerceIn(0f, 1f),
            fogDensity = fogDensity.coerceIn(0f, 0.2f),
            fogStart = safeFogStart,
            fogEnd = fogEnd.coerceIn(safeFogStart + 0.1f, 1000f),

            skyColorR = skyColorR.coerceIn(0f, 1f),
            skyColorG = skyColorG.coerceIn(0f, 1f),
            skyColorB = skyColorB.coerceIn(0f, 1f),
            atmosphere = atmosphere.coerceIn(0f, 1f),

            sunColorR = sunColorR.coerceIn(0f, 1f),
            sunColorG = sunColorG.coerceIn(0f, 1f),
            sunColorB = sunColorB.coerceIn(0f, 1f),
            sunIntensity = sunIntensity.coerceIn(0f, 4f),
            sunDirectionX = sunDirectionX.coerceIn(-1f, 1f),
            sunDirectionY = sunDirectionY.coerceIn(-1f, 1f),
            sunDirectionZ = sunDirectionZ.coerceIn(-1f, 1f),
            ambientIntensity = ambientIntensity.coerceIn(0f, 2f),

            temperature = temperature.coerceIn(-1f, 1f),
            saturation = saturation.coerceIn(0f, 2f),
            brightness = brightness.coerceIn(0f, 2f),
            contrast = contrast.coerceIn(0f, 2f)
        )
    }
}