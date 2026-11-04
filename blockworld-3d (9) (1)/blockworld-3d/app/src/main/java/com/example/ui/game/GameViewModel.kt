package com.example.ui.game

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.game.*
import com.example.sfx.GameAudioManager
import com.example.model.Vector3
import com.example.teapot.ModelSwapManager
import com.example.teapot.GlbSceneExporter
import com.example.teapot.SceneExportInstance
import com.example.teapot.TeapotModelRegistry
import com.example.media.ImportedMedia
import com.example.media.MediaKind
import com.example.media.MediaRepository
import com.example.media.MediaWorldStore
import com.example.graphics.RotationAxis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
data class PendingPlacementState(
    val objectId: String,
    val objectName: String,
    val originalPosition: Vector3,
    val currentPosition: Vector3,
    val isNewlyAdded: Boolean = false,
    val modelId: String? = null,
    val originalRotationYDegrees: Float = 0f
)

data class GameUIState(
    val playerCoords: Vector3 = Vector3(0f, 1.5f, 0f),
    val nearestTargetInfo: String? = null,
    val nearestTargetDistance: Float = 0f,
    val playerHp: Float = 100f,
    val playerMaxHp: Float = 100f,
    val score: Int = 0,
    val hitCombo: Int = 0,
    val dashCooldownPercent: Float = 0f,
    val compassAngle: Float = 0f,
    val currentZoom: Float = 8.5f,
    val isSettingsOpen: Boolean = false,
    val isSpectatorMode: Boolean = false,
    val isCleanSpectatorView: Boolean = false,
    val isRotationGizmoVisible: Boolean = false,
    val isFirstPersonMode: Boolean = false,
    val settings: GameSettings = GameSettings(),
    val isHdrSkyAvailable: Boolean = true,
    // Genshin Impact Teapot Placement System State
    val isJoystickActive: Boolean = false,
    val selectedTargetId: String = "",
    val selectedTargetName: String = "Nothing selected",
    val selectedTargetIds: Set<String> = emptySet(),
    val teapotAreaName: String = "Nodoka Terrace",
    val isMultiSelect: Boolean = false,
    val saveToastMessage: String? = null,
    val lastSwapTimestamp: Long = 0L,
    // Move / Drag & Placement Confirmation State (Requirement 1 & 2)
    val pendingPlacement: PendingPlacementState? = null,
    val isMovingObject: Boolean = false,
    val isEditingObject: Boolean = false,
    val isVerticalAdjustment: Boolean = false,
    val activeObjectHeight: Float = 14.0f,
    val activeObjectRotation: Float = 0f,
    val activeObjectDescription: String = "Can be placed side by side and can be stacked vertically.",
    // Exit Spectator Confirmation Dialog State (Requirement 3)
    val showExitConfirmationDialog: Boolean = false,
    // Animation settings shown in the compact upper-left control.
    val animationMenuExpanded: Boolean = false,
    val availableAnimations: List<String> = emptyList(),
    val activeAnimationName: String? = null,
    val animationEnabled: Boolean = false,
    val autoAnimationEnabled: Boolean = false,
    val supportsSkeletalAnimation: Boolean = false,
    val modelLoadingName: String? = null,
    val modelLoadingProgress: Int = 0,
    val modelLoadingStage: String = "",
    // Imported image/video library displayed by the Media category in the furnishing drawer.
    val mediaItems: List<ImportedMedia> = emptyList()
)

class GameViewModel : ViewModel() {

    lateinit var engine: GameEngine
        private set

    private val _uiState = MutableStateFlow(GameUIState())
    val uiState: StateFlow<GameUIState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var mediaRepository: MediaRepository? = null
    private var vibrator: Vibrator? = null
    private var lastHapticTimestamp = 0L

    private data class SpectatorEditSnapshot(
        val worldObjects: List<WorldObject>,
        val activeSwaps: Map<String, String>,
        val placedObjects: List<ModelSwapManager.PlacedObjectData>,
        val selectedTargetId: String,
        val selectedTargetName: String
    )
    private var spectatorEditSnapshot: SpectatorEditSnapshot? = null
    // World-space offsets for a multi-selected group while its anchor is being dragged.
    private var activeGroupOffsets: Map<String, Vector3> = emptyMap()
    private var activeGroupOriginalPositions: Map<String, Vector3> = emptyMap()
    private var spectatorEditGeneration: Long = 0L
    private data class DeferredModelAction(
        val generation: Long,
        val action: (com.example.teapot.Custom3DMesh) -> Unit
    )
    private val modelLoadsInFlight = mutableSetOf<String>()
    private val waitingModelActions = mutableMapOf<String, MutableList<DeferredModelAction>>()

    private val teapotAreas = listOf("Nodoka Terrace", "Floating Isle", "Emerald Peak", "Kasumi Court")
    private var currentAreaIndex = 0

    fun initialize(context: Context, gameEngine: GameEngine) {
        appContext = context.applicationContext
        mediaRepository = MediaRepository(context.applicationContext)
        engine = gameEngine

        // Refresh the media library independently of model parsing so an image/video import can
        // never block initialization of the existing GLB/model pipeline.
        viewModelScope.launch {
            try {
                val mediaItems = mediaRepository?.list().orEmpty()
                _uiState.value = _uiState.value.copy(mediaItems = mediaItems)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "Could not read imported media library: ${e.message ?: "unknown error"}"
                )
            }
        }

        // Load persisted settings from SharedPreferences
        val savedSettings = GameSettingsStorage.loadSettings(context)
        engine.settings = savedSettings
        _uiState.value = _uiState.value.copy(settings = savedSettings)

