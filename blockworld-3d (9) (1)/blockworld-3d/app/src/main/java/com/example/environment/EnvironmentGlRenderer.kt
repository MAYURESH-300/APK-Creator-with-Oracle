package com.example.environment

import android.opengl.GLES20

object EnvironmentGlRenderer {

    private val uniformCache =
        mutableMapOf<Int, MutableMap<String, Int>>()

    private fun location(
        program: Int,
        name: String
    ): Int {
        val programCache = uniformCache.getOrPut(program) {
            mutableMapOf()
        }

        return programCache.getOrPut(name) {
            GLES20.glGetUniformLocation(program, name)
        }
    }

    private fun setFloat(
        program: Int,
        name: String,
        value: Float
    ) {
        val loc = location(program, name)

        if (loc >= 0) {
            GLES20.glUniform1f(loc, value)
        }
    }

    private fun setInt(
        program: Int,
        name: String,
        value: Int
    ) {
        val loc = location(program, name)

        if (loc >= 0) {
            GLES20.glUniform1i(loc, value)
        }
    }

    private fun setVec3(
        program: Int,
        name: String,
        x: Float,
        y: Float,
        z: Float
    ) {
        val loc = location(program, name)

        if (loc >= 0) {
            GLES20.glUniform3f(loc, x, y, z)
        }
    }

    /**
     * Call on the GL thread before glClear().
     *
     * Pass the clear-color values your renderer used
     * before introducing the environment system.
     */
    fun applyClearColor(
        settings: EnvironmentSettings,
        originalR: Float,
        originalG: Float,
        originalB: Float
    ) {
        if (settings.skyOverrideEnabled) {
            GLES20.glClearColor(
                settings.skyColorR,
                settings.skyColorG,
                settings.skyColorB,
                1f
            )
        } else {
            GLES20.glClearColor(
                originalR,
                originalG,
                originalB,
                1f
            )
        }
    }

    /**
     * Call after GLES20.glUseProgram(program).
     *
     * cameraPosition must contain world-space X, Y and Z.
     * Pass null only if the shader doesn't use camera position.
     */
    fun applyUniforms(
        program: Int,
        settings: EnvironmentSettings,
        cameraPosition: FloatArray? = null
    ) {
        setInt(
            program,
            "uFogEnabled",
            if (settings.fogEnabled) 1 else 0
        )

        setVec3(
            program,
            "uFogColor",
            settings.fogColorR,
            settings.fogColorG,
            settings.fogColorB
        )

        setFloat(program, "uFogDensity", settings.fogDensity)
        setFloat(program, "uFogStart", settings.fogStart)
        setFloat(program, "uFogEnd", settings.fogEnd)

        setVec3(
            program,
            "uSkyColor",
            settings.skyColorR,
            settings.skyColorG,
            settings.skyColorB
        )

        setFloat(program, "uAtmosphere", settings.atmosphere)

        setInt(
            program,
            "uSunLightingEnabled",
            if (settings.sunLightingEnabled) 1 else 0
        )

        setVec3(
            program,
            "uSunColor",
            settings.sunColorR,
            settings.sunColorG,
            settings.sunColorB
        )

        setFloat(program, "uSunIntensity", settings.sunIntensity)
        setFloat(
            program,
            "uAmbientIntensity",
            settings.ambientIntensity
        )

        setVec3(
            program,
            "uSunDirection",
            settings.sunDirectionX,
            settings.sunDirectionY,
            settings.sunDirectionZ
        )

        setFloat(program, "uTemperature", settings.temperature)
        setFloat(program, "uSaturation", settings.saturation)
        setFloat(program, "uBrightness", settings.brightness)
        setFloat(program, "uContrast", settings.contrast)

        if (cameraPosition != null && cameraPosition.size >= 3) {
            setVec3(
                program,
                "uCameraPosition",
                cameraPosition[0],
                cameraPosition[1],
                cameraPosition[2]
            )
        }
    }
}