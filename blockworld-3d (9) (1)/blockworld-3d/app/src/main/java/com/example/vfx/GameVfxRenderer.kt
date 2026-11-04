package com.example.vfx

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal OpenGL ES 2.0 camera-facing sprite renderer for world-attached VFX.
 *
 * Call onSurfaceCreated() from GLGameRenderer.onSurfaceCreated(), and call
 * draw(viewMatrix, projectionMatrix, deltaSeconds) on the GL thread each frame after the world
 * has been rendered. Matrices must be the same camera view/projection matrices used for the world.
 *
 * Required transparent PNGs:
 *   app/src/main/assets/vfx/attack_hit.png
 *   app/src/main/assets/vfx/jump_dust.png
 */
class GameVfxRenderer(context: Context) {
    private val appContext = context.applicationContext
    private var program = 0
    private var positionLocation = -1
    private var uvLocation = -1
    private var mvpLocation = -1
    private var samplerLocation = -1
    private var alphaLocation = -1
    private val textures = mutableMapOf<GameVfxType, Int>()
    private val active = mutableListOf<ActiveEffect>()
    private val mvp = FloatArray(16)
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * FLOATS_PER_VERTEX * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private class ActiveEffect(val request: GameVfxRequest) {
        var ageSeconds: Float = 0f
        var x: Float = request.x
        var y: Float = request.y
        var z: Float = request.z
        var velocityX: Float = request.velocityX
        var velocityY: Float = request.velocityY
        var velocityZ: Float = request.velocityZ
    }

    /** Must be called after EGL creates (or recreates) the GL context. */
    fun onSurfaceCreated() {
        // Old GL handles are invalid after context recreation; do not try to delete them here.
        textures.clear()
        active.clear()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        uvLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        alphaLocation = GLES20.glGetUniformLocation(program, "uAlpha")

        GameVfxType.values().forEach { type ->
            loadTexture(type)?.let { textures[type] = it }
        }
    }

    /**
     * Call each frame on the GL thread. dt is seconds; clamp it to avoid a large animation jump
     * after a pause. This renders world-space quads facing the camera.
     */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, deltaSeconds: Float) {
        if (program == 0 || viewMatrix.size < 16 || projectionMatrix.size < 16) return

        while (true) {
            val request = GameVfxManager.pollPending() ?: break
            active.add(ActiveEffect(request))
        }

        val dt = deltaSeconds.coerceIn(0f, 0.05f)
        active.forEach { effect ->
            effect.ageSeconds += dt
            if (effect.request.type == GameVfxType.FIREBALL) {
                effect.x += effect.velocityX * dt
                effect.y += effect.velocityY * dt
                effect.z += effect.velocityZ * dt
                effect.velocityY -= FIREBALL_GRAVITY * dt
            }
        }
        active.removeAll { it.ageSeconds >= it.request.type.lifetimeSeconds }
        if (active.isEmpty()) return

        Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, viewMatrix, 0)

        // Camera-facing basis vectors derived from Android's column-major view matrix.
        val rightX = viewMatrix[0]
        val rightY = viewMatrix[4]
        val rightZ = viewMatrix[8]
        val upX = viewMatrix[1]
        val upY = viewMatrix[5]
        val upZ = viewMatrix[9]

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Test depth against the world, but do not let transparent sprites write to the depth buffer.
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(uvLocation)

        for (effect in active) {
            val texture = textures[effect.request.type] ?: continue
            val type = effect.request.type
            val progress = (effect.ageSeconds / type.lifetimeSeconds).coerceIn(0f, 1f)
            val size = (type.startSize + (type.endSize - type.startSize) * progress) * effect.request.sizeMultiplier
            val alpha = if (type == GameVfxType.FIREBALL) {
                ((1f - progress) * 3.5f).coerceIn(0f, 1f)
            } else {
                (1f - progress) * (1f - progress * 0.15f)
            }
            val halfHeight = size * 0.5f
            val halfWidth = halfHeight * type.aspectRatio
            val x = effect.x
            val y = effect.y
            val z = effect.z

            // Interleaved: xyz position, uv. The PNG's upper edge maps to the top edge of the quad.
            val lx = rightX * halfWidth
            val ly = rightY * halfWidth
            val lz = rightZ * halfWidth
            val ux = upX * halfHeight
            val uy = upY * halfHeight
            val uz = upZ * halfHeight
            quadBuffer.clear()
            quadBuffer.put(floatArrayOf(
                x - lx - ux, y - ly - uy, z - lz - uz, 0f, 1f, // bottom-left
                x + lx - ux, y + ly - uy, z + lz - uz, 1f, 1f, // bottom-right
                x - lx + ux, y - ly + uy, z - lz + uz, 0f, 0f, // top-left
                x + lx + ux, y + ly + uy, z + lz + uz, 1f, 0f  // top-right
            ))
            quadBuffer.position(0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1f(alphaLocation, alpha)
            quadBuffer.position(0)
            GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, quadBuffer)
            quadBuffer.position(3)
            GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quadBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0)
    }

    /** Call on the GL thread if the renderer is permanently disposed. */
    fun release() {
        if (textures.isNotEmpty()) {
            val ids = textures.values.filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        }
        textures.clear()
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        active.clear()
        GameVfxManager.clearPending()
    }

    private fun loadTexture(type: GameVfxType): Int? {
        val bitmap = try {
            appContext.assets.open(type.assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Missing VFX texture ${type.assetPath}; effect will be skipped", error)
            null
        } ?: return null

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val textureId = ids[0]
        if (textureId == 0) {
            bitmap.recycle()
            Log.e(TAG, "Could not allocate texture for ${type.assetPath}")
            return null
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        return textureId
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
            GLES20.glDeleteShader(vertex)
            return 0
        }
        val linkedProgram = GLES20.glCreateProgram()
        if (linkedProgram == 0) {
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            Log.e(TAG, "glCreateProgram returned 0")
            return 0
        }
        GLES20.glAttachShader(linkedProgram, vertex)
        GLES20.glAttachShader(linkedProgram, fragment)
        GLES20.glLinkProgram(linkedProgram)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            Log.e(TAG, "VFX shader link failed: ${GLES20.glGetProgramInfoLog(linkedProgram)}")
            GLES20.glDeleteProgram(linkedProgram)
            return 0
        }
        return linkedProgram
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "VFX shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private companion object {
        private const val TAG = "GameVfxRenderer"
        private const val FLOATS_PER_VERTEX = 5
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val FIREBALL_GRAVITY = 4.2f

        private val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                color.a *= uAlpha;
                gl_FragColor = color;
            }
        """.trimIndent()
    }
}
