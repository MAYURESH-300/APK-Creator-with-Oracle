package com.example.environment

import android.content.Context

object EnvironmentStorage {

    private const val PREFS = "blockworld_environment"

    fun load(context: Context): EnvironmentSettings {
        val p = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val d = EnvironmentSettings()

        return EnvironmentSettings(
            fogEnabled = p.getBoolean("fogEnabled", d.fogEnabled),
            fogColorR = p.getFloat("fogColorR", d.fogColorR),
            fogColorG = p.getFloat("fogColorG", d.fogColorG),
            fogColorB = p.getFloat("fogColorB", d.fogColorB),
            fogDensity = p.getFloat("fogDensity", d.fogDensity),
            fogStart = p.getFloat("fogStart", d.fogStart),
            fogEnd = p.getFloat("fogEnd", d.fogEnd),

            skyOverrideEnabled = p.getBoolean(
                "skyOverrideEnabled",
                d.skyOverrideEnabled
            ),
            skyColorR = p.getFloat("skyColorR", d.skyColorR),
            skyColorG = p.getFloat("skyColorG", d.skyColorG),
            skyColorB = p.getFloat("skyColorB", d.skyColorB),
            atmosphere = p.getFloat("atmosphere", d.atmosphere),

            sunLightingEnabled = p.getBoolean(
                "sunLightingEnabled",
                d.sunLightingEnabled
            ),
            sunColorR = p.getFloat("sunColorR", d.sunColorR),
            sunColorG = p.getFloat("sunColorG", d.sunColorG),
            sunColorB = p.getFloat("sunColorB", d.sunColorB),
            sunIntensity = p.getFloat("sunIntensity", d.sunIntensity),
            sunDirectionX = p.getFloat("sunDirectionX", d.sunDirectionX),
            sunDirectionY = p.getFloat("sunDirectionY", d.sunDirectionY),
            sunDirectionZ = p.getFloat("sunDirectionZ", d.sunDirectionZ),
            ambientIntensity = p.getFloat(
                "ambientIntensity",
                d.ambientIntensity
            ),

            temperature = p.getFloat("temperature", d.temperature),
            saturation = p.getFloat("saturation", d.saturation),
            brightness = p.getFloat("brightness", d.brightness),
            contrast = p.getFloat("contrast", d.contrast)
        ).clamped()
    }

    fun save(context: Context, s: EnvironmentSettings) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean("fogEnabled", s.fogEnabled)
            .putFloat("fogColorR", s.fogColorR)
            .putFloat("fogColorG", s.fogColorG)
            .putFloat("fogColorB", s.fogColorB)
            .putFloat("fogDensity", s.fogDensity)
            .putFloat("fogStart", s.fogStart)
            .putFloat("fogEnd", s.fogEnd)

            .putBoolean("skyOverrideEnabled", s.skyOverrideEnabled)
            .putFloat("skyColorR", s.skyColorR)
            .putFloat("skyColorG", s.skyColorG)
            .putFloat("skyColorB", s.skyColorB)
            .putFloat("atmosphere", s.atmosphere)

            .putBoolean("sunLightingEnabled", s.sunLightingEnabled)
            .putFloat("sunColorR", s.sunColorR)
            .putFloat("sunColorG", s.sunColorG)
            .putFloat("sunColorB", s.sunColorB)
            .putFloat("sunIntensity", s.sunIntensity)
            .putFloat("sunDirectionX", s.sunDirectionX)
            .putFloat("sunDirectionY", s.sunDirectionY)
            .putFloat("sunDirectionZ", s.sunDirectionZ)
            .putFloat("ambientIntensity", s.ambientIntensity)

            .putFloat("temperature", s.temperature)
            .putFloat("saturation", s.saturation)
            .putFloat("brightness", s.brightness)
            .putFloat("contrast", s.contrast)
            .apply()
    }
}