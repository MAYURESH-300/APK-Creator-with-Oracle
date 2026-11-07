package com.example.teapot

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.example.graphics.MeshBuilder
import com.example.model.Vector3
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.IdentityHashMap

/** Shared OpenGL texture cache. IDs are cleared when Android recreates the EGL context. */
private object ImportedTextureCache {
    private val ids = IdentityHashMap<Bitmap, Int>()
    private val mipmapIds = HashSet<Int>()

    @Synchronized
    fun upload(bitmap: Bitmap): Int {
        ids[bitmap]?.let { return it }
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        if (names[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val err = GLES20.glGetError()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (err != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(1, names, 0)
            Log.w("ImportedTexture", "Upload failed (GL error 0x${err.toString(16)}); using material color")
            return 0
        }
        ids[bitmap] = names[0]
        return names[0]
    }

    @Synchronized
    fun ensureMipmaps(textureId: Int) {
        if (textureId != 0 && mipmapIds.add(textureId)) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
    }

    @Synchronized
    fun invalidateAll() {
        ids.clear()
        mipmapIds.clear()
    }
}

/** One compact, GLES2-compatible indexed batch belonging to a GLB primitive/material. */
class Custom3DMeshPart(
    val vertexBuffer: FloatBuffer,
    val normalBuffer: FloatBuffer,
    val indexBuffer: ShortBuffer?,
    /** Draw count: index count when indexBuffer is present, otherwise vertex count. */
    val vertexCount: Int,
    val texCoordBuffer: FloatBuffer? = null,
    val textureBitmap: Bitmap? = null,
    val baseColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f),
    val restPositions: FloatArray = FloatArray(0),
    val restNormals: FloatArray = FloatArray(0),
    val skinningData: GltfSkinningPartData? = null,
    val doubleSided: Boolean = false,
    val samplerWrapS: Int = 10497,
    val samplerWrapT: Int = 10497,
    val samplerMinFilter: Int = 9987,
    val samplerMagFilter: Int = 9729
) {
    fun writeAnimatedBuffers(positions: FloatArray, normals: FloatArray) {
        if (positions.size == vertexBuffer.limit()) {
            vertexBuffer.position(0)
            vertexBuffer.put(positions)
            vertexBuffer.position(0)
        }
        if (normals.size == normalBuffer.limit()) {
            normalBuffer.position(0)
            normalBuffer.put(normals)
            normalBuffer.position(0)
        }
    }

    fun restoreBindPose() {
        if (restPositions.size == vertexBuffer.limit()) {
            vertexBuffer.position(0); vertexBuffer.put(restPositions); vertexBuffer.position(0)
        }
        if (restNormals.size == normalBuffer.limit()) {
            normalBuffer.position(0); normalBuffer.put(restNormals); normalBuffer.position(0)
        }
    }

    private fun applySamplerParameters(textureId: Int, bitmap: Bitmap, wrapS: Int, wrapT: Int, minFilter: Int, magFilter: Int) {
        val hasNpotExtension = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().contains("GL_OES_texture_npot")
        val isPowerOfTwo = (bitmap.width and (bitmap.width - 1)) == 0 && (bitmap.height and (bitmap.height - 1)) == 0
        val repeatAllowed = isPowerOfTwo || hasNpotExtension
        fun wrap(value: Int) = when (value) {
            33071 -> GLES20.GL_CLAMP_TO_EDGE
            33648 -> if (repeatAllowed) GLES20.GL_MIRRORED_REPEAT else GLES20.GL_CLAMP_TO_EDGE
            else -> if (repeatAllowed) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        }
        val min = when (minFilter) {
            9728 -> GLES20.GL_NEAREST
            9729 -> GLES20.GL_LINEAR
            9984 -> if (repeatAllowed) GLES20.GL_NEAREST_MIPMAP_NEAREST else GLES20.GL_NEAREST
            9985 -> if (repeatAllowed) GLES20.GL_LINEAR_MIPMAP_NEAREST else GLES20.GL_LINEAR
            9986 -> if (repeatAllowed) GLES20.GL_NEAREST_MIPMAP_LINEAR else GLES20.GL_NEAREST
            9987 -> if (repeatAllowed) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR
            else -> GLES20.GL_LINEAR
        }
        val mag = if (magFilter == 9728) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap(wrapS))
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap(wrapT))
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, min)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mag)
        if (repeatAllowed && minFilter in 9984..9987) ImportedTextureCache.ensureMipmaps(textureId)
    }

    fun render(
        aPositionLoc: Int,
        aNormalLoc: Int,
        uColorLoc: Int,
        aTexCoordLoc: Int,
        uUseTextureLoc: Int,
        uTextureLoc: Int,
        tintColor: FloatArray? = null
    ) {
        vertexBuffer.position(0)
        normalBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)

        val textureId = if (textureBitmap != null && texCoordBuffer != null && aTexCoordLoc >= 0) {
            ImportedTextureCache.upload(textureBitmap)
        } else 0
        if (textureId != 0) {
            val uv = texCoordBuffer!!
            uv.position(0)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)
            applySamplerParameters(textureId, textureBitmap!!, samplerWrapS, samplerWrapT, samplerMinFilter, samplerMagFilter)
            GLES20.glUniform1i(uUseTextureLoc, 1)
            // The source base-colour texture is not tinted with the target object's gameplay color.
            GLES20.glUniform4fv(uColorLoc, 1, tintColor ?: baseColor, 0)
        } else {
            if (aTexCoordLoc >= 0) GLES20.glDisableVertexAttribArray(aTexCoordLoc)
            GLES20.glUniform1i(uUseTextureLoc, 0)
            GLES20.glUniform4fv(uColorLoc, 1, tintColor ?: baseColor, 0)
        }

        if (doubleSided) GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (indexBuffer != null) {
            indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, vertexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        }
        if (doubleSided) GLES20.glEnable(GLES20.GL_CULL_FACE)
        if (textureId != 0) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (aTexCoordLoc >= 0) GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glUniform1i(uUseTextureLoc, 0)
    }
}

