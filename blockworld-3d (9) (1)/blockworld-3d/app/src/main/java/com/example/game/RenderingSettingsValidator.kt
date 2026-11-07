package com.example.game

import kotlin.math.sqrt

private fun Float.safeRange(
    minimum: Float,
    maximum: Float,
    fallback: Float
): Float {
    return if (isFinite()) {
        coerceIn(minimum, maximum)
    } else {
        fallback
    }
}

fun GameSettings.validatedForRendering(): GameSettings {

    val start = fogStartDistance.safeRange(
        minimum = 0.0f,
        maximum = 99999.0f,
        fallback = 35.0f
    )

    val requestedEnd = fogEndDistance.safeRange(
        minimum = 1.0f,
        maximum = 100000.0f,
        fallback = 80.0f
    )

    val correctedEnd = maxOf(start + 1.0f, requestedEnd)

    return copy(

        // Object appearance
        objectBrightness = objectBrightness.safeRange(
            0.0f, 3.0f, 1.0f
        ),

        objectSaturation = objectSaturation.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        objectOpacity = objectOpacity.safeRange(
            0.0f, 1.0f, 1.0f
        ),

        objectContrast = objectContrast.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        objectColorTemperature = objectColorTemperature.safeRange(
            -1.0f, 1.0f, 0.0f
        ),

        objectTextureEdgeSoftness = objectTextureEdgeSoftness.safeRange(
            0.0f, 1.0f, 0.0f
        ),

        // Fog
        fogColorR = fogColorR.safeRange(0.0f, 1.0f, 0.68f),
        fogColorG = fogColorG.safeRange(0.0f, 1.0f, 0.84f),
        fogColorB = fogColorB.safeRange(0.0f, 1.0f, 0.98f),

        fogBrightness = fogBrightness.safeRange(
            0.0f, 3.0f, 1.0f
        ),

        fogOpacity = fogOpacity.safeRange(
            0.0f, 1.0f, 0.85f
        ),

        fogDensity = fogDensity.safeRange(
            0.0f, 0.1f, 0.01f
        ),

        fogStartDistance = start,
        fogEndDistance = correctedEnd,

        fogSoftness = fogSoftness.safeRange(
            0.0f, 1.0f, 0.0f
        ),

        // Sunlight
        sunColorR = sunColorR.safeRange(0.0f, 1.0f, 1.0f),
        sunColorG = sunColorG.safeRange(0.0f, 1.0f, 0.96f),
        sunColorB = sunColorB.safeRange(0.0f, 1.0f, 0.86f),

        sunIntensity = sunIntensity.safeRange(
            0.0f, 4.0f, 1.0f
        ),

        sunDirectionX = sunDirectionX.safeRange(
            -1.0f, 1.0f, 0.45f
        ),

        sunDirectionY = sunDirectionY.safeRange(
            -1.0f, 1.0f, 0.8f
        ),

        sunDirectionZ = sunDirectionZ.safeRange(
            -1.0f, 1.0f, 0.4f
        ),

        ambientLightIntensity = ambientLightIntensity.safeRange(
            0.0f, 2.0f, 0.3f
        ),

        // Sky
        skyColorR = skyColorR.safeRange(0.0f, 1.0f, 0.42f),
        skyColorG = skyColorG.safeRange(0.0f, 1.0f, 0.65f),
        skyColorB = skyColorB.safeRange(0.0f, 1.0f, 0.95f),

        skyBrightness = skyBrightness.safeRange(
            0.0f, 3.0f, 1.0f
        ),

        skySaturation = skySaturation.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        atmosphereStrength = atmosphereStrength.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        skyTransitionSoftness = skyTransitionSoftness.safeRange(
            0.0f, 1.0f, 0.0f
        ),

        // Environment grading
        environmentBrightness = environmentBrightness.safeRange(
            0.0f, 3.0f, 1.0f
        ),

        environmentSaturation = environmentSaturation.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        environmentContrast = environmentContrast.safeRange(
            0.0f, 2.0f, 1.0f
        ),

        environmentColorTemperature =
            environmentColorTemperature.safeRange(
                -1.0f, 1.0f, 0.0f
            )
    )
}

/**
 * Returns a normalized light direction.
 * The fallback preserves the renderer's previous light direction.
 */
fun GameSettings.normalizedSunDirection(): FloatArray {

    val settings = validatedForRendering()

    val x = settings.sunDirectionX
    val y = settings.sunDirectionY
    val z = settings.sunDirectionZ

    val length = sqrt(x * x + y * y + z * z)

    if (!length.isFinite() || length < 0.0001f) {
        return floatArrayOf(0.45f, 0.8f, 0.4f)
    }

    return floatArrayOf(
        x / length,
        y / length,
        z / length
    )
}