package com.example.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.graphics.SurfaceTexture
import android.view.Surface
import com.example.game.ObjectType
import com.example.game.WorldObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.opengl.Matrix

/**
 * Draws imported images and videos as textured, selectable world-space quads.
 * All GL operations and SurfaceTexture.updateTexImage() calls run on the GL render thread.
 * Video playback is capped to two concurrent decoders; remaining video planes show their poster.
 */
class MediaPlaneRenderer(context: Context) {
    private val appContext = context.applicationContext
    private var imageProgram = 0
    private var videoProgram = 0
    private var missingMediaTexture = 0
    private var positionLocImage = -1
    private var texCoordLocImage = -1
    private var mvpLocImage = -1
    private var texMatrixLocImage = -1
    private var texLocImage = -1
    private var opacityLocImage = -1
    private var positionLocVideo = -1
    private var texCoordLocVideo = -1
    private var mvpLocVideo = -1
    private var texLocVideo = -1
    private var texMatrixLocVideo = -1
    private var opacityLocVideo = -1

    private val positions: FloatBuffer = floatBuffer(floatArrayOf(
        -0.5f, -0.5f, 0f,
         0.5f, -0.5f, 0f,
        -0.5f,  0.5f, 0f,
         0.5f,  0.5f, 0f
    ))
    // GLUtils uploads Bitmap pixels with their origin at the top-left, so images use flipped V.
    private val texCoords: FloatBuffer = floatBuffer(floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    ))
    // SurfaceTexture supplies its own sampling transform. Give it normal GL UVs and apply its
    // transform exactly once; reusing the bitmap UVs here flips video vertically on many devices.
    private val videoTexCoords: FloatBuffer = floatBuffer(floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    ))
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identityTextureMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Access-order LRU prevents unbounded GPU memory use when users import many large images.
    private val imageTextures = LinkedHashMap<String, Int>(16, 0.75f, true)
    private val videoSurfaces = LinkedHashMap<String, VideoSurface>()
    private val failedVideoIds = HashSet<String>()
    private var activeVideoIds: Set<String> = emptySet()
    @Volatile private var playbackPaused: Boolean = false

    private data class VideoSurface(
        val id: String,
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val player: MediaPlayer,
        val frameAvailable: AtomicBoolean = AtomicBoolean(false),
        val textureMatrix: FloatArray = FloatArray(16),
        @Volatile var prepared: Boolean = false,
        @Volatile var failed: Boolean = false,
        @Volatile var hasFrame: Boolean = false
    )

    fun onSurfaceCreated() {
        // The EGL context may have been recreated. Surface/player handles are not reusable.
        releaseAllVideos()
        imageTextures.clear() // Old GL names belonged to the previous context.
        missingMediaTexture = createMissingMediaTexture()
        failedVideoIds.clear()
        activeVideoIds = emptySet()
        imageProgram = try { createProgram(VERTEX_SHADER, IMAGE_FRAGMENT_SHADER) } catch (_: Exception) { 0 }
        videoProgram = try { createProgram(VERTEX_SHADER, VIDEO_FRAGMENT_SHADER) } catch (_: Exception) { 0 }
        if (imageProgram != 0) {
            positionLocImage = GLES20.glGetAttribLocation(imageProgram, "aPosition")
            texCoordLocImage = GLES20.glGetAttribLocation(imageProgram, "aTexCoord")
            mvpLocImage = GLES20.glGetUniformLocation(imageProgram, "uMVPMatrix")
            texMatrixLocImage = GLES20.glGetUniformLocation(imageProgram, "uTexMatrix")
            texLocImage = GLES20.glGetUniformLocation(imageProgram, "uTexture")
            opacityLocImage = GLES20.glGetUniformLocation(imageProgram, "uOpacity")
        }
        if (videoProgram != 0) {
            positionLocVideo = GLES20.glGetAttribLocation(videoProgram, "aPosition")
            texCoordLocVideo = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
            mvpLocVideo = GLES20.glGetUniformLocation(videoProgram, "uMVPMatrix")
            texLocVideo = GLES20.glGetUniformLocation(videoProgram, "uTexture")
            texMatrixLocVideo = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
            opacityLocVideo = GLES20.glGetUniformLocation(videoProgram, "uOpacity")
        }
    }

    /** Keep at most two nearby videos decoding; inactive planes use their static thumbnail. */
    fun setActiveVideoIds(ids: Set<String>) {
        activeVideoIds = ids.take(MAX_ACTIVE_VIDEOS).toSet()
        val stale = videoSurfaces.keys.filter { it !in activeVideoIds }
        stale.forEach { id -> videoSurfaces.remove(id)?.let(::releaseVideo) }
    }

    /** Call on pause/background; this does not release GL objects and must run on the GL thread. */
    fun setPlaybackPaused(paused: Boolean) {
        playbackPaused = paused
        videoSurfaces.values.forEach { video ->
            if (!video.prepared || video.failed) return@forEach
            try {
                if (paused && video.player.isPlaying) video.player.pause()
                else if (!paused && !video.player.isPlaying) video.player.start()
            } catch (_: Exception) {
                video.failed = true
            }
        }
    }

    fun render(obj: WorldObject, viewProjectionMatrix: FloatArray, shouldPlayVideo: Boolean) {
        if (!obj.isMediaPlane) return
        val sourcePath = obj.mediaFilePath?.takeIf { it.isNotBlank() }
        val sourceExists = sourcePath?.let { File(it).isFile } == true
        val thumbnailPath = obj.mediaThumbnailPath?.takeIf { it.isNotBlank() && File(it).isFile }
        if (!sourceExists && thumbnailPath == null) {
            // Keep a visible world-space placeholder when an imported file was deleted. The
            // persisted object remains selectable and can be repaired by re-importing the asset.
            if (imageProgram != 0 && missingMediaTexture != 0) {
                drawImageTexture(obj, viewProjectionMatrix, missingMediaTexture)
            }
            return
        }

        if (obj.type == ObjectType.MEDIA_VIDEO && sourceExists && videoProgram != 0 && shouldPlayVideo && obj.id in activeVideoIds && !playbackPaused) {
            val video = getOrCreateVideo(obj)
            if (video != null && !video.failed) {
                if (video.frameAvailable.compareAndSet(true, false)) {
                    try {
                        video.surfaceTexture.updateTexImage()
                        video.surfaceTexture.getTransformMatrix(video.textureMatrix)
                        video.hasFrame = true
                    } catch (_: RuntimeException) {
                        video.failed = true
                    }
                }
                if (video.hasFrame) {
                    drawExternalTexture(obj, viewProjectionMatrix, video)
                    return
                }
            }
            // During decoder startup, show the poster rather than a black plane.
        }

        if (imageProgram == 0) return
        val displayPath = if (obj.type == ObjectType.MEDIA_VIDEO) thumbnailPath else sourcePath?.takeIf { sourceExists } ?: thumbnailPath
        if (displayPath.isNullOrBlank()) {
            if (imageProgram != 0 && missingMediaTexture != 0) drawImageTexture(obj, viewProjectionMatrix, missingMediaTexture)
            return
        }
        val key = if (obj.type == ObjectType.MEDIA_VIDEO) "poster:${obj.mediaId ?: obj.id}" else (obj.mediaId ?: displayPath)
        val texture = getOrCreateImageTexture(key, displayPath) ?: return
        drawImageTexture(obj, viewProjectionMatrix, texture)
    }

    private fun createMissingMediaTexture(): Int {
        val ids = IntArray(1)
        return try {
            GLES20.glGenTextures(1, ids, 0)
            if (ids[0] == 0) return 0
            // A simple high-contrast 2x2 checker makes a missing media object obvious without
            // requiring any external asset or throwing away the saved transform.
            val pixels = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
            pixels.put(byteArrayOf(
                0x55, 0x55, 0x55, 0xFF.toByte(), 0xCC.toByte(), 0x33, 0xCC.toByte(), 0xFF.toByte(),
                0xCC.toByte(), 0x33, 0xCC.toByte(), 0xFF.toByte(), 0x55, 0x55, 0x55, 0xFF.toByte()
            ))
            pixels.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            ids[0]
        } catch (_: Exception) {
            if (ids[0] != 0) GLES20.glDeleteTextures(1, ids, 0)
            0
        }
    }

    private fun getOrCreateImageTexture(key: String, path: String): Int? {
        imageTextures[key]?.let { return it }
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_TEXTURE_EDGE || bounds.outHeight / sample > MAX_TEXTURE_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }) ?: return null
        val ids = IntArray(1)
        try {
            GLES20.glGenTextures(1, ids, 0)
            if (ids[0] == 0) return null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            while (imageTextures.size >= MAX_IMAGE_TEXTURES) {
                val oldest = imageTextures.entries.iterator()
                if (!oldest.hasNext()) break
                val entry = oldest.next()
                oldest.remove()
                GLES20.glDeleteTextures(1, intArrayOf(entry.value), 0)
            }
            imageTextures[key] = ids[0]
            return ids[0]
        } catch (_: Exception) {
            if (ids[0] != 0) GLES20.glDeleteTextures(1, ids, 0)
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun getOrCreateVideo(obj: WorldObject): VideoSurface? {
        videoSurfaces[obj.id]?.let { return it }
        if (obj.id in failedVideoIds || videoSurfaces.size >= MAX_ACTIVE_VIDEOS) return null
        val path = obj.mediaFilePath?.takeIf { File(it).isFile } ?: run {
            failedVideoIds += obj.id
            return null
        }
        val textureIds = IntArray(1)
        var surfaceTextureForCleanup: SurfaceTexture? = null
        var surfaceForCleanup: Surface? = null
        var playerForCleanup: MediaPlayer? = null
        try {
            GLES20.glGenTextures(1, textureIds, 0)
            if (textureIds[0] == 0) throw IllegalStateException("Could not allocate a video texture")
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val createdTexture = SurfaceTexture(textureIds[0])
            surfaceTextureForCleanup = createdTexture
            val createdSurface = Surface(createdTexture)
            surfaceForCleanup = createdSurface
            val createdPlayer = MediaPlayer()
            playerForCleanup = createdPlayer
            val created = VideoSurface(obj.id, textureIds[0], createdTexture, createdSurface, createdPlayer)
            createdTexture.setOnFrameAvailableListener { created.frameAvailable.set(true) }
            createdPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            createdPlayer.setDataSource(path)
            createdPlayer.setSurface(createdSurface)
            createdPlayer.isLooping = true
            createdPlayer.setVolume(0f, 0f) // Imported world videos are muted by default.
            createdPlayer.setOnPreparedListener { mp ->
                try {
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    created.prepared = true
                    if (!playbackPaused && created.id in activeVideoIds) {
                        mp.start() // Autoplay when this plane enters the active nearby-video set.
                    }
                } catch (_: Exception) {
                    created.failed = true
                }
            }
            createdPlayer.setOnErrorListener { _, _, _ ->
                created.failed = true
                true
            }
            createdPlayer.prepareAsync()
            videoSurfaces[obj.id] = created
            return created
        } catch (_: Exception) {
            failedVideoIds += obj.id
            try { playerForCleanup?.release() } catch (_: Exception) {}
            try { surfaceForCleanup?.release() } catch (_: Exception) {}
            try { surfaceTextureForCleanup?.release() } catch (_: Exception) {}
            if (textureIds[0] != 0) GLES20.glDeleteTextures(1, textureIds, 0)
            return null
        }
    }

    private fun drawImageTexture(obj: WorldObject, viewProjectionMatrix: FloatArray, textureId: Int) {
        GLES20.glUseProgram(imageProgram)
        setupQuad(obj, viewProjectionMatrix, positionLocImage, texCoordLocImage, mvpLocImage)
        GLES20.glUniformMatrix4fv(texMatrixLocImage, 1, false, identityTextureMatrix, 0)
        GLES20.glUniform1i(texLocImage, 0)
        GLES20.glUniform1f(opacityLocImage, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        finishQuad(positionLocImage, texCoordLocImage)
    }

    private fun drawExternalTexture(obj: WorldObject, viewProjectionMatrix: FloatArray, video: VideoSurface) {
        GLES20.glUseProgram(videoProgram)
        setupQuad(obj, viewProjectionMatrix, positionLocVideo, texCoordLocVideo, mvpLocVideo, useVideoTextureCoordinates = true)
        GLES20.glUniform1i(texLocVideo, 0)
        GLES20.glUniform1f(opacityLocVideo, 1f)
        GLES20.glUniformMatrix4fv(texMatrixLocVideo, 1, false, video.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, video.textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        finishQuad(positionLocVideo, texCoordLocVideo)
    }

    private fun setupQuad(
        obj: WorldObject,
        viewProjectionMatrix: FloatArray,
        positionLocation: Int,
        texCoordLocation: Int,
        mvpLocation: Int,
        useVideoTextureCoordinates: Boolean = false
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
        Matrix.rotateM(modelMatrix, 0, obj.rotationXDegrees, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, obj.rotationYDegrees, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, obj.rotationZDegrees, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, obj.size.x, obj.size.y, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvpMatrix, 0)
        positions.position(0)
        val uvBuffer = if (useVideoTextureCoordinates) videoTexCoords else texCoords
        uvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glDisable(GLES20.GL_CULL_FACE) // Render the plane from either side.
    }

    private fun finishQuad(positionLocation: Int, texCoordLocation: Int) {
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun releaseAllVideos() {
        videoSurfaces.values.toList().forEach(::releaseVideo)
        videoSurfaces.clear()
    }

    private fun releaseVideo(video: VideoSurface) {
        try { video.player.setOnPreparedListener(null) } catch (_: Exception) {}
        try { video.player.setOnErrorListener(null) } catch (_: Exception) {}
        try { video.player.stop() } catch (_: Exception) {}
        try { video.player.release() } catch (_: Exception) {}
        try { video.surface.release() } catch (_: Exception) {}
        try { video.surfaceTexture.setOnFrameAvailableListener(null) } catch (_: Exception) {}
        try { video.surfaceTexture.release() } catch (_: Exception) {}
        try { GLES20.glDeleteTextures(1, intArrayOf(video.textureId), 0) } catch (_: Exception) {}
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (linked[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Media shader link failed: $error")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Media shader compile failed: $error")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    companion object {
        private const val MAX_TEXTURE_EDGE = 2048
        private const val MAX_IMAGE_TEXTURES = 24
        private const val MAX_ACTIVE_VIDEOS = 2

        private val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uOpacity);
            }
        """.trimIndent()

        private val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uOpacity);
            }
        """.trimIndent()
    }
}