/** Static or animated model loaded from GLB/OBJ/FBX. Existing procedural models use the root buffers. */
data class Custom3DMesh(
    val id: String,
    val name: String,
    val category: String,
    val vertexBuffer: FloatBuffer,
    val normalBuffer: FloatBuffer,
    val indexBuffer: ShortBuffer? = null,
    val vertexCount: Int,
    val defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f),
    val boundsSize: Vector3 = Vector3(1f, 1f, 1f),
    val loadCost: Int = 20,
    val count: Int = 1,
    val isUserImported: Boolean = false,
    val sourcePath: String? = null,
    val renderParts: List<Custom3DMeshPart> = emptyList(),
    val animationRuntime: GltfAnimationRuntime? = null,
    /** True when a dense source GLB was sampled down to fit the renderer's mobile triangle budget. */
    val wasReducedForMobile: Boolean = false,
    /** Non-fatal import note to display in the existing UI. */
    val importWarning: String? = null,
    /** True when this gallery entry is only metadata + a small preview cube, not render geometry. */
    val isLazyLoaded: Boolean = false,
    /** Bounded triangle sample retained for the gallery after full vertex/texture data is released. */
    val previewTriangles: FloatArray? = null
) {
    val animationNames: List<String> get() = animationRuntime?.animationNames.orEmpty()
    val activeAnimationName: String? get() = animationRuntime?.selectedAnimationName
    val isAnimationEnabled: Boolean get() = animationRuntime?.isEnabled == true
    val isAutoAnimationEnabled: Boolean get() = animationRuntime?.autoMode == true
    val supportsSkeletalAnimation: Boolean get() = animationRuntime?.supportsSkeletalAnimation == true

    fun setAnimationEnabled(enabled: Boolean) = animationRuntime?.setEnabled(enabled)
    fun setAutoAnimation(enabled: Boolean) = animationRuntime?.setAutoMode(enabled)
    fun selectAnimation(index: Int) = animationRuntime?.selectAnimation(index)
    fun selectAnimation(name: String): Boolean = animationRuntime?.selectAnimation(name) ?: false
    fun nextAnimation() = animationRuntime?.nextAnimation()

    fun updateAnimation(deltaSeconds: Float, frameToken: Long, playerAction: String? = null, horizontalSpeed: Float = 0f) {
        animationRuntime?.update(renderParts, deltaSeconds, frameToken, playerAction, horizontalSpeed)
    }

    fun invalidateGlTextures() = ImportedTextureCache.invalidateAll()

    /** Backward-compatible name used by the existing GLGameRenderer. */
    fun invalidateGlTexture() = invalidateGlTextures()

    /**
     * Returns a bounded sample of triangle positions for the Compose card preview.
     * This reads indexed batches correctly and never copies the entire high-poly mesh.
     */
    fun buildPreviewTriangles(maxTriangles: Int = 96): FloatArray {
        val triangleBudget = maxTriangles.coerceAtLeast(1)
        previewTriangles?.let { retainedPreview ->
            val maxFloats = triangleBudget * 9
            return if (retainedPreview.size > maxFloats) retainedPreview.copyOf(maxFloats)
                else retainedPreview.copyOf()
        }
        val parts = if (renderParts.isNotEmpty()) {
            renderParts.map { PreviewPart(it.vertexBuffer, it.indexBuffer, it.vertexCount) }
        } else {
            listOf(PreviewPart(vertexBuffer, indexBuffer, vertexCount))
        }
        val partTriangleCounts = parts.map { part ->
            val drawElements = if (part.indices != null) minOf(part.drawCount, part.indices.limit())
                else minOf(part.drawCount, part.positions.limit() / 3)
            drawElements.coerceAtLeast(0) / 3
        }
        val totalTriangles = partTriangleCounts.sum()
        if (totalTriangles <= 0) return FloatArray(0)

        val output = FloatArray(minOf(triangleBudget, totalTriangles) * 9)
        var outputTriangleCount = 0
        var remainingSourceTriangles = totalTriangles
        var remainingBudget = minOf(triangleBudget, totalTriangles)

        for (partIndex in parts.indices) {
            if (remainingBudget <= 0) break
            val part = parts[partIndex]
            val sourceTriangleCount = partTriangleCounts[partIndex]
            if (sourceTriangleCount <= 0) continue

            val partBudget = if (sourceTriangleCount == remainingSourceTriangles) {
                remainingBudget
            } else {
                ((remainingBudget.toDouble() * sourceTriangleCount.toDouble()) /
                    remainingSourceTriangles.toDouble()).toInt().coerceAtLeast(1).coerceAtMost(remainingBudget)
            }
            val sampleCount = minOf(sourceTriangleCount, partBudget)
            val positions = part.positions.asReadOnlyBuffer()
            val indices = part.indices?.asReadOnlyBuffer()

            for (sampleIndex in 0 until sampleCount) {
                val sourceTriangle = ((sampleIndex.toLong() * sourceTriangleCount.toLong()) / sampleCount)
                    .toInt().coerceAtMost(sourceTriangleCount - 1)
                for (corner in 0..2) {
                    val orderedIndex = sourceTriangle * 3 + corner
                    val vertexIndex = if (indices != null) indices.get(orderedIndex).toInt() and 0xFFFF else orderedIndex
                    val sourceOffset = vertexIndex * 3
                    if (sourceOffset + 2 >= positions.limit()) continue
                    val destOffset = outputTriangleCount * 9 + corner * 3
                    output[destOffset] = positions.get(sourceOffset)
                    output[destOffset + 1] = positions.get(sourceOffset + 1)
                    output[destOffset + 2] = positions.get(sourceOffset + 2)
                }
                outputTriangleCount++
            }
            remainingBudget -= sampleCount
            remainingSourceTriangles -= sourceTriangleCount
        }
        return if (outputTriangleCount * 9 == output.size) output else output.copyOf(outputTriangleCount * 9)
    }

    private data class PreviewPart(
        val positions: FloatBuffer,
        val indices: ShortBuffer?,
        val drawCount: Int
    )

    companion object {
        fun invalidateSharedTextureCache() = ImportedTextureCache.invalidateAll()
    }

    fun render(
        aPositionLoc: Int,
        aNormalLoc: Int,
        uColorLoc: Int,
        tintColor: FloatArray? = null,
        aTexCoordLoc: Int = -1,
        uUseTextureLoc: Int = -1,
        uTextureLoc: Int = -1
    ) {
        if (renderParts.isNotEmpty()) {
            renderParts.forEach { part ->
                // Preserve material colors/textures on imported models; gameplay tint remains for
                // old procedural models but must not turn a textured imported character green.
                // Imported assets keep their own material factor even without a bitmap; never tint
                // a model gray/green/etc. with the gameplay World's entity color.
                val tint = if (isUserImported) null else tintColor
                part.render(aPositionLoc, aNormalLoc, uColorLoc, aTexCoordLoc, uUseTextureLoc, uTextureLoc, tint)
            }
            GLES20.glUniform1i(uUseTextureLoc, 0)
            return
        }

        vertexBuffer.position(0)
        normalBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)
        GLES20.glUniform4fv(uColorLoc, 1, if (isUserImported) defaultColor else (tintColor ?: defaultColor), 0)
        if (indexBuffer != null) {
            indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, vertexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        }
    }
}
