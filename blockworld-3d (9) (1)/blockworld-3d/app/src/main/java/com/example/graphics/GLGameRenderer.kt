package com.example.graphics

import com.example.vfx.GameVfxRenderer
import com.example.environment.EnvironmentGlRenderer
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.game.*
import com.example.model.Vector3
import com.example.teapot.ModelSwapManager
import com.example.teapot.TeapotModelRegistry
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import com.example.environment.EnvironmentController
import com.example.environment.EnvironmentVisualSettingsStore
import com.example.teapot.Custom3DMesh
import com.example.media.MediaPlaneRenderer
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
enum class RotationAxis { X, Y, Z }

/**
 * Robust OpenGL ES 2.0 Renderer.
 * Zero per-frame allocatFions, NaN-proof matrix transformations,
 * and crash-proof object health bar rendering.
 */
class GLGameRenderer(
    private val context: Context,
    val engine: GameEngine
) : GLSurfaceView.Renderer {
    private val gameVfxRenderer = GameVfxRenderer(context.applicationContext)
    private val mediaPlaneRenderer = MediaPlaneRenderer(context.applicationContext)
    private var lastGameVfxFrameNanos: Long = 0L

    /** Must be invoked on the GL thread (GameScreen queues it through GLSurfaceView.queueEvent). */
    fun setMediaPlaybackPaused(paused: Boolean) {
        mediaPlaneRenderer.setPlaybackPaused(paused)
    }

    fun setRotationGizmoVisible(visible: Boolean) {
        isRotationGizmoVisible = visible
    }

    /** Returns the X/Y/Z ring nearest a touch point, but only when it is on the visible gizmo. */
    fun beginRotationGizmoDrag(touchX: Float, touchY: Float): RotationAxis? {
        if (!isRotationGizmoVisible) return null
        val objectId = selectedTargetId ?: return null
        val obj = engine.worldManager.objects.firstOrNull { it.id == objectId && !it.isDestroyed } ?: return null
        val viewProjection = FloatArray(16)
        synchronized(currentViewProjCopy) { System.arraycopy(currentViewProjCopy, 0, viewProjection, 0, 16) }
        val radius = rotationGizmoRadius(obj)
        var bestAxis: RotationAxis? = null
        var bestDistance = Float.MAX_VALUE
        for (axis in RotationAxis.values()) {
            val points = projectRotationRing(obj, axis, radius, viewProjection) ?: continue
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val distance = distanceToSegment(touchX, touchY, a.first, a.second, b.first, b.second)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestAxis = axis
                }
            }
        }
        return bestAxis.takeIf { bestDistance <= ROTATION_GIZMO_HIT_RADIUS_PX }
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0.0001f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun rotationGizmoRadius(obj: WorldObject): Float =
        (max(obj.size.x, max(obj.size.y, obj.size.z)) * 0.62f).coerceIn(0.85f, 3.6f)

    private fun ringWorldPoint(obj: WorldObject, axis: RotationAxis, radius: Float, angle: Float): FloatArray {
        val c = cos(angle) * radius
        val s = sin(angle) * radius
        return when (axis) {
            RotationAxis.X -> floatArrayOf(obj.position.x, obj.position.y + c, obj.position.z + s)
            RotationAxis.Y -> floatArrayOf(obj.position.x + c, obj.position.y, obj.position.z + s)
            RotationAxis.Z -> floatArrayOf(obj.position.x + c, obj.position.y + s, obj.position.z)
        }
    }

    private fun projectRotationRing(
        obj: WorldObject,
        axis: RotationAxis,
        radius: Float,
        viewProjection: FloatArray
    ): List<Pair<Float, Float>>? {
        val result = ArrayList<Pair<Float, Float>>(ROTATION_RING_SEGMENTS)
        val clip = FloatArray(4)
        for (i in 0 until ROTATION_RING_SEGMENTS) {
            val a = (i.toFloat() / ROTATION_RING_SEGMENTS) * (Math.PI.toFloat() * 2f)
            val p = ringWorldPoint(obj, axis, radius, a)
            Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
            if (clip[3] <= 0.001f) return null
            val nx = clip[0] / clip[3]
            val ny = clip[1] / clip[3]
            result += Pair((nx + 1f) * 0.5f * screenWidth, (1f - ny) * 0.5f * screenHeight)
        }
        return result
    }

    private fun renderRotationGizmo() {
        if (!isRotationGizmoVisible || particleProgramId == 0) return
        val obj = engine.worldManager.objects.firstOrNull { it.id == selectedTargetId && !it.isDestroyed } ?: return
        val radius = rotationGizmoRadius(obj)
        GLES20.glUseProgram(particleProgramId)
        GLES20.glUniformMatrix4fv(uPartMVPMatrixLoc, 1, false, viewProjMatrix, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // Keep the interaction rings readable over the selected mesh.
        GLES20.glLineWidth(5f)
        GLES20.glEnableVertexAttribArray(aPartPositionLoc)
        val colors = arrayOf(
            floatArrayOf(1f, 0.16f, 0.16f, 1f),
            floatArrayOf(0.18f, 1f, 0.35f, 1f),
            floatArrayOf(0.18f, 0.55f, 1f, 1f)
        )
        val axes = RotationAxis.values()
        for (axisIndex in axes.indices) {
            val axis = axes[axisIndex]
            rotationGizmoBuffer.clear()
            for (i in 0 until ROTATION_RING_SEGMENTS) {
                val angle = (i.toFloat() / ROTATION_RING_SEGMENTS) * (Math.PI.toFloat() * 2f)
                val point = ringWorldPoint(obj, axis, radius, angle)
                rotationGizmoBuffer.put(point[0]).put(point[1]).put(point[2])
            }
            rotationGizmoBuffer.position(0)
            GLES20.glVertexAttribPointer(aPartPositionLoc, 3, GLES20.GL_FLOAT, false, 0, rotationGizmoBuffer)
            GLES20.glUniform4fv(uPartColorLoc, 1, colors[axisIndex], 0)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, ROTATION_RING_SEGMENTS)
        }
        GLES20.glDisableVertexAttribArray(aPartPositionLoc)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glLineWidth(1f)
        GLES20.glUseProgram(worldProgramId)
    }

    private lateinit var skyRenderer: SkyRenderer

    // Shader Programs
    private var worldProgramId: Int = 0
    private var particleProgramId: Int = 0

    // World Uniforms
    private var uMVPMatrixLoc = -1
    private var uModelMatrixLoc = -1
    private var uNormalMatrixLoc = -1
    private var uColorLoc = -1
    private var uLightDirLoc = -1
    private var uCameraPosLoc = -1
    private var uHitFlashLoc = -1
    private var aPositionLoc = -1
    private var aNormalLoc = -1
    // Texture coordinates/material uniforms are required for imported GLB base-colour maps.
    private var aTexCoordLoc = -1
    private var uUseTextureLoc = -1
    private var uTextureLoc = -1

    // Particle Uniforms
    private var uPartMVPMatrixLoc = -1
    private var uPartColorLoc = -1
    private var aPartPositionLoc = -1




    private var uFogGlowLoc = -1

    // Dedicated live environment uniforms (not shared with the legacy GameSettings pass).
    private var uLiveObjectBrightnessLoc = -1
    private var uLiveFogEnabledLoc = -1
    private var uLiveFogColorLoc = -1
    private var uLiveFogDensityLoc = -1
    private var uLiveFogGlowLoc = -1

    // Geometry Buffers
    private lateinit var cubeVertices: FloatBuffer
    private lateinit var cubeNormals: FloatBuffer
    private lateinit var cubeIndices: ShortBuffer

    private lateinit var crystalVertices: FloatBuffer
    private lateinit var crystalNormals: FloatBuffer

    private lateinit var wireCubeVertices: FloatBuffer
    private lateinit var selectionRingVertices: FloatBuffer

    @Volatile var selectedTargetId: String? = null
    @Volatile var selectedTargetIds: Set<String> = emptySet()
    @Volatile var isRotationGizmoVisible: Boolean = false
        private set
    @Volatile var isSpectatorEditingActive: Boolean = false

    /** Clears editor-only visuals when spectator editing ends. */
    fun clearSpectatorEditorVisuals() {
        isSpectatorEditingActive = false
        isRotationGizmoVisible = false
        selectedTargetId = null
        selectedTargetIds = emptySet()
        hoverPreviewPosition = null
        hoverPreviewSize = null
        hoverPreviewModelId = null
    }
    private val rotationGizmoBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(ROTATION_RING_SEGMENTS * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    var screenWidth: Int = 1080
    var screenHeight: Int = 2400

    @Volatile var hoverPreviewPosition: com.example.model.Vector3? = null
    @Volatile var hoverPreviewSize: com.example.model.Vector3? = null
    @Volatile var hoverPreviewModelId: String? = null

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val compositeParentTransform = FloatArray(16)
    private val compositeDrawModelMatrix = FloatArray(16)
    private var compositeTransformActive = false
    private val viewProjMatrix = FloatArray(16)
    private val invViewProjMatrix = FloatArray(16)

    // Thread-safe matrix snapshots for touch raycasting
    private val currentViewProjCopy = FloatArray(16)
    private val currentInvViewProjCopy = FloatArray(16)

    // Pre-allocated static matrix stack with bounds checking
    private val matrixStack = Array(32) { FloatArray(16) }
    private var stackIndex = 0

    private val lightDirection = floatArrayOf(0.45f, 0.8f, 0.4f)
    private val cameraPosArray = FloatArray(3)

    // Pre-allocated reusable color buffers (Zero allocations in draw loop)
    private val headColor = floatArrayOf(1.0f, 0.84f, 0.0f, 1.0f)
    private val torsoColor = floatArrayOf(0.12f, 0.53f, 0.9f, 1.0f)
    private val limbsColor = floatArrayOf(1.0f, 0.84f, 0.0f, 1.0f)
    private val pantsColor = floatArrayOf(0.15f, 0.68f, 0.38f, 1.0f)
    private val ghostColor = floatArrayOf(0.3f, 0.7f, 1.0f, 0.35f)
    private val swordBladeColor = floatArrayOf(0.2f, 0.85f, 1.0f, 1.0f)
    private val swordSlashColor = floatArrayOf(1.0f, 0.3f, 0.1f, 1.0f)
    private val tempColor = FloatArray(4)

    private var uFogEnabledLoc = -1
    private var uFogColorLoc = -1
    private var uFogBrightnessLoc = -1
    private var uFogOpacityLoc = -1
    private var uFogDensityLoc = -1
    private var uFogStartLoc = -1
    private var uFogEndLoc = -1
    private var uFogSoftnessLoc = -1

    private var uObjectBrightnessLoc = -1
    private var uObjectSaturationLoc = -1
    private var uObjectOpacityLoc = -1
    private var uObjectContrastLoc = -1
    private var uObjectTemperatureLoc = -1
    private var uObjectTextureEdgeSoftnessLoc = -1

    private var uSunColorLoc = -1
    private var uSunIntensityLoc = -1
    private var uAmbientIntensityLoc = -1

    private var lastTimeNanos: Long = 0L
    private var animationFrameToken: Long = 0L

    // Only update/render world models near the player. World objects remain in WorldManager,
    // so they become visible again naturally when the player approaches them.
    private val worldRenderDistance = 34f
    private val worldRenderDistanceSquared = worldRenderDistance * worldRenderDistance
//    private var animationFrameToken: Long = 0L


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        EnvironmentVisualSettingsStore.initialize(context.applicationContext)
        GLES20.glClearColor(0.55f, 0.78f, 0.95f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        worldProgramId = GLShaderHelper.createProgram(
            GLShaderHelper.WORLD_VERTEX_SHADER,
            GLShaderHelper.WORLD_FRAGMENT_SHADER

        )
        cacheAppearanceUniformLocations()
        uMVPMatrixLoc = GLES20.glGetUniformLocation(worldProgramId, "uMVPMatrix")
        uModelMatrixLoc = GLES20.glGetUniformLocation(worldProgramId, "uModelMatrix")
        uNormalMatrixLoc = GLES20.glGetUniformLocation(worldProgramId, "uNormalMatrix")
        uColorLoc = GLES20.glGetUniformLocation(worldProgramId, "uColor")
        uLightDirLoc = GLES20.glGetUniformLocation(worldProgramId, "uLightDir")
        uCameraPosLoc = GLES20.glGetUniformLocation(worldProgramId, "uCameraPos")
        uHitFlashLoc = GLES20.glGetUniformLocation(worldProgramId, "uHitFlash")
        aPositionLoc = GLES20.glGetAttribLocation(worldProgramId, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(worldProgramId, "aNormal")
        aTexCoordLoc = GLES20.glGetAttribLocation(worldProgramId, "aTexCoord")
        uUseTextureLoc = GLES20.glGetUniformLocation(worldProgramId, "uUseTexture")
        uTextureLoc = GLES20.glGetUniformLocation(worldProgramId, "uTexture")
        Custom3DMesh.invalidateSharedTextureCache()
        mediaPlaneRenderer.onSurfaceCreated()

        particleProgramId = GLShaderHelper.createProgram(
            GLShaderHelper.PARTICLE_VERTEX_SHADER,
            GLShaderHelper.PARTICLE_FRAGMENT_SHADER
        )
        uPartMVPMatrixLoc = GLES20.glGetUniformLocation(particleProgramId, "uMVPMatrix")
        uPartColorLoc = GLES20.glGetUniformLocation(particleProgramId, "uColor")
        aPartPositionLoc = GLES20.glGetAttribLocation(particleProgramId, "aPosition")

        cubeVertices = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_VERTICES)
        cubeNormals = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_NORMALS)
        cubeIndices = MeshBuilder.createShortBuffer(MeshBuilder.CUBE_INDICES)

        crystalVertices = MeshBuilder.createFloatBuffer(MeshBuilder.CRYSTAL_VERTICES)
        crystalNormals = MeshBuilder.createFloatBuffer(MeshBuilder.CRYSTAL_NORMALS)

        wireCubeVertices = MeshBuilder.createFloatBuffer(MeshBuilder.WIRE_CUBE_VERTICES)
        selectionRingVertices = MeshBuilder.createFloatBuffer(MeshBuilder.createSelectionRing(32, 1.0f))

        skyRenderer = SkyRenderer(context)
        skyRenderer.initGL()

        // OpenGL texture IDs are scoped to the EGL context. Re-upload original model textures
        // when Android recreates that context (rotation, memory pressure, background/foreground).
        TeapotModelRegistry.getModels().forEach { it.invalidateGlTexture() }

        lastTimeNanos = System.nanoTime()
        gameVfxRenderer.onSurfaceCreated()



        uObjectBrightnessLoc = GLES20.glGetUniformLocation(
            worldProgramId,
            "uObjectBrightness"
        )

        uFogEnabledLoc = GLES20.glGetUniformLocation(
            worldProgramId,
            "uFogEnabled"
        )

        uFogColorLoc = GLES20.glGetUniformLocation(
            worldProgramId,
            "uFogColor"
        )

        uFogDensityLoc = GLES20.glGetUniformLocation(
            worldProgramId,
            "uFogDensity"
        )

        uFogGlowLoc = GLES20.glGetUniformLocation(
            worldProgramId,
            "uFogGlow"
        )
        uLiveObjectBrightnessLoc = GLES20.glGetUniformLocation(worldProgramId, "uLiveObjectBrightness")
        uLiveFogEnabledLoc = GLES20.glGetUniformLocation(worldProgramId, "uLiveFogEnabled")
        uLiveFogColorLoc = GLES20.glGetUniformLocation(worldProgramId, "uLiveFogColor")
        uLiveFogDensityLoc = GLES20.glGetUniformLocation(worldProgramId, "uLiveFogDensity")
        uLiveFogGlowLoc = GLES20.glGetUniformLocation(worldProgramId, "uLiveFogGlow")

    }
    private fun applyWorldVisualSettings() {
        val settings = EnvironmentVisualSettingsStore.snapshot()
        val fogColor = EnvironmentVisualSettingsStore.colorInt(settings.fogColorHex)

        if (uLiveObjectBrightnessLoc >= 0) {
            GLES20.glUniform1f(uLiveObjectBrightnessLoc, settings.objectBrightness.coerceIn(0f, 2f))
        }
        if (uLiveFogEnabledLoc >= 0) {
            GLES20.glUniform1i(uLiveFogEnabledLoc, if (settings.fogEnabled) 1 else 0)
        }
        if (uLiveFogColorLoc >= 0) {
            GLES20.glUniform3f(
                uLiveFogColorLoc,
                android.graphics.Color.red(fogColor) / 255.0f,
                android.graphics.Color.green(fogColor) / 255.0f,
                android.graphics.Color.blue(fogColor) / 255.0f
            )
        }
        if (uLiveFogDensityLoc >= 0) {
            GLES20.glUniform1f(uLiveFogDensityLoc, settings.fogDensity.coerceIn(0f, 0.08f))
        }
        if (uLiveFogGlowLoc >= 0) {
            GLES20.glUniform1f(uLiveFogGlowLoc, settings.fogGlow.coerceIn(0f, 2f))
        }

        // The live settings screen owns fog behavior now; disable the legacy fog pass
        // so its saved default cannot make the live Fog switch appear broken.
        if (uFogEnabledLoc >= 0) GLES20.glUniform1i(uFogEnabledLoc, 0)
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(height.toFloat(), 1.0f)
        Matrix.perspectiveM(projectionMatrix, 0, 58.0f, aspect, 0.5f, 150.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastTimeNanos > 0L) {
            ((now - lastTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        } else {
            0.016f
        }
        lastTimeNanos = now

        // 1. Thread-safe Game Simulation Update on GLThread
        engine.update(dt)

        // ANIMATED GLB UPDATE BEGIN
        // Do skinning on the OpenGL thread before drawing, not on Compose/UI threads.
        val animationFrameToken = ++this.animationFrameToken
        val animationPlayerMesh = ModelSwapManager.getSwappedMesh("player_character")
        val animationVelocity = engine.player.velocity
        val animationHorizontalSpeed = kotlin.math.sqrt(
            animationVelocity.x * animationVelocity.x + animationVelocity.z * animationVelocity.z
        )
        val animationPlayerAction = engine.player.actionState.name
        val animationUpdatedIds = HashSet<String>()

        animationPlayerMesh?.let { mesh ->
            if (animationUpdatedIds.add(mesh.id)) {
                mesh.updateAnimation(dt, animationFrameToken, animationPlayerAction, animationHorizontalSpeed)
            }
        }
        // If the user imported the animated GLB onto the currently selected target instead of
        // Player Character, let the same joystick action drive that selected model as well.
        selectedTargetId?.let { targetId ->
            val selectedAnimationMesh = ModelSwapManager.getSwappedMesh(targetId)
            if (selectedAnimationMesh != null && animationUpdatedIds.add(selectedAnimationMesh.id)) {
                selectedAnimationMesh.updateAnimation(
                    dt, animationFrameToken, animationPlayerAction, animationHorizontalSpeed
                )
            }
        }
        for (animationWorldObject in engine.worldManager.objects) {
            if (animationWorldObject.isDestroyed) continue
            val dx = animationWorldObject.position.x - engine.player.position.x
            val dz = animationWorldObject.position.z - engine.player.position.z
            if (dx * dx + dz * dz > worldRenderDistanceSquared) continue
            val animationMesh = ModelSwapManager.getSwappedMesh(animationWorldObject.id) ?: continue
            if (animationUpdatedIds.add(animationMesh.id)) {
                animationMesh.updateAnimation(dt, animationFrameToken)
            }
        }




        val environment = EnvironmentController.snapshot()

        EnvironmentGlRenderer.applyClearColor(
            settings = environment,

            // Replace these with the clear-color values
            // already used by your renderer.
            originalR = 0.42f,
            originalG = 0.65f,
            originalB = 0.95f
        )


        // ANIMATED GLB UPDATE END
        // 2. Clear Screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 3. Setup Camera View Matrix
        val eye = engine.camera.eyePosition
        val target = engine.camera.viewTarget
        cameraPosArray[0] = eye.x
        cameraPosArray[1] = eye.y
        cameraPosArray[2] = eye.z

        Matrix.setLookAtM(
            viewMatrix, 0,
            eye.x, eye.y, eye.z,
            target.x, target.y, target.z,
            0f, 1f, 0f
        )

        // Compute View-Projection and its inverse for accurate 3D object raycasting & touch picking
        Matrix.multiplyMM(viewProjMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.invertM(invViewProjMatrix, 0, viewProjMatrix, 0)

        synchronized(currentViewProjCopy) {
            System.arraycopy(viewProjMatrix, 0, currentViewProjCopy, 0, 16)
            System.arraycopy(invViewProjMatrix, 0, currentInvViewProjCopy, 0, 16)
        }

        // 4. Render Sky Dome with the same live values changed by EnvironmentSettingsActivity.
        val visualEnvironment = EnvironmentVisualSettingsStore.snapshot()
        skyRenderer.render(
            viewMatrix = viewMatrix,
            projectionMatrix = projectionMatrix,
            cameraPos = cameraPosArray,
            skyType = engine.settings.skyEnvironment,
            skyTintColor = EnvironmentVisualSettingsStore.colorInt(visualEnvironment.skyColorHex),
            skyTintStrength = visualEnvironment.skyTintStrength,
            skyBrightness = visualEnvironment.skyBrightness
        )

        // 5. Render Platforms & Attackable Objects
        renderWorldObjects()

        // Legacy cube ghost dash trail disabled; the transparent yellow-thunder PNG is used instead.

        // 7. Render Roblox Humanoid Character
        // Do not render the player's own body while the eye camera is inside the head.
        if (!engine.camera.isFirstPerson) renderRobloxCharacter(engine.player)

        // 8. Render 3D Particles
        renderParticles()
        val gameVfxNowNanos = System.nanoTime()
        val gameVfxDeltaSeconds = if (lastGameVfxFrameNanos == 0L) {
            0.016f
        } else {
            ((gameVfxNowNanos - lastGameVfxFrameNanos).toDouble() / 1_000_000_000.0).toFloat()
        }
        lastGameVfxFrameNanos = gameVfxNowNanos
        gameVfxRenderer.draw(viewMatrix, projectionMatrix, gameVfxDeltaSeconds)
        if (isSpectatorEditingActive) renderRotationGizmo()

    }

    private fun pushMatrix() {
        if (stackIndex < 31) {
            System.arraycopy(modelMatrix, 0, matrixStack[stackIndex], 0, 16)
            stackIndex++
        }
    }


    private fun cacheAppearanceUniformLocations() {

        fun location(name: String): Int {
            return GLES20.glGetUniformLocation(
                worldProgramId,
                name
            )
        }

        uFogEnabledLoc = location("uFogEnabled")
        uFogColorLoc = location("uFogColor")
        uFogBrightnessLoc = location("uFogBrightness")
        uFogOpacityLoc = location("uFogOpacity")
        uFogDensityLoc = location("uFogDensity")
        uFogStartLoc = location("uFogStartDistance")
        uFogEndLoc = location("uFogEndDistance")
        uFogSoftnessLoc = location("uFogSoftness")

        uObjectBrightnessLoc = location("uObjectBrightness")
        uObjectSaturationLoc = location("uObjectSaturation")
        uObjectOpacityLoc = location("uObjectOpacity")
        uObjectContrastLoc = location("uObjectContrast")
        uObjectTemperatureLoc = location("uObjectTemperature")
        uObjectTextureEdgeSoftnessLoc =
            location("uObjectTextureEdgeSoftness")

        uSunColorLoc = location("uSunColor")
        uSunIntensityLoc = location("uSunIntensity")
        uAmbientIntensityLoc = location("uAmbientIntensity")
    }

    private fun applyAppearanceUniforms() {

        val s = engine.settings.validatedForRendering()

        // Fog
        GLES20.glUniform1i(
            uFogEnabledLoc,
            if (s.fogEnabled) 1 else 0
        )

        GLES20.glUniform3f(
            uFogColorLoc,
            s.fogColorR,
            s.fogColorG,
            s.fogColorB
        )

        GLES20.glUniform1f(
            uFogBrightnessLoc,
            s.fogBrightness
        )

        GLES20.glUniform1f(
            uFogOpacityLoc,
            s.fogOpacity
        )

        GLES20.glUniform1f(
            uFogDensityLoc,
            s.fogDensity
        )

        GLES20.glUniform1f(
            uFogStartLoc,
            s.fogStartDistance
        )

        GLES20.glUniform1f(
            uFogEndLoc,
            s.fogEndDistance
        )

        GLES20.glUniform1f(
            uFogSoftnessLoc,
            s.fogSoftness
        )

        // Object appearance
        GLES20.glUniform1f(
            uObjectBrightnessLoc,
            s.objectBrightness
        )

        GLES20.glUniform1f(
            uObjectSaturationLoc,
            s.objectSaturation
        )

        GLES20.glUniform1f(
            uObjectOpacityLoc,
            s.objectOpacity
        )

        GLES20.glUniform1f(
            uObjectContrastLoc,
            s.objectContrast
        )

        GLES20.glUniform1f(
            uObjectTemperatureLoc,
            s.objectColorTemperature
        )

        GLES20.glUniform1f(
            uObjectTextureEdgeSoftnessLoc,
            s.objectTextureEdgeSoftness
        )

        // Sunlight and ambient lighting
        GLES20.glUniform3f(
            uSunColorLoc,
            s.sunColorR,
            s.sunColorG,
            s.sunColorB
        )

        GLES20.glUniform1f(
            uSunIntensityLoc,
            if (s.sunlightEnabled) s.sunIntensity else 0.0f
        )

        GLES20.glUniform1f(
            uAmbientIntensityLoc,
            s.ambientLightIntensity
        )

        // Use the normalized user-configured direction.
        val direction = s.normalizedSunDirection()

        // Keep your existing light-direction uniform.
        GLES20.glUniform3fv(
            uLightDirLoc,
            1,
            direction,
            0
        )
    }
    private fun popMatrix() {
        if (stackIndex > 0) {
            stackIndex--
            System.arraycopy(matrixStack[stackIndex], 0, modelMatrix, 0, 16)
        }
    }

    private fun updateMVP() {
        val drawModel = if (compositeTransformActive) {
            Matrix.multiplyMM(compositeDrawModelMatrix, 0, compositeParentTransform, 0, modelMatrix, 0)
            compositeDrawModelMatrix
        } else modelMatrix
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, drawModel, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // Guard against singular non-invertible matrix (prevents NaNs).
        if (Matrix.invertM(tempMatrix, 0, drawModel, 0)) {
            Matrix.transposeM(normalMatrix, 0, tempMatrix, 0)
        } else {
            Matrix.setIdentityM(normalMatrix, 0)
        }

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelMatrixLoc, 1, false, drawModel, 0)
        GLES20.glUniformMatrix4fv(uNormalMatrixLoc, 1, false, normalMatrix, 0)
    }

    private fun renderWorldObjects() {
        GLES20.glUseProgram(worldProgramId)
        applyAppearanceUniforms()
        // Apply live visual controls last so they take precedence over legacy settings uniforms.
        applyWorldVisualSettings()
        GLES20.glUniform3fv(uLightDirLoc, 1, lightDirection, 0)
        GLES20.glUniform3fv(uCameraPosLoc, 1, cameraPosArray, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aNormalLoc)

        val objects = engine.worldManager.objects
        // Keep only the two nearest visible world videos decoding. Other video planes use their
        // imported poster texture, avoiding an unbounded number of MediaPlayer/codec instances.
        val activeVideoIds = objects.asSequence()
            .filter { it.type == ObjectType.MEDIA_VIDEO && !it.isDestroyed }
            .map { obj ->
                val dx = obj.position.x - engine.player.position.x
                val dz = obj.position.z - engine.player.position.z
                Triple(obj.id, dx * dx + dz * dz, obj)
            }
            .filter { it.second <= worldRenderDistanceSquared }
            .sortedBy { it.second }
            .take(2)
            .map { it.first }
            .toSet()
        mediaPlaneRenderer.setActiveVideoIds(activeVideoIds)
        for (i in objects.indices) {
            val obj = objects[i]
            if (obj.isDestroyed) continue
            val playerDx = obj.position.x - engine.player.position.x
            val playerDz = obj.position.z - engine.player.position.z
            if (playerDx * playerDx + playerDz * playerDz > worldRenderDistanceSquared) continue

            GLES20.glUniform1f(uHitFlashLoc, if (obj.hitFlashTimer > 0f) 1.0f else 0.0f)

            if (obj.isMediaPlane) {
                mediaPlaneRenderer.render(
                    obj = obj,
                    viewProjectionMatrix = viewProjMatrix,
                    shouldPlayVideo = obj.id in activeVideoIds
                )
                // The media renderer uses dedicated image/video shaders. Restore the world shader
                // before the next ordinary object or the selection outline is drawn.
                GLES20.glUseProgram(worldProgramId)
                if (obj.isAttackable) renderObjectHealthBar(obj)
                continue
            }

            // Check if user swapped this object in Teapot Placement Mode
            val swappedMesh = ModelSwapManager.getSwappedMesh(obj.id)
            if (swappedMesh != null) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
                Matrix.rotateM(modelMatrix, 0, obj.rotationXDegrees, 1f, 0f, 0f)
                Matrix.rotateM(modelMatrix, 0, obj.rotationYDegrees, 0f, 1f, 0f)
                Matrix.rotateM(modelMatrix, 0, obj.rotationZDegrees, 0f, 0f, 1f)
                if (obj.type == ObjectType.TARGET_CRYSTAL || obj.type == ObjectType.FLOATING_DRONE) {
                    Matrix.rotateM(modelMatrix, 0, (System.currentTimeMillis() % 3600L) * 0.1f, 0f, 1f, 0f)
                }
                val nativeX = swappedMesh.boundsSize.x.coerceAtLeast(0.001f)
                val nativeY = swappedMesh.boundsSize.y.coerceAtLeast(0.001f)
                val nativeZ = swappedMesh.boundsSize.z.coerceAtLeast(0.001f)
                Matrix.scaleM(
                    modelMatrix, 0,
                    obj.size.x / nativeX,
                    obj.size.y / nativeY,
                    obj.size.z / nativeZ
                )
                updateMVP()
                // Keep authored textures/colors for imported and packaged GLBs; only procedural
                // built-in models should inherit the gameplay object's tint.
                val meshTint = if (swappedMesh.isUserImported || swappedMesh.sourcePath?.startsWith("asset://") == true) null else obj.color
                swappedMesh.render(
                    aPositionLoc, aNormalLoc, uColorLoc, meshTint,
                    aTexCoordLoc, uUseTextureLoc, uTextureLoc
                )
                if (obj.isAttackable) {
                    renderObjectHealthBar(obj)
                }
                continue
            }

            when (obj.type) {
                ObjectType.TARGET_CRYSTAL -> {
                    crystalVertices.position(0)
                    crystalNormals.position(0)
                    GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, crystalVertices)
                    GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, crystalNormals)

                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationXDegrees, 1f, 0f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationYDegrees, 0f, 1f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationZDegrees, 0f, 0f, 1f)
                    Matrix.rotateM(modelMatrix, 0, (System.currentTimeMillis() % 3600L) * 0.1f, 0f, 1f, 0f)
                    Matrix.rotateM(modelMatrix, 0, 15f, 1f, 0f, 0f)
                    Matrix.scaleM(modelMatrix, 0, obj.size.x, obj.size.y, obj.size.z)

                    updateMVP()
                    GLES20.glUniform4fv(uColorLoc, 1, obj.color, 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 24)
                }

                ObjectType.TRAINING_DUMMY -> {
                    renderTrainingDummy(obj)
                }

                ObjectType.BOUNCE_PAD -> {
                    cubeVertices.position(0)
                    cubeNormals.position(0)
                    cubeIndices.position(0)
                    GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
                    GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationXDegrees, 1f, 0f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationYDegrees, 0f, 1f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationZDegrees, 0f, 0f, 1f)
                    Matrix.scaleM(modelMatrix, 0, obj.size.x, obj.size.y, obj.size.z)
                    updateMVP()

                    val pulse = 0.85f + sin(System.currentTimeMillis() * 0.008).toFloat() * 0.15f
                    tempColor[0] = obj.color[0] * pulse
                    tempColor[1] = obj.color[1] * pulse
                    tempColor[2] = obj.color[2] * pulse
                    tempColor[3] = 1.0f
                    GLES20.glUniform4fv(uColorLoc, 1, tempColor, 0)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
                }

                else -> {
                    cubeVertices.position(0)
                    cubeNormals.position(0)
                    cubeIndices.position(0)
                    GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
                    GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, obj.position.x, obj.position.y, obj.position.z)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationXDegrees, 1f, 0f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationYDegrees, 0f, 1f, 0f)
                    Matrix.rotateM(modelMatrix, 0, obj.rotationZDegrees, 0f, 0f, 1f)
                    Matrix.scaleM(modelMatrix, 0, obj.size.x, obj.size.y, obj.size.z)
                    updateMVP()

                    GLES20.glUniform4fv(uColorLoc, 1, obj.color, 0)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
                }
            }

            // Only render health bar if object is alive, damaged, and has valid positive health
            if (obj.isAttackable && !obj.isDestroyed && obj.health > 0.05f && obj.health < obj.maxHealth) {
                renderObjectHealthBar(obj)
            }
        }

        // Draw one outline per selected member so multi-select remains visible in 3D.
        if (isSpectatorEditingActive) {
            val idsToOutline = selectedTargetIds.takeIf { it.isNotEmpty() }
                ?: selectedTargetId?.let { setOf(it) }.orEmpty()
            for (targetId in idsToOutline) {
                val selectedObj = objects.firstOrNull { it.id == targetId && !it.isDestroyed } ?: continue
                val dx = selectedObj.position.x - engine.player.position.x
                val dz = selectedObj.position.z - engine.player.position.z
                if (dx * dx + dz * dz > worldRenderDistanceSquared) continue
                renderSelectionOutline(
                    selectedObj.position.x,
                    selectedObj.position.y,
                    selectedObj.position.z,
                    selectedObj.size.x,
                    selectedObj.size.y,
                    selectedObj.size.z
                )
            }
        }

        // Render the actual model in world space while dragging, not just a Compose card/icon.
        // Model parsing happens on Dispatchers.IO; this render loop only consumes the finished mesh.
        if (isSpectatorEditingActive) {
            hoverPreviewPosition?.let { pos ->
                renderPlacementHoverModel(pos.x, pos.y, pos.z, hoverPreviewModelId)
                renderPlacementHoverPreview(pos.x, pos.y, pos.z, hoverPreviewModelId)
            }
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
    }

    private fun renderSelectionOutline(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        sizeX: Float,
        sizeY: Float,
        sizeZ: Float
    ) {
        val time = (System.currentTimeMillis() % 2000L) / 2000.0f
        val pulse = 0.85f + 0.15f * kotlin.math.sin(time * Math.PI * 2.0).toFloat()
        // Vibrant neon electric cyan/blue outline
        val cyanColor = floatArrayOf(0.12f * pulse, 0.78f * pulse, 1.0f, 1.0f)

        GLES20.glLineWidth(6.0f)
        GLES20.glUniform1f(uHitFlashLoc, 0f)
        GLES20.glUniform4fv(uColorLoc, 1, cyanColor, 0)

        // 1. Draw glowing ground rectangular footprint (Genshin blue selection box on ground)
        wireCubeVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, wireCubeVertices)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, centerY - (sizeY * 0.5f) + 0.04f, centerZ)
        Matrix.scaleM(modelMatrix, 0, sizeX + 0.35f, 0.06f, sizeZ + 0.35f)
        updateMVP()
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)

        // 2. Draw 3D wireframe bounding box edges
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, centerY, centerZ)
        Matrix.scaleM(modelMatrix, 0, sizeX + 0.15f, sizeY + 0.15f, sizeZ + 0.15f)
        updateMVP()
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)

        // 3. Floating top cyan marker arrow above object (Genshin floating indicator)
        crystalVertices.position(0)
        crystalNormals.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, crystalVertices)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, crystalNormals)

        val markerY = centerY + (sizeY * 0.5f) + 0.85f + 0.15f * kotlin.math.sin(time * Math.PI * 2.0).toFloat()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, markerY, centerZ)
        Matrix.scaleM(modelMatrix, 0, 0.45f, 0.75f, 0.45f)
        updateMVP()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 24)
    }

    private fun renderPlacementHoverModel(
        groundX: Float,
        groundY: Float,
        groundZ: Float,
        modelId: String?
    ) {
        val mesh = modelId?.let { TeapotModelRegistry.getModel(it) } ?: return
        // Until the real mesh finishes loading, leave the lightweight wireframe placement marker.
        if (mesh.isLazyLoaded) return

        val sizeX = mesh.boundsSize.x.coerceIn(0.2f, 40f)
        val sizeY = mesh.boundsSize.y.coerceIn(0.2f, 40f)
        val sizeZ = mesh.boundsSize.z.coerceIn(0.2f, 40f)
        GLES20.glUseProgram(worldProgramId)
        applyAppearanceUniforms()
        applyWorldVisualSettings()
        GLES20.glUniform3fv(uLightDirLoc, 1, lightDirection, 0)
        GLES20.glUniform3fv(uCameraPosLoc, 1, cameraPosArray, 0)
        GLES20.glUniform1f(uHitFlashLoc, 0f)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aNormalLoc)

        // Parsed model vertices are centred around their bounds; place the mesh feet on the hit
        // surface and use its native dimensions, matching the real placement path.
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, groundX, groundY + sizeY * 0.5f, groundZ)
        Matrix.scaleM(
            modelMatrix, 0,
            sizeX / mesh.boundsSize.x.coerceAtLeast(0.001f),
            sizeY / mesh.boundsSize.y.coerceAtLeast(0.001f),
            sizeZ / mesh.boundsSize.z.coerceAtLeast(0.001f)
        )
        updateMVP()
        mesh.render(
            aPositionLoc, aNormalLoc, uColorLoc, null,
            aTexCoordLoc, uUseTextureLoc, uTextureLoc
        )
    }

    private fun renderPlacementHoverPreview(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        modelId: String?
    ) {
        val time = (System.currentTimeMillis() % 1200L) / 1200.0f
        val pulse = 0.8f + 0.2f * kotlin.math.sin(time * Math.PI * 2.0).toFloat()
        // Vibrant neon emerald green/cyan placement preview
        val greenColor = floatArrayOf(0.12f * pulse, 1.0f * pulse, 0.45f * pulse, 0.95f)

        GLES20.glLineWidth(6.0f)
        GLES20.glUniform1f(uHitFlashLoc, 0f)
        GLES20.glUniform4fv(uColorLoc, 1, greenColor, 0)

        // 1. Placement Bounding Box
        // Use the newly loaded source bounds even if the user's finger has not moved since loading.
        val previewMesh = modelId?.let { TeapotModelRegistry.getModel(it) }
            ?.takeUnless { it.isLazyLoaded }
        val sx = (previewMesh?.boundsSize?.x ?: hoverPreviewSize?.x ?: 2.2f).coerceIn(0.2f, 40f)
        val sy = (previewMesh?.boundsSize?.y ?: hoverPreviewSize?.y ?: 1.6f).coerceIn(0.2f, 40f)
        val sz = (previewMesh?.boundsSize?.z ?: hoverPreviewSize?.z ?: 2.2f).coerceIn(0.2f, 40f)

        wireCubeVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, wireCubeVertices)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, centerY + (sy * 0.5f), centerZ)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        updateMVP()
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)

        // 2. Base Ground Placement Ring
        val ringRadius = kotlin.math.max(sx, sz) * 0.65f + 0.2f
        selectionRingVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, selectionRingVertices)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, centerX, centerY + 0.04f, centerZ)
        Matrix.scaleM(modelMatrix, 0, ringRadius, 1f, ringRadius)
        updateMVP()
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 64)
    }

    data class RayHitResult(
        val objectId: String,
        val hitObject: com.example.game.WorldObject,
        val hitPoint: com.example.model.Vector3,
        val placementPos: com.example.model.Vector3
    )

    fun raycastPlacement(touchX: Float, touchY: Float, excludedId: String? = null): RayHitResult? {
        val w = kotlin.math.max(screenWidth.toFloat(), 1.0f)
        val h = kotlin.math.max(screenHeight.toFloat(), 1.0f)

        val ndcX = (touchX / w) * 2.0f - 1.0f
        val ndcY = 1.0f - (touchY / h) * 2.0f

        val nearPoint = floatArrayOf(ndcX, ndcY, -1.0f, 1.0f)
        val farPoint = floatArrayOf(ndcX, ndcY, 1.0f, 1.0f)

        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)

        synchronized(currentViewProjCopy) {
            Matrix.multiplyMV(nearWorld, 0, currentInvViewProjCopy, 0, nearPoint, 0)
            Matrix.multiplyMV(farWorld, 0, currentInvViewProjCopy, 0, farPoint, 0)
        }

        if (kotlin.math.abs(nearWorld[3]) < 0.0001f || kotlin.math.abs(farWorld[3]) < 0.0001f) return null

        val rayOriginX = nearWorld[0] / nearWorld[3]
        val rayOriginY = nearWorld[1] / nearWorld[3]
        val rayOriginZ = nearWorld[2] / nearWorld[3]

        var rayDirX = (farWorld[0] / farWorld[3]) - rayOriginX
        var rayDirY = (farWorld[1] / farWorld[3]) - rayOriginY
        var rayDirZ = (farWorld[2] / farWorld[3]) - rayOriginZ

        val dirLen = kotlin.math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ)
        if (dirLen > 0.0001f) {
            rayDirX /= dirLen
            rayDirY /= dirLen
            rayDirZ /= dirLen
        }

        var closestDistance = Float.MAX_VALUE
        var bestHit: RayHitResult? = null

        val objects = engine.worldManager.objects
        // While dragging a media plane, use the actual ray hit point instead of snapping it to
        // the top of the hit model. This allows the plane to be positioned through/inside meshes;
        // media themselves remain excluded as placement colliders/targets.
        val movingMediaPlane = excludedId?.let { id -> objects.firstOrNull { it.id == id }?.isMediaPlane } == true
        for (obj in objects) {
            if (obj.isDestroyed || obj.id == excludedId || obj.isMediaPlane) continue
            val pad = 0.45f
            val minX = obj.position.x - (obj.size.x * 0.5f) - pad
            val maxX = obj.position.x + (obj.size.x * 0.5f) + pad
            val minY = obj.position.y - (obj.size.y * 0.5f) - pad
            val maxY = obj.position.y + (obj.size.y * 0.5f) + pad
            val minZ = obj.position.z - (obj.size.z * 0.5f) - pad
            val maxZ = obj.position.z + (obj.size.z * 0.5f) + pad

            val hitDist = rayIntersectsAABB(
                rayOriginX, rayOriginY, rayOriginZ,
                rayDirX, rayDirY, rayDirZ,
                minX, maxX, minY, maxY, minZ, maxZ
            )
            if (hitDist != null && hitDist < closestDistance) {
                closestDistance = hitDist
                val hitX = rayOriginX + rayDirX * hitDist
                val hitY = rayOriginY + rayDirY * hitDist
                val hitZ = rayOriginZ + rayDirZ * hitDist

                val topSurfaceY = obj.position.y + (obj.size.y * 0.5f)
                val clampedX = hitX.coerceIn(obj.position.x - obj.size.x * 0.45f, obj.position.x + obj.size.x * 0.45f)
                val clampedZ = hitZ.coerceIn(obj.position.z - obj.size.z * 0.45f, obj.position.z + obj.size.z * 0.45f)
                val placementPosition = if (movingMediaPlane) {
                    // Do not surface-snap or clamp a media plane to the model's top footprint.
                    com.example.model.Vector3(hitX, hitY, hitZ)
                } else {
                    com.example.model.Vector3(clampedX, topSurfaceY, clampedZ)
                }

                bestHit = RayHitResult(
                    objectId = obj.id,
                    hitObject = obj,
                    hitPoint = com.example.model.Vector3(hitX, hitY, hitZ),
                    placementPos = placementPosition
                )
            }
        }

        // Fallback: If no platform or object was hit, intersect with ground plane (y = 0)
        if (bestHit == null && kotlin.math.abs(rayDirY) > 0.0001f) {
            val tGround = (0f - rayOriginY) / rayDirY
            if (tGround > 0f) {
                val gX = (rayOriginX + rayDirX * tGround).coerceIn(-45f, 45f)
                val gZ = (rayOriginZ + rayDirZ * tGround).coerceIn(-45f, 45f)
                val groundObj = com.example.game.WorldObject(
                    id = "ground_plane",
                    name = "Ground",
                    position = com.example.model.Vector3(gX, 0f, gZ),
                    size = com.example.model.Vector3(4f, 0.2f, 4f),
                    color = floatArrayOf(0.35f, 0.55f, 0.35f, 1f),
                    type = com.example.game.ObjectType.PLATFORM
                )
                bestHit = RayHitResult(
                    objectId = "ground_plane",
                    hitObject = groundObj,
                    hitPoint = com.example.model.Vector3(gX, 0f, gZ),
                    placementPos = com.example.model.Vector3(gX, 0f, gZ)
                )
            }
        }

        return bestHit
    }

    fun getObjectScreenPos(objectId: String): androidx.compose.ui.geometry.Offset? {
        val obj = engine.worldManager.objects.find { it.id == objectId } ?: return null
        val worldPos = floatArrayOf(obj.position.x, obj.position.y + (obj.size.y * 0.5f) + 0.6f, obj.position.z, 1.0f)
        val clip = FloatArray(4)
        synchronized(currentViewProjCopy) {
            Matrix.multiplyMV(clip, 0, currentViewProjCopy, 0, worldPos, 0)
        }
        if (clip[3] <= 0.2f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        if (ndcX < -1.1f || ndcX > 1.1f || ndcY < -1.1f || ndcY > 1.1f) return null

        val screenX = (ndcX + 1.0f) * 0.5f * screenWidth
        val screenY = (1.0f - ndcY) * 0.5f * screenHeight
        return androidx.compose.ui.geometry.Offset(screenX, screenY)
    }

    /**
     * Accurate 3D Ray-AABB test: Tests if a 3D ray intersects an axis-aligned bounding box.
     * Returns distance t >= 0 along the ray if hit, or null if no hit.
     */
    private fun rayIntersectsAABB(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        minZ: Float, maxZ: Float
    ): Float? {
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        // X slab
        if (kotlin.math.abs(dx) > 0.00001f) {
            val t1 = (minX - ox) / dx
            val t2 = (maxX - ox) / dx
            val tNear = kotlin.math.min(t1, t2)
            val tFar = kotlin.math.max(t1, t2)
            tMin = kotlin.math.max(tMin, tNear)
            tMax = kotlin.math.min(tMax, tFar)
            if (tMin > tMax) return null
        } else if (ox < minX || ox > maxX) {
            return null
        }

        // Y slab
        if (kotlin.math.abs(dy) > 0.00001f) {
            val t1 = (minY - oy) / dy
            val t2 = (maxY - oy) / dy
            val tNear = kotlin.math.min(t1, t2)
            val tFar = kotlin.math.max(t1, t2)
            tMin = kotlin.math.max(tMin, tNear)
            tMax = kotlin.math.min(tMax, tFar)
            if (tMin > tMax) return null
        } else if (oy < minY || oy > maxY) {
            return null
        }

        // Z slab
        if (kotlin.math.abs(dz) > 0.00001f) {
            val t1 = (minZ - oz) / dz
            val t2 = (maxZ - oz) / dz
            val tNear = kotlin.math.min(t1, t2)
            val tFar = kotlin.math.max(t1, t2)
            tMin = kotlin.math.max(tMin, tNear)
            tMax = kotlin.math.min(tMax, tFar)
            if (tMin > tMax) return null
        } else if (oz < minZ || oz > maxZ) {
            return null
        }

        return if (tMax >= 0f) kotlin.math.max(0f, tMin) else null
    }

    /**
     * Returns every live world object whose projected 3D bounds intersect the drag-selection box.
     * Coordinates must use the same pixel space as screenWidth/screenHeight.
     */
    fun findObjectsInScreenRect(x1: Float, y1: Float, x2: Float, y2: Float): Set<String> {
        val left = min(x1, x2)
        val right = max(x1, x2)
        val top = min(y1, y2)
        val bottom = max(y1, y2)
        val w = max(screenWidth.toFloat(), 1f)
        val h = max(screenHeight.toFloat(), 1f)
        val viewProjection = FloatArray(16)
        synchronized(currentViewProjCopy) {
            System.arraycopy(currentViewProjCopy, 0, viewProjection, 0, 16)
        }
        val model = FloatArray(16)
        val mvp = FloatArray(16)
        val vertex = FloatArray(4)
        val clip = FloatArray(4)
        val selected = LinkedHashSet<String>()
        val objects = engine.worldManager.objects
        for (obj in objects) {
            if (obj.isDestroyed) continue
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, obj.position.x, obj.position.y, obj.position.z)
            Matrix.rotateM(model, 0, obj.rotationXDegrees, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, obj.rotationYDegrees, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, obj.rotationZDegrees, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, obj.size.x, obj.size.y, obj.size.z)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)

            var minScreenX = Float.POSITIVE_INFINITY
            var maxScreenX = Float.NEGATIVE_INFINITY
            var minScreenY = Float.POSITIVE_INFINITY
            var maxScreenY = Float.NEGATIVE_INFINITY
            var projectedAnyCorner = false
            for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
                vertex[0] = if (ix == 0) -0.5f else 0.5f
                vertex[1] = if (iy == 0) -0.5f else 0.5f
                vertex[2] = if (iz == 0) -0.5f else 0.5f
                vertex[3] = 1f
                Matrix.multiplyMV(clip, 0, mvp, 0, vertex, 0)
                if (clip[3] <= 0.001f) continue
                val nx = clip[0] / clip[3]
                val ny = clip[1] / clip[3]
                val sx = (nx + 1f) * 0.5f * w
                val sy = (1f - ny) * 0.5f * h
                minScreenX = min(minScreenX, sx)
                maxScreenX = max(maxScreenX, sx)
                minScreenY = min(minScreenY, sy)
                maxScreenY = max(maxScreenY, sy)
                projectedAnyCorner = true
            }
            if (!projectedAnyCorner) continue
            val intersects = maxScreenX >= left && minScreenX <= right &&
                maxScreenY >= top && minScreenY <= bottom
            if (intersects) selected += obj.id
        }
        return selected
    }

    /**
     * Accurately selects a world object or character clicked at screen coordinates (touchX, touchY).
     * 1. Shoots an unprojected 3D ray through the camera frustum into the 3D world.
     * 2. Tests AABB intersection with all objects; picks the closest object in front of the camera.
     * 3. Fallback: Depth-sorted screen-space proximity if tapped near the edge.
     */
    fun findObjectAtScreenPos(touchX: Float, touchY: Float): String? {
        val w = kotlin.math.max(screenWidth.toFloat(), 1.0f)
        val h = kotlin.math.max(screenHeight.toFloat(), 1.0f)

        // Convert touch pixels to Normalized Device Coordinates (NDC)
        val ndcX = (touchX / w) * 2.0f - 1.0f
        val ndcY = 1.0f - (touchY / h) * 2.0f

        val nearPoint = floatArrayOf(ndcX, ndcY, -1.0f, 1.0f)
        val farPoint = floatArrayOf(ndcX, ndcY, 1.0f, 1.0f)

        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)

        synchronized(currentViewProjCopy) {
            Matrix.multiplyMV(nearWorld, 0, currentInvViewProjCopy, 0, nearPoint, 0)
            Matrix.multiplyMV(farWorld, 0, currentInvViewProjCopy, 0, farPoint, 0)
        }

        if (kotlin.math.abs(nearWorld[3]) < 0.0001f || kotlin.math.abs(farWorld[3]) < 0.0001f) return null

        val rayOriginX = nearWorld[0] / nearWorld[3]
        val rayOriginY = nearWorld[1] / nearWorld[3]
        val rayOriginZ = nearWorld[2] / nearWorld[3]

        var rayDirX = (farWorld[0] / farWorld[3]) - rayOriginX
        var rayDirY = (farWorld[1] / farWorld[3]) - rayOriginY
        var rayDirZ = (farWorld[2] / farWorld[3]) - rayOriginZ

        val dirLen = kotlin.math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ)
        if (dirLen > 0.0001f) {
            rayDirX /= dirLen
            rayDirY /= dirLen
            rayDirZ /= dirLen
        }

        var closestDistance = Float.MAX_VALUE
        var bestId: String? = null

        // 1. Check Player Avatar
        val player = engine.player
        val pHit = rayIntersectsAABB(
            rayOriginX, rayOriginY, rayOriginZ,
            rayDirX, rayDirY, rayDirZ,
            player.position.x - 0.7f, player.position.x + 0.7f,
            player.position.y - 0.2f, player.position.y + 2.2f,
            player.position.z - 0.7f, player.position.z + 0.7f
        )
        if (pHit != null && pHit < closestDistance) {
            closestDistance = pHit
            bestId = "player_character"
        }

        // 2. Check World Objects (Platforms, Dummies, Crates, Crystals, Drones)
        val objects = engine.worldManager.objects
        for (obj in objects) {
            if (obj.isDestroyed) continue
            // Media planes are selectable editor overlays, not volumetric colliders. Use tight
            // bounds for them so they can be positioned inside other models without creating a
            // large invisible box. Keep the forgiving bounds for ordinary world objects.
            val pad = if (obj.isMediaPlane) 0f else 0.35f
            val halfX: Float
            val halfZ: Float
            if (obj.isMediaPlane) {
                val rotation = Math.toRadians(obj.rotationYDegrees.toDouble())
                val cosine = kotlin.math.abs(kotlin.math.cos(rotation)).toFloat()
                val sine = kotlin.math.abs(kotlin.math.sin(rotation)).toFloat()
                halfX = cosine * obj.size.x * 0.5f + sine * obj.size.z * 0.5f
                halfZ = sine * obj.size.x * 0.5f + cosine * obj.size.z * 0.5f
            } else {
                halfX = obj.size.x * 0.5f
                halfZ = obj.size.z * 0.5f
            }
            val minX = obj.position.x - halfX - pad
            val maxX = obj.position.x + halfX + pad
            val minY = obj.position.y - (obj.size.y * 0.5f) - pad
            val maxY = obj.position.y + (obj.size.y * 0.5f) + pad
            val minZ = obj.position.z - halfZ - pad
            val maxZ = obj.position.z + halfZ + pad

            val hit = rayIntersectsAABB(
                rayOriginX, rayOriginY, rayOriginZ,
                rayDirX, rayDirY, rayDirZ,
                minX, maxX, minY, maxY, minZ, maxZ
            )
            if (hit != null && hit < closestDistance) {
                closestDistance = hit
                bestId = obj.id
            }
        }

        if (bestId != null) {
            return bestId
        }

        // 3. Fallback: Proximity in Screen Space using actual View-Projection matrix
        var bestScreenDistSq = 90f * 90f
        var bestScreenDepth = Float.MAX_VALUE
        val objPos = FloatArray(4)
        val clip = FloatArray(4)

        for (obj in objects) {
            if (obj.isDestroyed) continue
            objPos[0] = obj.position.x
            objPos[1] = obj.position.y
            objPos[2] = obj.position.z
            objPos[3] = 1.0f

            synchronized(currentViewProjCopy) {
                Matrix.multiplyMV(clip, 0, currentViewProjCopy, 0, objPos, 0)
            }
            if (clip[3] <= 0.2f) continue // Behind camera

            val pNdcX = clip[0] / clip[3]
            val pNdcY = clip[1] / clip[3]
            if (pNdcX < -1.1f || pNdcX > 1.1f || pNdcY < -1.1f || pNdcY > 1.1f) continue

            val pScreenX = (pNdcX + 1.0f) * 0.5f * w
            val pScreenY = (1.0f - pNdcY) * 0.5f * h

            val dx = touchX - pScreenX
            val dy = touchY - pScreenY
            val distSq = dx * dx + dy * dy
            if (distSq < bestScreenDistSq) {
                bestScreenDistSq = distSq
                bestId = obj.id
            }
        }

        return bestId
    }

    private fun renderTrainingDummy(dummy: WorldObject) {
        Matrix.setIdentityM(compositeParentTransform, 0)
        Matrix.translateM(compositeParentTransform, 0, dummy.position.x, dummy.position.y, dummy.position.z)
        Matrix.rotateM(compositeParentTransform, 0, dummy.rotationXDegrees, 1f, 0f, 0f)
        Matrix.rotateM(compositeParentTransform, 0, dummy.rotationYDegrees, 0f, 1f, 0f)
        Matrix.rotateM(compositeParentTransform, 0, dummy.rotationZDegrees, 0f, 0f, 1f)
        Matrix.translateM(compositeParentTransform, 0, -dummy.position.x, -dummy.position.y, -dummy.position.z)
        compositeTransformActive = true
        cubeVertices.position(0)
        cubeNormals.position(0)
        cubeIndices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

        // 1. Pole
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, dummy.position.x, dummy.position.y - 0.5f, dummy.position.z)
        Matrix.scaleM(modelMatrix, 0, 0.25f, 1.0f, 0.25f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.45f, 0.3f, 0.15f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

        // 2. Torso
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, dummy.position.x, dummy.position.y + 0.15f, dummy.position.z)
        Matrix.scaleM(modelMatrix, 0, 0.85f, 0.95f, 0.45f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, dummy.color, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

        // 3. Head
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, dummy.position.x, dummy.position.y + 0.85f, dummy.position.z)
        Matrix.scaleM(modelMatrix, 0, 0.55f, 0.55f, 0.55f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.95f, 0.72f, 0.45f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

        // 4. Target Bullseye
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, dummy.position.x, dummy.position.y + 0.15f, dummy.position.z + 0.24f)
        Matrix.scaleM(modelMatrix, 0, 0.42f, 0.42f, 0.05f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.95f, 0.15f, 0.15f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        compositeTransformActive = false
    }

    private fun renderObjectHealthBar(obj: WorldObject) {
        val barY = obj.position.y + (obj.size.y * 0.5f) + 0.45f
        val barWidth = 1.2f
        val barHeight = 0.12f
        val hpPercent = (obj.health / obj.maxHealth).coerceIn(0.04f, 1f)

        cubeVertices.position(0)
        cubeNormals.position(0)
        cubeIndices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

        // Background
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, obj.position.x, barY, obj.position.z)
        Matrix.rotateM(modelMatrix, 0, engine.camera.yaw, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, barWidth, barHeight, 0.02f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.1f, 0.1f, 0.1f, 0.85f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

        // Fill bar (strictly clamped > 0.04f to prevent singular matrix)
        val fillWidth = (barWidth * hpPercent).coerceIn(0.04f, barWidth)
        val fillOffsetX = - (barWidth - fillWidth) * 0.5f

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, obj.position.x, barY, obj.position.z)
        Matrix.rotateM(modelMatrix, 0, engine.camera.yaw, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, fillOffsetX, 0f, 0.015f)
        Matrix.scaleM(modelMatrix, 0, fillWidth, barHeight * 0.8f, 0.02f)
        updateMVP()

        val r = (1.0f - hpPercent).coerceIn(0f, 1f)
        val g = hpPercent.coerceIn(0f, 1f)
        GLES20.glUniform4f(uColorLoc, r, g, 0.1f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
    }

    private fun renderDashTrails() {
        val ghosts = engine.player.ghosts
        GLES20.glUseProgram(worldProgramId)

        GLES20.glUniform3fv(uLightDirLoc, 1, lightDirection, 0)
        GLES20.glUniform3fv(uCameraPosLoc, 1, cameraPosArray, 0)
        GLES20.glUniform1f(uHitFlashLoc, 0f)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        cubeVertices.position(0)
        cubeNormals.position(0)
        cubeIndices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

        for (i in ghosts.indices) {
            val g = ghosts[i]
            if (!g.active || g.alpha <= 0.01f) continue

            ghostColor[3] = g.alpha * 0.45f
            GLES20.glUniform4fv(uColorLoc, 1, ghostColor, 0)

            // Render ghost torso
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, g.position.x, g.position.y + 1.15f, g.position.z)
            Matrix.rotateM(modelMatrix, 0, g.rotationY, 0f, 1f, 0f)
            Matrix.rotateM(modelMatrix, 0, 35f, 1f, 0f, 0f)
            Matrix.scaleM(modelMatrix, 0, 1.0f, 1.2f, 0.5f)
            updateMVP()
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

            // Render ghost head
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, g.position.x, g.position.y + 2.05f, g.position.z)
            Matrix.rotateM(modelMatrix, 0, g.rotationY, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, 0.65f, 0.65f, 0.65f)
            updateMVP()
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
    }

    private fun renderRobloxCharacter(p: RobloxCharacter) {
        GLES20.glUseProgram(worldProgramId)

        GLES20.glUniform3fv(uLightDirLoc, 1, lightDirection, 0)
        GLES20.glUniform3fv(uCameraPosLoc, 1, cameraPosArray, 0)
        GLES20.glUniform1f(uHitFlashLoc, 0f)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        cubeVertices.position(0)
        cubeNormals.position(0)
        cubeIndices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormals)

        stackIndex = 0

        // Check if player avatar model was swapped
        val swappedPlayerMesh = ModelSwapManager.getSwappedMesh("player_character")
        if (swappedPlayerMesh != null) {
            // GLBModelParser recentres character geometry at its bounds centre. Lift the model by
            // half its scaled height so the feet meet the physics ground rather than sinking below it.
            val characterScale = 0.8f
            val animationTime = System.nanoTime() / 1_000_000_000.0
            val horizontalSpeed = sqrt(p.velocity.x * p.velocity.x + p.velocity.z * p.velocity.z)
            val isRunning = p.actionState.name == "DASHING" || horizontalSpeed >= 5f
            val isWalking = p.actionState.name == "WALKING" || horizontalSpeed >= 0.2f
            val proceduralBob = when {
                isRunning -> abs(sin(animationTime * 12.0)).toFloat() * 0.065f
                isWalking -> abs(sin(animationTime * 8.0)).toFloat() * 0.04f
                else -> sin(animationTime * 1.8).toFloat() * 0.018f
            }
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(
                modelMatrix, 0,
                p.position.x,
                p.position.y + swappedPlayerMesh.boundsSize.y * characterScale * 0.5f + proceduralBob,
                p.position.z
            )
            Matrix.rotateM(modelMatrix, 0, p.rotationY, 0f, 1f, 0f)
            if (isRunning) Matrix.rotateM(modelMatrix, 0, 7f, 1f, 0f, 0f)
            Matrix.scaleM(modelMatrix, 0, characterScale, characterScale, characterScale)
            updateMVP()
            swappedPlayerMesh.render(
                aPositionLoc, aNormalLoc, uColorLoc,
                tintColor = null,
                aTexCoordLoc = aTexCoordLoc,
                uUseTextureLoc = uUseTextureLoc,
                uTextureLoc = uTextureLoc
            )
            return
        }

        // The packaged GLB avatar is loaded asynchronously at startup. Do not briefly draw
        // the legacy procedural/Roblox character while the requested GLB is being parsed.
        // The player remains fully simulated and becomes visible as soon as the GLB swap is ready.
        return

        // Base Root Position & Yaw Rotation (legacy fallback retained below for easy rollback)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, p.position.x, p.position.y + p.torsoBob, p.position.z)
        Matrix.rotateM(modelMatrix, 0, p.rotationY, 0f, 1f, 0f)

        // --- 1. TORSO ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, 1.15f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.torsoTiltX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.torsoRotY, 0f, 1f, 0f)

        pushMatrix()
        Matrix.scaleM(modelMatrix, 0, 1.0f, 1.2f, 0.5f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, torsoColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Torso front emblem
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, 0.1f, 0.26f)
        Matrix.scaleM(modelMatrix, 0, 0.35f, 0.35f, 0.04f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 1.0f, 1.0f, 1.0f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // --- 2. HEAD ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, 0.95f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.68f, 0.68f, 0.68f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, headColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)

        // Left Eye
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, -0.2f, 0.08f, 0.52f)
        Matrix.scaleM(modelMatrix, 0, 0.12f, 0.22f, 0.05f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Right Eye
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0.2f, 0.08f, 0.52f)
        Matrix.scaleM(modelMatrix, 0, 0.12f, 0.22f, 0.05f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Smile
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, -0.2f, 0.52f)
        Matrix.scaleM(modelMatrix, 0, 0.32f, 0.08f, 0.05f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()
        popMatrix() // End Head

        // --- 3. LEFT ARM ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, -0.75f, 0.55f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.leftArmRotX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.leftArmRotZ, 0f, 0f, 1f)
        Matrix.translateM(modelMatrix, 0, 0f, -0.55f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.45f, 1.15f, 0.45f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, limbsColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // --- 4. RIGHT ARM & SWORD ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0.75f, 0.55f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.rightArmRotX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.rightArmRotY, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.rightArmRotZ, 0f, 0f, 1f)
        Matrix.translateM(modelMatrix, 0, 0f, -0.55f, 0f)

        pushMatrix()
        Matrix.scaleM(modelMatrix, 0, 0.45f, 1.15f, 0.45f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, limbsColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Sword attached to hand
        renderSwordInHand(p.swordTrailAlpha)
        popMatrix() // End Right Arm

        popMatrix() // End Torso

        // --- 5. LEFT LEG ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, -0.27f, 0.55f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.leftLegRotX, 1f, 0f, 0f)
        Matrix.translateM(modelMatrix, 0, 0f, -0.55f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.46f, 1.15f, 0.46f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, pantsColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // --- 6. RIGHT LEG ---
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0.27f, 0.55f, 0f)
        Matrix.rotateM(modelMatrix, 0, p.rightLegRotX, 1f, 0f, 0f)
        Matrix.translateM(modelMatrix, 0, 0f, -0.55f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.46f, 1.15f, 0.46f)
        updateMVP()
        GLES20.glUniform4fv(uColorLoc, 1, pantsColor, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
    }

    private fun renderSwordInHand(trailAlpha: Float) {
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, -0.5f, 0.2f)
        Matrix.rotateM(modelMatrix, 0, 75f, 1f, 0f, 0f)

        // Handle
        pushMatrix()
        Matrix.scaleM(modelMatrix, 0, 0.12f, 0.45f, 0.12f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 0.2f, 0.2f, 0.25f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Crossguard
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, 0.22f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.45f, 0.1f, 0.18f)
        updateMVP()
        GLES20.glUniform4f(uColorLoc, 1.0f, 0.84f, 0.0f, 1.0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        // Glowing Katana Blade
        pushMatrix()
        Matrix.translateM(modelMatrix, 0, 0f, 0.85f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.1f, 1.25f, 0.04f)
        updateMVP()
        val blade = if (trailAlpha > 0.1f) swordSlashColor else swordBladeColor
        GLES20.glUniform4fv(uColorLoc, 1, blade, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        popMatrix()

        popMatrix()
    }

    private fun renderParticles() {
        val pool = engine.particleSystem.pool
        GLES20.glUseProgram(particleProgramId)
        GLES20.glEnableVertexAttribArray(aPartPositionLoc)
        cubeVertices.position(0)
        cubeIndices.position(0)
        GLES20.glVertexAttribPointer(aPartPositionLoc, 3, GLES20.GL_FLOAT, false, 0, cubeVertices)

        for (i in 0 until ParticleSystem.MAX_PARTICLES) {
            val p = pool[i]
            if (!p.active) continue
            val alpha = p.alpha
            if (alpha <= 0.01f) continue

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, p.x, p.y, p.z)
            Matrix.scaleM(modelMatrix, 0, p.size, p.size, p.size)

            Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

            GLES20.glUniformMatrix4fv(uPartMVPMatrixLoc, 1, false, mvpMatrix, 0)
            tempColor[0] = p.r
            tempColor[1] = p.g
            tempColor[2] = p.b
            tempColor[3] = alpha
            GLES20.glUniform4fv(uPartColorLoc, 1, tempColor, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices)
        }

        GLES20.glDisableVertexAttribArray(aPartPositionLoc)
    }

    companion object {
        private const val ROTATION_RING_SEGMENTS = 96
        private const val ROTATION_GIZMO_HIT_RADIUS_PX = 42f
    }
}
