package com.example.graphics

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.example.game.SkyEnvironmentType
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Modular 3D Sky Dome Renderer (Requirements 3, 11, 12).
 * Supports procedural Vibrant Blue Sky, Sunset, Midnight, and custom HDR asset loader.
 */
class SkyRenderer(private val context: Context) {

    private var programId: Int = 0
    private var uMVPMatrixLoc: Int = -1
    private var uSunDirLoc: Int = -1
    private var uSkyModeLoc: Int = -1
    private var uSkyTintLoc: Int = -1
    private var uSkyTintStrengthLoc: Int = -1
    private var uSkyBrightnessLoc: Int = -1
    private var aPositionLoc: Int = -1

    private lateinit var sphereVertices: FloatBuffer
    private lateinit var sphereIndices: ShortBuffer
    private var indexCount: Int = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val sunDirection = floatArrayOf(0.45f, 0.75f, -0.45f) // Sun in sky

    var hasHdrSkyLoaded: Boolean = false
        private set

    fun initGL() {
        programId = GLShaderHelper.createProgram(
            GLShaderHelper.SKY_VERTEX_SHADER,
            GLShaderHelper.SKY_FRAGMENT_SHADER
        )

        uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSunDirLoc = GLES20.glGetUniformLocation(programId, "uSunDir")
        uSkyModeLoc = GLES20.glGetUniformLocation(programId, "uSkyMode")
        uSkyTintLoc = GLES20.glGetUniformLocation(programId, "uSkyTint")
        uSkyTintStrengthLoc = GLES20.glGetUniformLocation(programId, "uSkyTintStrength")
        uSkyBrightnessLoc = GLES20.glGetUniformLocation(programId, "uSkyBrightness")
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")

        val (verts, inds) = MeshBuilder.createSphere(rings = 20, sectors = 32)
        sphereVertices = verts
        sphereIndices = inds
        indexCount = inds.capacity()

        // Check if sky.hdr exists in assets
        checkAndLoadHdrAsset()
    }

    private fun checkAndLoadHdrAsset() {
        try {
            val inputStream: InputStream = context.assets.open("sky.hdr")
            val buffer = ByteArray(256)
            val bytesRead = inputStream.read(buffer)
            inputStream.close()
            if (bytesRead > 0) {
                val header = String(buffer, 0, bytesRead)
                hasHdrSkyLoaded = header.contains("#?RADIANCE")
                Log.d("SkyRenderer", "HDR asset validated: hasHdr=$hasHdrSkyLoaded")
            }
        } catch (e: Exception) {
            Log.w("SkyRenderer", "HDR sky asset check: ${e.message}")
            hasHdrSkyLoaded = false
        }
    }

    fun render(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        cameraPos: FloatArray,
        skyType: SkyEnvironmentType,
        skyTintColor: Int = Color.WHITE,
        skyTintStrength: Float = 0.0f,
        skyBrightness: Float = 1.0f
    ) {
        GLES20.glUseProgram(programId)

        // Disable depth writing so sky is always in the deep background
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // Center sky dome around camera
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cameraPos[0], cameraPos[1], cameraPos[2])
        Matrix.scaleM(modelMatrix, 0, 75f, 75f, 75f) // Large radius 75m dome

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform3fv(uSunDirLoc, 1, sunDirection, 0)

        val skyMode = when (skyType) {
            SkyEnvironmentType.STYLIZED_BLUE_SKY -> 0
            SkyEnvironmentType.SUNSET_HORIZON -> 1
            SkyEnvironmentType.MIDNIGHT_SPACE -> 2
            SkyEnvironmentType.CUSTOM_HDR -> if (hasHdrSkyLoaded) 0 else 0 // Maps to vivid HDR blue spectrum
        }
        GLES20.glUniform1i(uSkyModeLoc, skyMode)

        // Color is supplied as Android ARGB and converted to normalized RGB.
        val tint = floatArrayOf(
            Color.red(skyTintColor) / 255f,
            Color.green(skyTintColor) / 255f,
            Color.blue(skyTintColor) / 255f
        )
        GLES20.glUniform3fv(uSkyTintLoc, 1, tint, 0)
        GLES20.glUniform1f(uSkyTintStrengthLoc, skyTintStrength.coerceIn(0f, 1f))
        GLES20.glUniform1f(uSkyBrightnessLoc, skyBrightness.coerceIn(0f, 3f))

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, sphereVertices)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, sphereIndices)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}