        // Parse previously imported models off the UI thread. Detailed meshes and textures can be
        // large; doing this inside initialize() previously froze the game or risked an ANR.
        viewModelScope.launch {
            try {
                val appContext = context.applicationContext
                // Migrate old world edits before reading the selected player model. The migration
                // clears stale object edits but now deliberately retains player_character's swap.
                val migrationPrefs = appContext.getSharedPreferences(
                    "blockworld_world_migrations", Context.MODE_PRIVATE
                )
                val firstGlbWorldLaunch = !migrationPrefs.getBoolean("glb_spawn_world_v1", false)
                if (firstGlbWorldLaunch) {
                    ModelSwapManager.clearSavedWorldForMigration(appContext)
                    migrationPrefs.edit().putBoolean("glb_spawn_world_v1", true).apply()
                }

                val savedPlayerModelId = ModelSwapManager.getSavedPlayerModelId(appContext)
                withContext(Dispatchers.IO) {
                    TeapotModelRegistry.initialize(appContext, savedPlayerModelId) { modelName, percent, stage ->
                        _uiState.value = _uiState.value.copy(
                            modelLoadingName = modelName,
                            modelLoadingProgress = percent.coerceIn(0, 100),
                            modelLoadingStage = stage
                        )
                    }
                }
                _uiState.value = _uiState.value.copy(
                    modelLoadingName = null,
                    modelLoadingProgress = 0,
                    modelLoadingStage = ""
                )

                // Restore saved model swaps and any non-built-in user placements.
                ModelSwapManager.loadSwapsFromStorage(appContext, engine.worldManager)

                // If the user's selected character was removed or failed to parse, safely fall back
                // to the packaged Skirk GLB. Otherwise keep the saved model ID and render it directly.
                val selectedCharacterId = ModelSwapManager.getActiveSwapModelId("player_character")
                if (selectedCharacterId.isNullOrBlank() || TeapotModelRegistry.getModel(selectedCharacterId) == null) {
                    if (TeapotModelRegistry.getModel("genshin_skirk_character") != null) {
                        ModelSwapManager.applySwap("player_character", "genshin_skirk_character", engine.worldManager)
                    } else {
                        ModelSwapManager.removeSwap("player_character")
                    }
                }

                // Built-in object slots always return to their authored world placement, shape and
                // packaged model. This must not overwrite the player's saved character selection.
                if (TeapotModelRegistry.getModel("flat_surface_platform") != null) {
                    ModelSwapManager.applySwap("platform_spawn", "flat_surface_platform", engine.worldManager)
                }
                if (TeapotModelRegistry.getModel("sample_cube_glb") != null) {
                    ModelSwapManager.applySwap("sample_cube", "sample_cube_glb", engine.worldManager)
                }
                if (TeapotModelRegistry.getModel("sample_green_cube_glb") != null) {
                    ModelSwapManager.applySwap("sample_green_cube", "sample_green_cube_glb", engine.worldManager)
                }

                // Restore canonical positions and dimensions after any persisted swap was read.
                engine.worldManager.objects.find { it.id == "platform_spawn" }?.let { obj ->
                    obj.reset(); obj.position.set(0f, 0f, 0f); obj.size.set(16f, 0.1f, 16f)
                }
                engine.worldManager.objects.find { it.id == "sample_cube" }?.let { obj ->
                    obj.reset(); obj.position.set(3f, 0.8f, -3f); obj.size.set(1.5f, 1.5f, 1.5f)
                }
                engine.worldManager.objects.find { it.id == "sample_green_cube" }?.let { obj ->
                    obj.reset(); obj.position.set(-3f, 0.8f, -3f); obj.size.set(1.5f, 1.5f, 1.5f)
                }
                // These are fixed built-in scene slots, not user-placed furnishings. Remove any
                // stale placement records so an old saved transform cannot leak into a later run.
                ModelSwapManager.removePlacedObject("platform_spawn")
                ModelSwapManager.removePlacedObject("sample_cube")
                ModelSwapManager.removePlacedObject("sample_green_cube")
                // Restore user-edited transforms only after built-in slots have been normalized.
                BuiltInWorldTransformStore.restore(appContext, engine.worldManager.objects)

                // Media placements have their own store, so they coexist with (and never rewrite)
                // the established model-swap/GLB persistence format.
                MediaWorldStore.restore(appContext, engine.worldManager)
                _uiState.value = _uiState.value.copy(
                    mediaItems = mediaRepository?.list().orEmpty()
                )

                // GameEngine creates a new player at spawn; never teleport asynchronously after
                // parsing finishes because the player may already have started moving.
                ModelSwapManager.saveSwapsToStorage(appContext)
                _uiState.value = _uiState.value.copy(lastSwapTimestamp = System.currentTimeMillis())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "Some saved 3D models could not be loaded: ${e.message ?: "unknown error"}"
                )
            }
        }

        try {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {}

        // Configure callbacks with safe throttling
        engine.onHitTarget = { _, _, isCrit ->
            triggerHapticThrottled(if (isCrit) 50 else 30, minIntervalMs = 70)
        }
        engine.onObjectDestroyed = {
            triggerHapticThrottled(80, minIntervalMs = 100)
        }
        engine.onJump = {
            triggerHapticThrottled(20, minIntervalMs = 80)
        }
        engine.onDash = {
            triggerHapticThrottled(40, minIntervalMs = 120)
        }
        engine.onBouncePad = {
            triggerHapticThrottled(75, minIntervalMs = 150)
        }

        // Lightweight UI sync loop
        viewModelScope.launch {
            while (true) {
                try {
                    syncUIState()
                } catch (_: Exception) {}
                delay(50)
            }
        }
    }

    private fun syncUIState() {
        val player = engine.player
        val nearest = engine.worldManager.findNearestTarget(player.position)
        val nearestText = nearest?.let {
            val dist = player.position.distanceTo(it.position)
            "${it.name} (${formatCoord(it.position.x)}, ${formatCoord(it.position.y)}, ${formatCoord(it.position.z)}) • ${String.format("%.1fm", dist)}"
        }

        val selectedMeshWithClips = ModelSwapManager.getSwappedMesh(_uiState.value.selectedTargetId)
            ?.takeIf { it.animationNames.isNotEmpty() }
        val animationMesh = selectedMeshWithClips ?: ModelSwapManager.getSwappedMesh("player_character")
        _uiState.value = _uiState.value.copy(
            playerCoords = player.position,
            nearestTargetInfo = nearestText,
            nearestTargetDistance = nearest?.let { player.position.distanceTo(it.position) } ?: 0f,
            playerHp = player.health,
            playerMaxHp = player.maxHealth,
            score = engine.score,
            hitCombo = engine.hitCombo,
            dashCooldownPercent = 0f,
            compassAngle = engine.camera.yaw,
            currentZoom = engine.camera.distance,
            isSpectatorMode = engine.camera.isSpectatorMode,
            isFirstPersonMode = engine.camera.isFirstPerson,
            availableAnimations = animationMesh?.animationNames ?: emptyList(),
            activeAnimationName = animationMesh?.activeAnimationName,
            animationEnabled = animationMesh?.isAnimationEnabled == true,
            autoAnimationEnabled = animationMesh?.isAutoAnimationEnabled == true,
            supportsSkeletalAnimation = animationMesh?.supportsSkeletalAnimation == true,
        )
    }

    private fun formatCoord(value: Float): String = String.format("%.1f", value)
    private fun selectedAnimationMesh() =
        ModelSwapManager.getSwappedMesh(_uiState.value.selectedTargetId)
            ?.takeIf { it.animationNames.isNotEmpty() }
            ?: ModelSwapManager.getSwappedMesh("player_character")

    fun toggleAnimationMenu() {
        _uiState.value = _uiState.value.copy(animationMenuExpanded = !_uiState.value.animationMenuExpanded)
    }

    fun closeAnimationMenu() {
        _uiState.value = _uiState.value.copy(animationMenuExpanded = false)
    }

    fun setSelectedModelAnimationEnabled(enabled: Boolean) {
        val mesh = selectedAnimationMesh()
        if (mesh == null || mesh.animationNames.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                saveToastMessage = "Import a GLB with skeletal animation clips, or select the animated player model."
            )
            return
        }
        mesh.setAnimationEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            animationEnabled = mesh.isAnimationEnabled,
            activeAnimationName = mesh.activeAnimationName,
            saveToastMessage = if (enabled) "Animation enabled: ${mesh.activeAnimationName ?: "default clip"}" else "Animation paused on the current pose."
        )
    }

    fun setSelectedModelAutoAnimation(enabled: Boolean) {
        val mesh = selectedAnimationMesh()
        if (mesh == null || mesh.animationNames.isEmpty()) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "The selected model has no animation clips.")
            return
        }
        mesh.setAutoAnimation(enabled)
        _uiState.value = _uiState.value.copy(
            animationEnabled = mesh.isAnimationEnabled,
            autoAnimationEnabled = mesh.isAutoAnimationEnabled,
            activeAnimationName = mesh.activeAnimationName,
            saveToastMessage = if (enabled) "Auto mode follows player joystick movement: Idle / Walk / Run." else "Manual animation selection enabled."
        )
    }

    fun selectSelectedModelAnimation(index: Int) {
        val mesh = selectedAnimationMesh()
        if (mesh == null || index !in mesh.animationNames.indices) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "No animation clip at that index.")
            return
        }
        mesh.selectAnimation(index)
        _uiState.value = _uiState.value.copy(
            animationMenuExpanded = false,
            activeAnimationName = mesh.activeAnimationName,
            animationEnabled = mesh.isAnimationEnabled,
            autoAnimationEnabled = mesh.isAutoAnimationEnabled,
            saveToastMessage = "Playing animation: ${mesh.activeAnimationName}"
        )
    }

    fun onJoystickMoved(dx: Float, dy: Float) {
        val active = (dx * dx + dy * dy) > 0.005f
        if (active != _uiState.value.isJoystickActive) {
            _uiState.value = _uiState.value.copy(isJoystickActive = active)
        }

        if (_uiState.value.isSpectatorMode && _uiState.value.isEditingObject) {
            val pending = _uiState.value.pendingPlacement
            if (pending != null && ::engine.isInitialized) {
                val obj = engine.worldManager.objects.find { it.id == pending.objectId }
                if (obj != null) {
                    if (_uiState.value.isVerticalAdjustment) {
                        val minimumY = if (obj.isMediaPlane) -30f else 0.5f
                        val newY = (obj.position.y - dy * 0.22f).coerceIn(minimumY, 30f)
                        obj.position.y = newY
                        _uiState.value = _uiState.value.copy(
                            activeObjectHeight = newY,
                            pendingPlacement = pending.copy(currentPosition = obj.position.copy())
                        )
                    } else {
                        val yawRad = Math.toRadians(engine.camera.yaw.toDouble())
                        val cosY = Math.cos(yawRad).toFloat()
                        val sinY = Math.sin(yawRad).toFloat()
                        val speed = 0.22f
                        val mx = (dx * cosY - dy * sinY) * speed
                        val mz = (dx * sinY + dy * cosY) * speed
                        obj.position.x = (obj.position.x + mx).coerceIn(-45f, 45f)
                        obj.position.z = (obj.position.z + mz).coerceIn(-45f, 45f)
                        _uiState.value = _uiState.value.copy(
                            pendingPlacement = pending.copy(currentPosition = obj.position.copy())
                        )
                    }
                }
            }
        } else {
            engine.moveInputX = dx
            engine.moveInputY = dy
        }
    }

    fun onCameraSwipe(panX: Float, panY: Float) {
        engine.rotateCamera(panX, panY)
    }

    fun onZoomDelta(delta: Float) {
        engine.zoomCamera(delta)
    }

    fun onZoomIn() {
        engine.zoomCamera(-200f)
        triggerHapticThrottled(20, 50)
    }

    fun onZoomOut() {
        engine.zoomCamera(200f)
        triggerHapticThrottled(20, 50)
    }

    fun onJump() {
        // The engine emits jump sound/VFX only when performJump succeeds.
        // Keeping this input callback side-effect-free prevents duplicate effects.
        engine.performJump()
    }


    fun onAttack() {
        engine.performAttack()
    }

    /** Launch a travelling fireball using app/src/main/assets/vfx/fireball.png. */
    fun onFireball() {
        if (!::engine.isInitialized) return
        engine.performFireball()
        triggerHapticThrottled(30, 45)
    }

    /** Switch between orbit camera and eye-level first-person view. */
    fun toggleFirstPersonView() {
        if (!::engine.isInitialized) return
        if (engine.camera.isSpectatorMode) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "Exit spectator placement mode before switching camera views.")
            return
        }
        val active = engine.camera.toggleFirstPerson(engine.player.position, engine.player.rotationY)
        _uiState.value = _uiState.value.copy(
            isFirstPersonMode = active,
            saveToastMessage = if (active) "First-person view enabled" else "Third-person view enabled"
        )
        triggerHapticThrottled(25, 45)
    }

    fun onDash() {
        engine.performDash()
    }

    private fun copyWorldObjectForSnapshot(obj: WorldObject): WorldObject = obj.copy(
        position = obj.position.copy(),
        size = obj.size.copy(),
        color = obj.color.clone(),
        initialPosition = obj.initialPosition.copy(),
        moveAxis = obj.moveAxis.copy()
    )

    private fun captureSpectatorEditSnapshot() {
        if (!::engine.isInitialized) return
        spectatorEditGeneration++
        spectatorEditSnapshot = SpectatorEditSnapshot(
            worldObjects = engine.worldManager.objects.map(::copyWorldObjectForSnapshot),
            activeSwaps = ModelSwapManager.getAllSwaps(),
            placedObjects = ModelSwapManager.getPlacedObjects(),
            selectedTargetId = _uiState.value.selectedTargetId,
            selectedTargetName = _uiState.value.selectedTargetName
        )
    }

    /** Load expensive model geometry off the UI thread and run all queued uses on the main thread. */
    private fun withLoadedModel(modelId: String, action: (com.example.teapot.Custom3DMesh) -> Unit) {
        val existing = TeapotModelRegistry.getModel(modelId)
        if (existing == null) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "This model is no longer available in the library.")
            return
        }
        if (!existing.isLazyLoaded) {
            action(existing)
            return
        }
        val callbacks = waitingModelActions.getOrPut(modelId) { mutableListOf() }
        callbacks += DeferredModelAction(spectatorEditGeneration, action)
        if (!modelLoadsInFlight.add(modelId)) return
        val context = appContext
        if (context == null) {
            modelLoadsInFlight.remove(modelId)
            waitingModelActions.remove(modelId)
            _uiState.value = _uiState.value.copy(saveToastMessage = "Model storage is not initialized yet.")
            return
        }
        _uiState.value = _uiState.value.copy(
            saveToastMessage = null,
            modelLoadingName = existing.name,
            modelLoadingProgress = 0,
            modelLoadingStage = "Preparing model…"
        )
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                TeapotModelRegistry.ensureModelLoaded(context.applicationContext, modelId) { percent, stage ->
                    _uiState.value = _uiState.value.copy(
                        modelLoadingName = existing.name,
                        modelLoadingProgress = percent.coerceIn(0, 100),
                        modelLoadingStage = stage
                    )
                }
            }
            modelLoadsInFlight.remove(modelId)
            _uiState.value = _uiState.value.copy(
                modelLoadingName = null,
                modelLoadingProgress = 0,
                modelLoadingStage = ""
            )
            val queued = waitingModelActions.remove(modelId).orEmpty().toList()
            if (loaded == null) {
                // Do not show a stale load error after the edit session that requested it was discarded.
                if (queued.any { it.generation == spectatorEditGeneration }) {
                    val reason = TeapotModelRegistry.lastImportError ?: "The model could not be loaded."
                    _uiState.value = _uiState.value.copy(
                        saveToastMessage = reason,
                        lastSwapTimestamp = System.currentTimeMillis()
                    )
                } else {
                    _uiState.value = _uiState.value.copy(lastSwapTimestamp = System.currentTimeMillis())
                }
            } else {
                // Force the gallery/drag ghost to recompose now that the lightweight entry has
                // been replaced by its actual geometry. Each queued action carries its own session
                // generation, so a newer spectator session can safely reuse an in-flight load.
                _uiState.value = _uiState.value.copy(lastSwapTimestamp = System.currentTimeMillis())
                queued.forEach { deferred ->
                    if (deferred.generation == spectatorEditGeneration) {
                        try { deferred.action(loaded) }
                        catch (e: Exception) {
                            _uiState.value = _uiState.value.copy(
                                saveToastMessage = "Could not use '${loaded.name}': ${e.message ?: e.javaClass.simpleName}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun releaseUnusedLoadedModels() {
        val retainedIds = LinkedHashSet<String>()
        retainedIds += ModelSwapManager.getAllSwaps().values
        ModelSwapManager.getPlacedObjects().forEach { retainedIds += it.modelId }
        viewModelScope.launch(Dispatchers.IO) {
            TeapotModelRegistry.releaseUnusedModels(retainedIds)
        }
    }

    /** Hide the editor HUD while keeping the world and camera gestures active. */
    fun toggleCleanSpectatorView() {
        if (!_uiState.value.isSpectatorMode) return
        _uiState.value = _uiState.value.copy(
            isCleanSpectatorView = !_uiState.value.isCleanSpectatorView,
            isRotationGizmoVisible = false
        )
        triggerHapticThrottled(25, 45)
    }

    /** Show/hide the selected object's three-axis rotation rings in Spectator Mode. */
    fun toggleRotationGizmo() {
        if (!_uiState.value.isSpectatorMode) return
        val visible = !_uiState.value.isRotationGizmoVisible
        _uiState.value = _uiState.value.copy(
            isRotationGizmoVisible = visible,
            isCleanSpectatorView = false,
            saveToastMessage = if (visible) "Drag an X, Y, or Z ring to rotate the selected object." else null
        )
        triggerHapticThrottled(25, 45)
    }

    /**
     * Apply incremental rotation while a visible gizmo ring is dragged.
     * The renderer reports pixel deltas; scale them to a predictable angular speed.
     */
    fun rotateSelectedObjectAxis(axis: RotationAxis, deltaX: Float, deltaY: Float) {
        if (!::engine.isInitialized || !_uiState.value.isSpectatorMode) return
        val id = _uiState.value.selectedTargetId
        val obj = engine.worldManager.objects.firstOrNull { it.id == id && !it.isDestroyed } ?: return
        val delta = ((deltaX - deltaY) * 0.45f).coerceIn(-18f, 18f)
        when (axis) {
            RotationAxis.X -> obj.rotationXDegrees = (obj.rotationXDegrees + delta) % 360f
            RotationAxis.Y -> obj.rotationYDegrees = (obj.rotationYDegrees + delta) % 360f
            RotationAxis.Z -> obj.rotationZDegrees = (obj.rotationZDegrees + delta) % 360f
        }
    }

    /** Drag completion is intentionally side-effect free; changes are saved by Save Furnishings. */
    fun finishRotationGizmoDrag() = Unit

    fun toggleSpectatorMode() {
        if (!::engine.isInitialized) return
        val enteringSpectator = !engine.camera.isSpectatorMode
        if (enteringSpectator) captureSpectatorEditSnapshot()
        if (!engine.camera.isSpectatorMode && engine.camera.isFirstPerson) {
            engine.camera.toggleFirstPerson(engine.player.position, engine.player.rotationY)
        }
        val active = engine.camera.toggleSpectatorMode(engine.player.position)
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            isSpectatorMode = active,
            isCleanSpectatorView = false,
            isRotationGizmoVisible = false,
            selectedTargetId = "",
            selectedTargetName = "Nothing selected",
            selectedTargetIds = emptySet(),
            isMultiSelect = false,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            isFirstPersonMode = engine.camera.isFirstPerson,
            isJoystickActive = false
        )
        triggerHapticThrottled(40, 50)
    }

    // --- Genshin Impact Teapot Swap & Placement Methods ---

    fun getAvailableTargets(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        list.add("player_character" to "Player Character Avatar")
        if (::engine.isInitialized) {
            for (obj in engine.worldManager.objects) {
                val label = when (obj.type) {
                    ObjectType.PLATFORM, ObjectType.MOVING_PLATFORM -> "Platform: ${obj.name}"
                    ObjectType.TRAINING_DUMMY -> "NPC: ${obj.name}"
                    ObjectType.TARGET_CRYSTAL -> "Crystal Target: ${obj.name}"
                    ObjectType.BREAKABLE_CRATE -> "Crate: ${obj.name}"
                    ObjectType.BOUNCE_PAD -> "Bounce Pad: ${obj.name}"
                    ObjectType.FLOATING_DRONE -> "Drone: ${obj.name}"
                    ObjectType.MEDIA_IMAGE -> "Image: ${obj.name}"
                    ObjectType.MEDIA_VIDEO -> "Video: ${obj.name}"
                }
                list.add(obj.id to label)
            }
        }
        return list
    }

    fun selectTarget(targetId: String) {
        if (targetId.isBlank()) {
            clearSelection()
            return
        }
        val targets = getAvailableTargets()
        val name = targets.find { it.first == targetId }?.second ?: targetId
        val obj = if (::engine.isInitialized && targetId != "player_character") {
            engine.worldManager.objects.find { it.id == targetId }
        } else null
        val pending = if (obj != null) {
            PendingPlacementState(
                objectId = obj.id,
                objectName = obj.name,
                originalPosition = obj.position.copy(),
                currentPosition = obj.position.copy(),
                isNewlyAdded = false,
                modelId = ModelSwapManager.getActiveSwapModelId(obj.id),
                originalRotationYDegrees = obj.rotationYDegrees
            )
        } else null
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            selectedTargetId = targetId,
            selectedTargetName = name,
            selectedTargetIds = setOf(targetId),
            pendingPlacement = pending,
            activeObjectRotation = obj?.rotationYDegrees ?: 0f,
            isMovingObject = false,
            isEditingObject = false,
            isVerticalAdjustment = false
        )
        triggerHapticThrottled(25, 40)
    }

    /** Add/remove one target while multi-select is enabled. */
    private fun toggleMultiSelectedTarget(targetId: String) {
        val old = _uiState.value
        val next = LinkedHashSet(old.selectedTargetIds)
        if (!next.add(targetId)) next.remove(targetId)
        val primary = if (targetId in next) targetId else next.lastOrNull().orEmpty()
        val label = getAvailableTargets().firstOrNull { it.first == primary }?.second
            ?: if (primary.isBlank()) "Nothing selected" else primary
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = old.copy(
            selectedTargetIds = next.toSet(),
            selectedTargetId = primary,
            selectedTargetName = label,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            isRotationGizmoVisible = false,
            saveToastMessage = if (next.isEmpty()) "Selection cleared" else "${next.size} object(s) selected"
        )
        triggerHapticThrottled(20, 35)
    }

    /** Merge objects enclosed by a drag-selection rectangle into the current multi-selection. */
    fun addObjectsToSelection(objectIds: Set<String>) {
        if (!_uiState.value.isMultiSelect) return
        val valid = objectIds.filterTo(LinkedHashSet()) { id ->
            id == "player_character" || (::engine.isInitialized && engine.worldManager.objects.any { it.id == id && !it.isDestroyed })
        }
        if (valid.isEmpty()) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "No objects found in selection area")
            return
        }
        val next = LinkedHashSet(_uiState.value.selectedTargetIds)
        next.addAll(valid)
        val primary = valid.lastOrNull() ?: next.lastOrNull().orEmpty()
        val label = getAvailableTargets().firstOrNull { it.first == primary }?.second ?: primary
        _uiState.value = _uiState.value.copy(
            selectedTargetIds = next.toSet(),
            selectedTargetId = primary,
            selectedTargetName = label,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            saveToastMessage = "${next.size} object(s) selected"
        )
        triggerHapticThrottled(25, 40)
    }

    /** Clear selected IDs, move state and editor highlights when the user taps empty space. */
    fun clearSelection() {
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            selectedTargetId = "",
            selectedTargetName = "Nothing selected",
            selectedTargetIds = emptySet(),
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            isRotationGizmoVisible = false,
            saveToastMessage = null
        )
    }

    fun cycleNextTarget() {
        val targets = getAvailableTargets()
        if (targets.isEmpty()) return
        val currentIdx = targets.indexOfFirst { it.first == _uiState.value.selectedTargetId }
        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % targets.size else 0
        selectTarget(targets[nextIdx].first)
    }

    fun selectObjectAtScreenPos(touchX: Float, touchY: Float, glRenderer: com.example.graphics.GLGameRenderer): Boolean {
        val objId = glRenderer.findObjectAtScreenPos(touchX, touchY)
        if (objId == null) {
            clearSelection()
            return false
        }
        val state = _uiState.value
        if (state.isMultiSelect) {
            toggleMultiSelectedTarget(objId)
            return true
        }
        // Preserve a multi-selection when the user taps one of its members to begin moving it.
        if (state.selectedTargetIds.size > 1 && objId in state.selectedTargetIds) {
            val label = getAvailableTargets().firstOrNull { it.first == objId }?.second ?: objId
            _uiState.value = _uiState.value.copy(selectedTargetId = objId, selectedTargetName = label)
            startMovingSelectedObject()
            return true
        }
        selectTarget(objId)
        // Ordinary object taps immediately enable the drag editor; no separate Move-button tap.
        if (objId != "player_character") startMovingSelectedObject()
        return true
    }

    fun spawnAndEditModel(modelId: String, glRenderer: com.example.graphics.GLGameRenderer? = null) {
        if (!::engine.isInitialized) return
        val model = TeapotModelRegistry.getModel(modelId) ?: return
        if (model.isLazyLoaded) {
            withLoadedModel(modelId) { spawnAndEditModel(modelId, glRenderer) }
            return
        }
        val newObjId = "placed_${java.util.UUID.randomUUID()}"

        val categoryStr = model.category
        // Use the actual imported mesh bounds for the placement collider; avoid assigning every
        // high-detail GLB a tiny generic Companion box or an unrelated category size.
        val modelSize = com.example.model.Vector3(
            model.boundsSize.x.coerceIn(0.2f, 40f),
            model.boundsSize.y.coerceIn(0.2f, 40f),
            model.boundsSize.z.coerceIn(0.2f, 40f)
        )

        // Spawn centered in front of camera view
        val cam = engine.camera
        val yawRad = Math.toRadians(cam.yaw.toDouble())
        val dist = 7.0f
        val spawnX = engine.player.position.x - (Math.sin(yawRad) * dist).toFloat()
        val spawnZ = engine.player.position.z - (Math.cos(yawRad) * dist).toFloat()
        val spawnY = (engine.player.position.y + model.boundsSize.y * 0.5f)
            .coerceAtLeast(model.boundsSize.y * 0.5f)

        val newObj = com.example.game.WorldObject(
            id = newObjId,
            name = model.name,
            position = com.example.model.Vector3(spawnX, spawnY, spawnZ),
            size = modelSize,
            color = model.defaultColor.clone(),
            type = when (categoryStr) {
                "Landform", "Building" -> com.example.game.ObjectType.PLATFORM
                "Companion" -> com.example.game.ObjectType.TRAINING_DUMMY
                else -> com.example.game.ObjectType.BREAKABLE_CRATE
            },
            maxHealth = 200f
        )

        engine.worldManager.addObject(newObj)
        com.example.teapot.ModelSwapManager.applySwap(newObjId, modelId, engine.worldManager)
        com.example.teapot.ModelSwapManager.recordPlacedObject(
            newObjId, newObj.name, modelId,
            newObj.position, newObj.size, categoryStr
        )

        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            selectedTargetId = newObjId,
            selectedTargetName = model.name,
            selectedTargetIds = setOf(newObjId),
            isEditingObject = true,
            isVerticalAdjustment = false,
            activeObjectHeight = spawnY,
            activeObjectDescription = when (categoryStr) {
                "Landform" -> "Can be placed side by side and can be stacked vertically."
                "Building" -> "Architectural furnishing providing substantial Adeptal Energy."
                "Companion" -> "Can interact and converse with companions in the realm."
                else -> "Outdoor furnishing that elevates realm aesthetics."
            },
            pendingPlacement = PendingPlacementState(
                objectId = newObjId,
                objectName = model.name,
                originalPosition = newObj.position.copy(),
                currentPosition = newObj.position.copy(),
                isNewlyAdded = true,
                modelId = modelId
            )
        )
        triggerHapticThrottled(40, 50)
    }

    fun selectAndEditObject(objectId: String) {
        if (!::engine.isInitialized || objectId == "player_character") return
        val obj = engine.worldManager.objects.find { it.id == objectId } ?: return

        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            selectedTargetId = objectId,
            selectedTargetName = obj.name,
            selectedTargetIds = setOf(objectId),
            isEditingObject = true,
            isVerticalAdjustment = false,
            activeObjectHeight = obj.position.y,
            activeObjectRotation = obj.rotationYDegrees,
            activeObjectDescription = "Can be placed side by side and can be stacked vertically.",
            pendingPlacement = PendingPlacementState(
                objectId = obj.id,
                objectName = obj.name,
                originalPosition = obj.position.copy(),
                currentPosition = obj.position.copy(),
                isNewlyAdded = false,
                modelId = com.example.teapot.ModelSwapManager.getActiveSwapModelId(obj.id),
                originalRotationYDegrees = obj.rotationYDegrees
            )
        )
        triggerHapticThrottled(35, 45)
    }

    fun toggleAdjustmentMode() {
        val next = !_uiState.value.isVerticalAdjustment
        _uiState.value = _uiState.value.copy(isVerticalAdjustment = next)
        triggerHapticThrottled(30, 40)
    }

    fun rotateActiveObject() {
        val pending = _uiState.value.pendingPlacement ?: return
        if (!::engine.isInitialized) return
        val obj = engine.worldManager.objects.find { it.id == pending.objectId } ?: return

        val nextRot = if (obj.isMediaPlane) {
            (obj.rotationYDegrees + 90f) % 360f
        } else {
            // Existing furnishing behavior: swap width and depth for quarter-turn rotation.
            val tempX = obj.size.x
            obj.size.x = obj.size.z
            obj.size.z = tempX
            (_uiState.value.activeObjectRotation + 90f) % 360f
        }
        if (obj.isMediaPlane) obj.rotationYDegrees = nextRot
        _uiState.value = _uiState.value.copy(activeObjectRotation = nextRot)
        triggerHapticThrottled(35, 45)
    }

    fun confirmActivePlacement() {
        confirmPendingPlacement()
        _uiState.value = _uiState.value.copy(isEditingObject = false)
    }

    fun cancelActivePlacement() {
        cancelPendingPlacement()
        _uiState.value = _uiState.value.copy(isEditingObject = false)
    }

    fun updateMovingActiveObject(touchX: Float, touchY: Float, glRenderer: com.example.graphics.GLGameRenderer) {
        val pending = _uiState.value.pendingPlacement ?: return
        val obj = engine.worldManager.objects.find { it.id == pending.objectId } ?: return

        if (_uiState.value.isVerticalAdjustment) {
            val screenH = glRenderer.screenHeight.toFloat().coerceAtLeast(1f)
            val normalizedY = 1.0f - (touchY / screenH)
            val minHeight = if (obj.isMediaPlane) -30.0f else 0.5f
            val requestedHeight = if (obj.isMediaPlane) normalizedY * 60.0f - 30.0f else normalizedY * 30.0f
            val newHeight = requestedHeight.coerceIn(minHeight, 30.0f)
            obj.position.y = newHeight
            applyActiveGroupOffsets(obj.id)
            _uiState.value = _uiState.value.copy(
                activeObjectHeight = newHeight,
                pendingPlacement = pending.copy(currentPosition = obj.position.copy())
            )
        } else {
            val hit = glRenderer.raycastPlacement(touchX, touchY, excludedId = pending.objectId)
            if (hit != null) {
                // Media planes are non-colliding decals/screens, not floor objects. Use the ray
                // hit's world Y directly so they can pass through the platform and sit below it.
                val newY = if (obj.isMediaPlane) hit.placementPos.y else hit.placementPos.y + (obj.size.y * 0.5f)
                obj.position.x = hit.placementPos.x
                obj.position.y = newY
                obj.position.z = hit.placementPos.z

                glRenderer.hoverPreviewPosition = hit.placementPos
                glRenderer.hoverPreviewSize = com.example.model.Vector3(obj.size.x, obj.size.y, obj.size.z)

                applyActiveGroupOffsets(obj.id)
                _uiState.value = _uiState.value.copy(
                    activeObjectHeight = newY,
                    pendingPlacement = pending.copy(currentPosition = obj.position.copy())
                )
            }
        }
    }

    fun onModelDragHover(
        screenX: Float,
        screenY: Float,
        glRenderer: com.example.graphics.GLGameRenderer,
        modelId: String? = null
    ) {
        val model = modelId?.let { TeapotModelRegistry.getModel(it) }
        // A drag can emit 60+ updates/second. Queue at most one no-op completion while the mesh
        // loads; otherwise hundreds of callbacks accumulate and make drag/drop itself lag.
        if (model?.isLazyLoaded == true && modelId !in modelLoadsInFlight) {
            withLoadedModel(modelId!!) { /* the renderer reads the completed mesh next frame */ }
        }
        val hit = glRenderer.raycastPlacement(screenX, screenY)
        if (hit != null) {
            glRenderer.hoverPreviewPosition = hit.placementPos
            val bounds = model?.boundsSize
            glRenderer.hoverPreviewSize = if (bounds != null) {
                com.example.model.Vector3(
                    bounds.x.coerceIn(0.2f, 40f),
                    bounds.y.coerceIn(0.2f, 40f),
                    bounds.z.coerceIn(0.2f, 40f)
                )
            } else {
                com.example.model.Vector3(2.2f, 1.4f, 2.2f)
            }
        } else {
            glRenderer.hoverPreviewPosition = null
            glRenderer.hoverPreviewSize = null
        }
    }

    fun onModelDragCancel(glRenderer: com.example.graphics.GLGameRenderer) {
        glRenderer.hoverPreviewPosition = null
        glRenderer.hoverPreviewSize = null
        glRenderer.hoverPreviewModelId = null
    }

    fun onModelDragEnd(
        screenX: Float,
        screenY: Float,
        modelId: String,
        glRenderer: com.example.graphics.GLGameRenderer
    ): Boolean {
        glRenderer.hoverPreviewPosition = null
        glRenderer.hoverPreviewSize = null
        glRenderer.hoverPreviewModelId = null
        val hit = glRenderer.raycastPlacement(screenX, screenY)
        if (hit != null) {
            placeFurnishingOnObject(modelId, hit.hitObject, hit.placementPos)
            return true
        }
        _uiState.value = _uiState.value.copy(
            saveToastMessage = "Drop the model onto a visible 3D surface to place a copy. Tap a card to swap instead."
        )
        return false
    }

    fun placeFurnishingOnObject(modelId: String, targetObject: com.example.game.WorldObject, placementPos: com.example.model.Vector3) {
        if (!::engine.isInitialized) return
        val model = TeapotModelRegistry.getModel(modelId) ?: return
        if (model.isLazyLoaded) {
            val savedTargetId = targetObject.id
            val savedPlacement = placementPos.copy()
            withLoadedModel(modelId) {
                val liveTarget = engine.worldManager.objects.firstOrNull { it.id == savedTargetId } ?: return@withLoadedModel
                placeFurnishingOnObject(modelId, liveTarget, savedPlacement)
            }
            return
        }
        val newObjId = "placed_${java.util.UUID.randomUUID()}"

        val categoryStr = model.category
        // Use parsed geometry bounds, clamped to safe world dimensions so malformed or very large
        // model bounds cannot produce a giant collider or destabilize raycasts/render transforms.
        val placementSize = com.example.model.Vector3(
            model.boundsSize.x.coerceIn(0.2f, 40f),
            model.boundsSize.y.coerceIn(0.2f, 40f),
            model.boundsSize.z.coerceIn(0.2f, 40f)
        )
        val placedY = placementPos.y + (placementSize.y * 0.5f)
        val newObj = com.example.game.WorldObject(
            id = newObjId,
            name = "${model.name} (${targetObject.name})",
            position = com.example.model.Vector3(placementPos.x, placedY, placementPos.z),
            size = placementSize,
            color = model.defaultColor.clone(),
            type = when (categoryStr) {
                "Landform", "Building" -> com.example.game.ObjectType.PLATFORM
                "Companion" -> com.example.game.ObjectType.TRAINING_DUMMY
                else -> com.example.game.ObjectType.BREAKABLE_CRATE
            },
            maxHealth = 200f
        )

        engine.worldManager.addObject(newObj)
        com.example.teapot.ModelSwapManager.applySwap(newObjId, modelId, engine.worldManager)
        com.example.teapot.ModelSwapManager.recordPlacedObject(
            newObjId, newObj.name, modelId,
            newObj.position, newObj.size, categoryStr
        )

        selectTarget(newObjId)
        triggerHapticThrottled(50, 60)

        // Set pending confirmation state so user can confirm with ✓ or cancel with ✕
        _uiState.value = _uiState.value.copy(
            isEditingObject = true,
            isVerticalAdjustment = false,
            activeObjectHeight = placedY,
            activeObjectDescription = when (categoryStr) {
                "Landform" -> "Can be placed side by side and stacked vertically."
                "Building" -> "Architectural furnishing. Move, rotate, or adjust its height."
                "Companion" -> "Placed companion model. Its target/gameplay type is retained."
                else -> "Imported furnishing. Move, rotate, or adjust its height."
            },
            pendingPlacement = PendingPlacementState(
                objectId = newObjId,
                objectName = model.name,
                originalPosition = newObj.position.copy(),
                currentPosition = newObj.position.copy(),
                isNewlyAdded = true,
                modelId = modelId
            ),
            saveToastMessage = "Model placed. Adjust it, then tap ✓ to confirm or ✕ to cancel."
        )
    }

    fun startMovingSelectedObject(anchorTargetId: String? = null, deferEditorUntilDragEnd: Boolean = false) {
        if (!::engine.isInitialized) return
        val liveObjects = engine.worldManager.objects.filter { !it.isDestroyed }
        val requestedIds = _uiState.value.selectedTargetIds
        val moveIds = requestedIds.filter { id -> id != "player_character" && liveObjects.any { it.id == id } }.toSet()
        val requestedAnchor = anchorTargetId ?: _uiState.value.selectedTargetId
        val targetId = requestedAnchor.takeIf { it in moveIds } ?: moveIds.firstOrNull() ?: return
        val anchor = liveObjects.firstOrNull { it.id == targetId } ?: return
        val ids = moveIds.ifEmpty { setOf(targetId) }
        activeGroupOriginalPositions = ids.mapNotNull { id ->
            liveObjects.firstOrNull { it.id == id }?.let { id to it.position.copy() }
        }.toMap()
        activeGroupOffsets = ids.mapNotNull { id ->
            liveObjects.firstOrNull { it.id == id }?.let { id to Vector3(it.position.x - anchor.position.x, it.position.y - anchor.position.y, it.position.z - anchor.position.z) }
        }.toMap()

        _uiState.value = _uiState.value.copy(
            selectedTargetId = anchor.id,
            selectedTargetName = getAvailableTargets().firstOrNull { it.first == anchor.id }?.second ?: anchor.name,
            selectedTargetIds = ids,
            isMovingObject = true,
            isEditingObject = !deferEditorUntilDragEnd,
            isVerticalAdjustment = false,
            activeObjectHeight = anchor.position.y,
            activeObjectDescription = if (ids.size > 1)
                "Moving ${ids.size} selected objects together. Drag, then tap ✓ to save or ✕ to revert."
            else "Move the selected object with a drag, then tap ✓ to save or ✕ to revert.",
            pendingPlacement = PendingPlacementState(
                objectId = anchor.id,
                objectName = anchor.name,
                originalPosition = anchor.position.copy(),
                currentPosition = anchor.position.copy(),
                isNewlyAdded = false,
                modelId = ModelSwapManager.getActiveSwapModelId(anchor.id),
                originalRotationYDegrees = anchor.rotationYDegrees
            )
        )
        triggerHapticThrottled(30, 40)
    }

    /** Keep every member's original offset from the dragged anchor. */
    private fun applyActiveGroupOffsets(anchorId: String) {
        if (!::engine.isInitialized || activeGroupOffsets.size < 2) return
        val anchor = engine.worldManager.objects.firstOrNull { it.id == anchorId && !it.isDestroyed } ?: return
        for ((id, offset) in activeGroupOffsets) {
            if (id == anchorId) continue
            val obj = engine.worldManager.objects.firstOrNull { it.id == id && !it.isDestroyed } ?: continue
            obj.position.x = anchor.position.x + offset.x
            obj.position.y = anchor.position.y + offset.y
            obj.position.z = anchor.position.z + offset.z
        }
    }

    fun updateMovingSelectedObject(touchX: Float, touchY: Float, glRenderer: com.example.graphics.GLGameRenderer) {
        val pending = _uiState.value.pendingPlacement ?: return
        val obj = engine.worldManager.objects.find { it.id == pending.objectId } ?: return

        // Raycast against all objects excluding the one being dragged
        val hit = glRenderer.raycastPlacement(touchX, touchY, excludedId = pending.objectId)
        if (hit != null) {
            // Media planes can be moved through geometry; don't snap their center to the surface.
            val newY = if (obj.isMediaPlane) hit.placementPos.y else hit.placementPos.y + (obj.size.y * 0.5f)
            obj.position.x = hit.placementPos.x
            obj.position.y = newY
            obj.position.z = hit.placementPos.z
            applyActiveGroupOffsets(obj.id)

            glRenderer.hoverPreviewPosition = hit.placementPos
            glRenderer.hoverPreviewSize = com.example.model.Vector3(obj.size.x, obj.size.y, obj.size.z)

            _uiState.value = _uiState.value.copy(
                pendingPlacement = pending.copy(currentPosition = obj.position.copy())
            )
        }
    }

    fun finishMovingSelectedObject(glRenderer: com.example.graphics.GLGameRenderer) {
        glRenderer.hoverPreviewPosition = null
        glRenderer.hoverPreviewSize = null
        _uiState.value = _uiState.value.copy(
            isMovingObject = false,
            isEditingObject = _uiState.value.pendingPlacement != null
        )
        triggerHapticThrottled(40, 50)
    }

    fun confirmPendingPlacement() {
        val pending = _uiState.value.pendingPlacement ?: return
        if (::engine.isInitialized) {
            val changedIds = if (activeGroupOriginalPositions.isNotEmpty()) activeGroupOriginalPositions.keys else setOf(pending.objectId)
            val placedRecords = ModelSwapManager.getPlacedObjects().associateBy { it.id }
            for (id in changedIds) {
                val obj = engine.worldManager.objects.find { it.id == id } ?: continue
                val record = placedRecords[id] ?: continue
                val modelId = ModelSwapManager.getActiveSwapModelId(id) ?: record.modelId
                val category = TeapotModelRegistry.getModel(modelId)?.category ?: record.category
                ModelSwapManager.recordPlacedObject(id, obj.name, modelId, obj.position, obj.size, category)
            }
        }
        // Save to persistent storage; all selected members now have their latest transforms.
        saveFurnishings()
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            pendingPlacement = null,
            isMovingObject = false,
            saveToastMessage = "Placement Confirmed & Saved!"
        )
        triggerHapticThrottled(60, 70)
    }

    fun cancelPendingPlacement() {
        val pending = _uiState.value.pendingPlacement ?: return
        if (::engine.isInitialized) {
            if (pending.isNewlyAdded) {
                // Remove newly spawned object
                engine.worldManager.removeObject(pending.objectId)
                com.example.teapot.ModelSwapManager.removeSwap(pending.objectId)
                com.example.teapot.ModelSwapManager.removePlacedObject(pending.objectId)
            } else {
                // Revert every member of a grouped drag to its transaction-start position.
                if (activeGroupOriginalPositions.isNotEmpty()) {
                    for ((id, original) in activeGroupOriginalPositions) {
                        val member = engine.worldManager.objects.find { it.id == id } ?: continue
                        member.position.x = original.x
                        member.position.y = original.y
                        member.position.z = original.z
                    }
                } else {
                    val obj = engine.worldManager.objects.find { it.id == pending.objectId }
                    if (obj != null) {
                        obj.position.x = pending.originalPosition.x
                        obj.position.y = pending.originalPosition.y
                        obj.position.z = pending.originalPosition.z
                        if (obj.isMediaPlane) obj.rotationYDegrees = pending.originalRotationYDegrees
                    }
                }
            }
            activeGroupOffsets = emptyMap()
            activeGroupOriginalPositions = emptyMap()
            // Persistence occurs only on explicit Confirm / Save & Exit. Discard & Exit also
            // restores the entry snapshot, so cancellation here never commits an edit implicitly.
        }
        _uiState.value = _uiState.value.copy(
            pendingPlacement = null,
            isMovingObject = false,
            saveToastMessage = if (pending.isNewlyAdded) "Placement Cancelled" else "Reverted to Original Position"
        )
        releaseUnusedLoadedModels()
        triggerHapticThrottled(40, 50)
    }

    // Exit Spectator Dialog actions (Requirement 3)
    fun requestExitSpectator() {
        _uiState.value = _uiState.value.copy(showExitConfirmationDialog = true)
        triggerHapticThrottled(30, 40)
    }

    fun dismissExitSpectatorDialog() {
        _uiState.value = _uiState.value.copy(showExitConfirmationDialog = false)
    }

    fun saveAndExitSpectator() {
        // Prevent an unfinished async drag/import callback from creating a new object after exit.
        spectatorEditGeneration++
        if (_uiState.value.pendingPlacement != null) confirmPendingPlacement()
        saveFurnishings()
        if (::engine.isInitialized) {
            engine.camera.isSpectatorMode = false
            engine.moveInputX = 0f
            engine.moveInputY = 0f
        }
        spectatorEditSnapshot = null
        _uiState.value = _uiState.value.copy(
            showExitConfirmationDialog = false,
            isSpectatorMode = false,
            isCleanSpectatorView = false,
            isRotationGizmoVisible = false,
            isJoystickActive = false,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            selectedTargetId = "",
            selectedTargetName = "Nothing selected",
            selectedTargetIds = emptySet(),
            isMultiSelect = false,
            saveToastMessage = "Furnishings Saved. Returned to Action Mode!"
        )
        triggerHapticThrottled(70, 80)
    }

    fun discardAndExitSpectator() {
        spectatorEditGeneration++
        val snapshot = spectatorEditSnapshot
        if (::engine.isInitialized && snapshot != null) {
            // Restore the exact scene that existed before Spectator Mode opened. Do not reload the
            // current disk state here: several in-session actions may have written tentative data.
            engine.worldManager.restoreSnapshot(snapshot.worldObjects)
            ModelSwapManager.restoreInMemoryState(snapshot.activeSwaps, snapshot.placedObjects)
            appContext?.let { ctx ->
                ModelSwapManager.saveSwapsToStorage(ctx)
                MediaWorldStore.save(ctx, engine.worldManager.objects)
            }
        } else if (::engine.isInitialized) {
            appContext?.let { ctx ->
                engine.worldManager.createDefaultWorld()
                ModelSwapManager.loadSwapsFromStorage(ctx, engine.worldManager)
                MediaWorldStore.restore(ctx, engine.worldManager)
            }
        }
        if (::engine.isInitialized) {
            engine.camera.isSpectatorMode = false
            engine.moveInputX = 0f
            engine.moveInputY = 0f
        }
        val restoredTargetId = snapshot?.selectedTargetId?.takeIf { id ->
            id == "player_character" || engine.worldManager.objects.any { it.id == id }
        } ?: "platform_spawn"
        val restoredTargetName = if (restoredTargetId == snapshot?.selectedTargetId) {
            snapshot.selectedTargetName
        } else "Spawn Platform"
        spectatorEditSnapshot = null
        _uiState.value = _uiState.value.copy(
            showExitConfirmationDialog = false,
            isSpectatorMode = false,
            isCleanSpectatorView = false,
            isRotationGizmoVisible = false,
            isJoystickActive = false,
            selectedTargetId = "",
            selectedTargetName = "Nothing selected",
            selectedTargetIds = emptySet(),
            isMultiSelect = false,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            saveToastMessage = "Unsaved furnishing changes discarded. Original scene restored."
        )
        releaseUnusedLoadedModels()
        triggerHapticThrottled(50, 60)
    }

    fun swapModelForSelectedTarget(modelId: String) {
        if (!::engine.isInitialized) return
        val model = TeapotModelRegistry.getModel(modelId) ?: return
        if (model.isLazyLoaded) {
            withLoadedModel(modelId) { swapModelForSelectedTarget(modelId) }
            return
        }
        val targetId = _uiState.value.selectedTargetId
        val targetObject = if (targetId == "player_character") null else {
            engine.worldManager.objects.find { it.id == targetId }
        }
        if (targetId != "player_character" && targetObject == null) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "Select a character or existing world object first")
            return
        }

        // Only the visual mesh and collision bounds change. WorldObject.type, health, attacks,
        // movement controller, and other gameplay behavior remain attached to the selected target.
        ModelSwapManager.applySwap(targetId, modelId, engine.worldManager)
        val pendingPlacement = _uiState.value.pendingPlacement?.let { pending ->
            if (pending.objectId == targetId) pending.copy(modelId = modelId) else pending
        }
        if (targetObject != null) {
            val existingPlaced = ModelSwapManager.getPlacedObjects().firstOrNull { it.id == targetId }
            if (existingPlaced != null) {
                ModelSwapManager.recordPlacedObject(
                    targetObject.id, targetObject.name, modelId,
                    targetObject.position, targetObject.size, model.category
                )
            }
        }
        val saved = appContext?.let { ModelSwapManager.saveSwapsToStorage(it) } ?: false
        triggerHapticThrottled(40, 50)
        _uiState.value = _uiState.value.copy(
            pendingPlacement = pendingPlacement,
            lastSwapTimestamp = System.currentTimeMillis(),
            saveToastMessage = if (saved) "${model.name} applied to ${if (targetId == "player_character") "Player Character" else targetObject?.name ?: "object"} and saved"
                else "${model.name} applied; persistence save failed"
        )
        releaseUnusedLoadedModels()
    }

    fun saveFurnishings() {
        appContext?.let { ctx ->
            val modelSaveSucceeded = ModelSwapManager.saveSwapsToStorage(ctx)
            val mediaSaveSucceeded = if (::engine.isInitialized) {
                MediaWorldStore.save(ctx, engine.worldManager.objects)
            } else true
            val builtInSaveSucceeded = if (::engine.isInitialized) {
                BuiltInWorldTransformStore.save(ctx, engine.worldManager.objects)
            } else true
            val success = modelSaveSucceeded && mediaSaveSucceeded && builtInSaveSucceeded
            triggerHapticThrottled(70, 80)
            _uiState.value = _uiState.value.copy(
                saveToastMessage = if (success) "Furnishing Placements Saved Successfully!" else "Failed to Save Swaps"
            )
        }
    }

    fun clearSaveToast() {
        _uiState.value = _uiState.value.copy(saveToastMessage = null)
    }

    private var modelImportRunning = false

    fun importModelFile(uri: Uri, fileName: String) {
        val context = appContext ?: return
        if (modelImportRunning) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "A model is already importing. Please wait.")
            return
        }
        modelImportRunning = true
        _uiState.value = _uiState.value.copy(saveToastMessage = "Importing $fileName…")

        viewModelScope.launch {
            try {
                // File copying, parsing, texture decoding and mesh batching all happen off the UI thread.
                val importedMesh = withContext(Dispatchers.IO) {
                    TeapotModelRegistry.importModelFromUri(context, uri, fileName)
                }
                if (importedMesh != null) {
                    _uiState.value = _uiState.value.copy(
                        saveToastMessage = "Imported '${importedMesh.name}' to the library only. Nothing was placed or swapped. Use PLACE to add it, or choose the explicit swap action."
                    )
                } else {
                    val reason = TeapotModelRegistry.lastImportError ?: "Unsupported or invalid 3D model file."
                    _uiState.value = _uiState.value.copy(saveToastMessage = "Model import failed: $reason")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "Model import failed: ${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                modelImportRunning = false
            }
        }
    }

    /** Export the current editable scene to a user-selected .glb document. */
    fun exportSceneGlb(destination: Uri) {
        val context = appContext ?: return
        if (!::engine.isInitialized) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saveToastMessage = "Preparing GLB scene export…")
            try {
                val result = withContext(Dispatchers.IO) {
                    val tempSources = mutableListOf<File>()
                    try {
                        val instances = mutableListOf<SceneExportInstance>()
                        val swaps = ModelSwapManager.getAllSwaps()
                        val objectsById = engine.worldManager.objects.associateBy { it.id }
                        for ((targetId, modelId) in swaps) {
                            val mesh = TeapotModelRegistry.getModel(modelId) ?: continue
                            val source = mesh.sourcePath ?: continue
                            val sourceFile = if (source.startsWith("asset://")) {
                                val assetName = source.removePrefix("asset://")
                                val suffix = if (assetName.endsWith(".glb", true)) ".glb" else continue
                                val tmp = File(context.cacheDir, "scene_export_${modelId.hashCode()}$suffix")
                                context.assets.open(assetName).use { input -> tmp.outputStream().use(input::copyTo) }
                                tempSources += tmp
                                tmp
                            } else File(source)
                            if (!sourceFile.isFile || !sourceFile.extension.equals("glb", true)) continue
                            val obj = objectsById[targetId]
                            val isPlayer = targetId == "player_character"
                            val position = obj?.position ?: if (isPlayer) engine.player.position else null
                            if (position == null) continue
                            val size = obj?.size ?: Vector3(1.2f, 2.5f, 1.2f)
                            instances += SceneExportInstance(
                                modelId = modelId,
                                objectName = obj?.name ?: "Player",
                                sourcePath = sourceFile.absolutePath,
                                positionX = position.x,
                                positionY = position.y,
                                positionZ = position.z,
                                sizeX = size.x,
                                sizeY = size.y,
                                sizeZ = size.z,
                                rotationYDegrees = obj?.rotationYDegrees ?: engine.player.rotationY,
                                rotationXDegrees = obj?.rotationXDegrees ?: 0f,
                                rotationZDegrees = obj?.rotationZDegrees ?: 0f,
                                isPlayerCharacter = isPlayer,
                                normalizedHeight = mesh.boundsSize.y.coerceAtLeast(0.001f)
                            )
                        }
                        val result = GlbSceneExporter.exportWorld(context, instances, engine.worldManager.objects)
                        context.contentResolver.openOutputStream(destination, "w")?.use { it.write(result.bytes) }
                            ?: error("Android could not open the selected destination for writing.")
                        result
                    } finally {
                        tempSources.forEach { it.delete() }
                    }
                }
                val notes = result.messages.take(3).joinToString(" ")
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "Exported ${result.objectCount} scene object(s) to GLB. ${notes}".trim()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "GLB export failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun importMedia(uri: Uri, kind: MediaKind) {
        val context = appContext ?: return
        val repository = mediaRepository ?: MediaRepository(context).also { mediaRepository = it }
        _uiState.value = _uiState.value.copy(saveToastMessage = "Importing ${kind.name.lowercase()}…")
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    when (kind) {
                        MediaKind.IMAGE -> repository.importImage(uri)
                        MediaKind.VIDEO -> repository.importVideo(uri)
                    }
                }
                _uiState.value = _uiState.value.copy(
                    mediaItems = repository.list(),
                    saveToastMessage = "Imported '${imported.displayName}'. Tap its Media card to place it in the world."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    saveToastMessage = "Media import failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /** Place a media-library item as an editable world-space textured plane. */
    fun placeMedia(mediaId: String) {
        if (!::engine.isInitialized) return
        val context = appContext ?: return
        val item = _uiState.value.mediaItems.firstOrNull { it.id == mediaId } ?: run {
            _uiState.value = _uiState.value.copy(saveToastMessage = "Media item is no longer in the library. Reopen Media and try again.")
            return
        }
        val assetFile = File(File(context.filesDir, "media_library/assets"), item.fileName)
        if (!assetFile.isFile) {
            _uiState.value = _uiState.value.copy(saveToastMessage = "The imported media file is missing. Import it again.")
            return
        }
        val thumbnailFile = File(File(context.filesDir, "media_library/thumbnails"), item.thumbnailName)
        val aspect = item.aspectRatio.coerceIn(0.2f, 5f)
        val longSide = 3.0f
        val width = if (aspect >= 1f) longSide else (longSide * aspect).coerceAtLeast(0.45f)
        val height = if (aspect >= 1f) (longSide / aspect).coerceAtLeast(0.45f) else longSide
        val cameraYaw = engine.camera.yaw
        val yawRad = Math.toRadians(cameraYaw.toDouble())
        val distance = 5.5f
        val spawnX = engine.player.position.x - (kotlin.math.sin(yawRad) * distance).toFloat()
        val spawnZ = engine.player.position.z - (kotlin.math.cos(yawRad) * distance).toFloat()
        val spawnY = (engine.player.position.y + height * 0.5f).coerceAtLeast(height * 0.5f)
        val id = "media_${item.id}_${System.currentTimeMillis()}"
        val isVideo = item.kind == MediaKind.VIDEO
        val obj = WorldObject(
            id = id,
            name = item.displayName,
            position = Vector3(spawnX, spawnY, spawnZ),
            size = Vector3(width, height, 0.08f),
            color = floatArrayOf(1f, 1f, 1f, 1f),
            type = if (isVideo) ObjectType.MEDIA_VIDEO else ObjectType.MEDIA_IMAGE,
            mediaId = item.id,
            mediaFilePath = assetFile.absolutePath,
            mediaThumbnailPath = thumbnailFile.takeIf { it.isFile }?.absolutePath,
            mediaKind = if (isVideo) "VIDEO" else "IMAGE",
            mediaAspectRatio = aspect,
            rotationYDegrees = cameraYaw
        )
        engine.worldManager.addObject(obj)
        activeGroupOffsets = emptyMap()
        activeGroupOriginalPositions = emptyMap()
        _uiState.value = _uiState.value.copy(
            selectedTargetId = obj.id,
            selectedTargetName = obj.name,
            selectedTargetIds = setOf(obj.id),
            isEditingObject = true,
            isMovingObject = false,
            isVerticalAdjustment = false,
            activeObjectHeight = spawnY,
            activeObjectRotation = cameraYaw,
            activeObjectDescription = if (isVideo)
                "Muted looping video plane. Drag to move, rotate with the rotation control, then confirm to save."
            else
                "Image plane. Drag to move, rotate with the rotation control, then confirm to save.",
            pendingPlacement = PendingPlacementState(
                objectId = obj.id,
                objectName = obj.name,
                originalPosition = obj.position.copy(),
                currentPosition = obj.position.copy(),
                isNewlyAdded = true,
                originalRotationYDegrees = obj.rotationYDegrees
            ),
            saveToastMessage = "Media placed. Drag to position it, then tap ✓ to save or ✕ to cancel."
        )
        triggerHapticThrottled(35, 50)
    }

    fun toggleMultiSelect() {
        val state = _uiState.value
        val next = !state.isMultiSelect
        val selected = if (next && state.selectedTargetId.isNotBlank()) {
            state.selectedTargetIds + state.selectedTargetId
        } else state.selectedTargetIds
        _uiState.value = state.copy(
            isMultiSelect = next,
            selectedTargetIds = selected,
            pendingPlacement = null,
            isEditingObject = false,
            isMovingObject = false,
            isVerticalAdjustment = false,
            saveToastMessage = if (next) "Multi-select enabled: tap objects or hold and drag a selection box." else "Multi-select disabled. Selected group is retained; tap a selected object to move the group."
        )
        triggerHapticThrottled(25, 50)
    }

    fun deleteSelectedObject() {
        val selectedId = _uiState.value.selectedTargetId
        val pending = _uiState.value.pendingPlacement
        // If an unconfirmed placement is selected, use the same cleanup path as Cancel.
        if (pending != null && pending.objectId == selectedId) {
            cancelPendingPlacement()
        } else {
            val removed = engine.worldManager.removeObject(selectedId)
            if (removed) {
                ModelSwapManager.removeSwap(selectedId)
                ModelSwapManager.removePlacedObject(selectedId)
                _uiState.value = _uiState.value.copy(
                    selectedTargetId = "",
                    selectedTargetName = "Nothing selected",
                    selectedTargetIds = emptySet(),
                    isEditingObject = false,
                    isMovingObject = false,
                    pendingPlacement = null,
                    saveToastMessage = "Object deleted"
                )
                releaseUnusedLoadedModels()
                triggerHapticThrottled(35, 60)
            } else {
                _uiState.value = _uiState.value.copy(saveToastMessage = "Selected object could not be deleted")
            }
        }
    }

    fun rotateSelectedTarget() {
        // Existing quick camera rotation; the 3-axis object gizmo is a separate renderer feature.
        engine.camera.yaw = (engine.camera.yaw + 45f) % 360f
        triggerHapticThrottled(30, 50)
    }

    fun cycleTeapotArea(forward: Boolean) {
        if (forward) {
            currentAreaIndex = (currentAreaIndex + 1) % teapotAreas.size
        } else {
            currentAreaIndex = if (currentAreaIndex - 1 < 0) teapotAreas.size - 1 else currentAreaIndex - 1
        }
        _uiState.value = _uiState.value.copy(teapotAreaName = teapotAreas[currentAreaIndex])
        triggerHapticThrottled(25, 50)
    }

    fun onOpenSettings() {
        _uiState.value = _uiState.value.copy(isSettingsOpen = true)
    }

    fun onCloseSettings() {
        _uiState.value = _uiState.value.copy(isSettingsOpen = false)
    }

//    fun updateSettings(newSettings: GameSettings) {
//        engine.settings = newSettings
//        _uiState.value = _uiState.value.copy(settings = newSettings)
//        GameAudioManager.setSoundEnabled(newSettings.soundEnabled)
//        GameAudioManager.setSfxEnabled(newSettings.soundEffectsEnabled)
//        GameAudioManager.setMusicEnabled(newSettings.backgroundMusicEnabled)
//        GameAudioManager.setMasterVolume(newSettings.masterVolume)
//        GameAudioManager.setSfxVolume(newSettings.soundEffectsVolume)
//        GameAudioManager.setMusicVolume(newSettings.backgroundMusicVolume)
//        if (newSettings.soundEnabled && newSettings.backgroundMusicEnabled) {
//            GameAudioManager.playBackgroundMusic()
//        } else {
//            GameAudioManager.pauseBackgroundMusic()
//        }
//        appContext?.let { GameSettingsStorage.saveSettings(it, newSettings) }
//    }




    fun updateSettings(newSettings: GameSettings) {
        engine.settings = newSettings
        _uiState.value = _uiState.value.copy(settings = newSettings)

        // Apply every audio toggle independently; disabling one setting must not leave its
        // previous player/volume state active in the audio manager.
        GameAudioManager.setSoundEnabled(newSettings.soundEnabled)
        GameAudioManager.setSfxEnabled(newSettings.soundEffectsEnabled)
        GameAudioManager.setMusicEnabled(newSettings.backgroundMusicEnabled)
        GameAudioManager.setMasterVolume(newSettings.masterVolume)
        GameAudioManager.setSfxVolume(newSettings.soundEffectsVolume)
        GameAudioManager.setMusicVolume(newSettings.backgroundMusicVolume)
        if (newSettings.soundEnabled && newSettings.backgroundMusicEnabled) {
            GameAudioManager.playBackgroundMusic()
        } else {
            GameAudioManager.pauseBackgroundMusic()
        }
        appContext?.let { context -> GameSettingsStorage.saveSettings(context, newSettings) }
    }

    fun snapCameraToNorth() {
        engine.camera.yaw = 180f
        triggerHapticThrottled(25, 50)
    }

    fun onResetPlayer() {
        engine.respawnPlayerAtNearestSafePlatform()
        triggerHapticThrottled(40, 50)
        syncUIState()
    }

    fun onResetWorld() {
        engine.resetGame()
        triggerHapticThrottled(60, 50)
        syncUIState()
    }

    private fun triggerHapticThrottled(durationMs: Long, minIntervalMs: Long = 60) {
        if (!engine.settings.hapticEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastHapticTimestamp < minIntervalMs) return
        lastHapticTimestamp = now

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
