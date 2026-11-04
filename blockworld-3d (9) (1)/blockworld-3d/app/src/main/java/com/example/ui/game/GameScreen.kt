package com.example.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import com.example.game.GameEngine
import com.example.graphics.GLGameRenderer
import com.example.graphics.RotationAxis
import com.example.teapot.ExitSpectatorDialog
import com.example.teapot.TeapotBottomBar
import com.example.teapot.TeapotObjectInfo
import com.example.teapot.TeapotPlacementControls
import com.example.teapot.TeapotTopBar
import com.example.media.MediaKind
import com.example.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import com.example.sfx.GameAudioLifecycle
import com.example.ui.components.SettingsDialog

/**
 * Main 3D Game Screen featuring:
 * 1. Single Virtual Joystick fixed strictly at the bottom-left corner (1/4th screen).
 * 2. Genshin Impact Serenitea Pot Placement System in Spectator Mode:
 *    - Top Area Selector and compact top-right action icons (Rotate, Save, Import, Multi-select)
 *    - The subcategory icon strip is omitted; category tabs stay visible.
 *    - Bottom Furnishing Drawer: Hides smoothly when joystick moves camera, reappears when idle!
 *    - 3D Model Swapping for world objects, platforms, NPCs, and character avatar!
 *    - File Manager Import for .glb, .obj, .fbx models.
 *    - Persistent saving that restores all swapped models on app reopen!
 */
@SuppressLint("ClickableViewAccessibility")
@Composable

fun GameScreen(

    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    GameAudioLifecycle()
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val gameEngine = remember { GameEngine() }
    val glRenderer = remember { GLGameRenderer(context, gameEngine) }
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // GLSurfaceView does not follow Compose lifecycle automatically. Pause the video decoders
    // before the GL thread is suspended, and resume them only while the screen is foregrounded.
    DisposableEffect(lifecycleOwner, glSurfaceView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    glSurfaceView?.queueEvent { glRenderer.setMediaPlaybackPaused(true) }
                    glSurfaceView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    glSurfaceView?.onResume()
                    glSurfaceView?.queueEvent { glRenderer.setMediaPlaybackPaused(false) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceView?.queueEvent { glRenderer.setMediaPlaybackPaused(true) }
            glSurfaceView?.onPause()
        }
    }

    // Drag cards and touch overlays report Compose-root coordinates; the OpenGL surface can
    // have a different origin/size under edge-to-edge layouts. Convert them before raycasting.
    var glViewOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var glViewSize by remember { mutableStateOf(IntSize.Zero) }
    var touchLayerOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeEnd by remember { mutableStateOf<Offset?>(null) }

    fun rootPointToGl(pointInRoot: Offset): Offset {
        val width = glViewSize.width.toFloat()
        val height = glViewSize.height.toFloat()
        if (width <= 0f || height <= 0f) return pointInRoot
        val localX = (pointInRoot.x - glViewOriginInRoot.x).coerceIn(0f, width)
        val localY = (pointInRoot.y - glViewOriginInRoot.y).coerceIn(0f, height)
        return Offset(
            localX * glRenderer.screenWidth.toFloat() / width,
            localY * glRenderer.screenHeight.toFloat() / height
        )
    }

    fun touchLayerPointToGl(localPoint: Offset): Offset =
        rootPointToGl(touchLayerOriginInRoot + localPoint)

    // Android File Manager Picker for .glb, .obj, .fbx models (Requirement 3)
    val glbExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportSceneGlb(uri)
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryDisplayName(context, uri) ?: "imported_model.glb"
            viewModel.importModelFile(uri, fileName)
        }
    }

    // Independent Storage Access Framework pickers. Import is copied into private app storage
    // immediately, so the game never depends on a temporary content:// permission later.
    val imageMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importMedia(uri, MediaKind.IMAGE)
    }
    val videoMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importMedia(uri, MediaKind.VIDEO)
    }

    LaunchedEffect(Unit) {
        viewModel.initialize(context, gameEngine)
    }

    // Keep editor-only visuals strictly scoped to Spectator Mode. Leaving it clears selection
    // outlines, rotation rings, and any stale green placement preview from the GL renderer.
    LaunchedEffect(uiState.isSpectatorMode) {
        if (uiState.isSpectatorMode) {
            glRenderer.isSpectatorEditingActive = true
            glRenderer.selectedTargetId = uiState.selectedTargetId.takeIf { it.isNotBlank() }
            glRenderer.selectedTargetIds = uiState.selectedTargetIds
            glRenderer.setRotationGizmoVisible(uiState.isRotationGizmoVisible)
        } else {
            glRenderer.clearSpectatorEditorVisuals()
        }
    }
    LaunchedEffect(uiState.selectedTargetId, uiState.selectedTargetIds, uiState.isSpectatorMode) {
        glRenderer.selectedTargetId = if (uiState.isSpectatorMode) uiState.selectedTargetId.takeIf { it.isNotBlank() } else null
        glRenderer.selectedTargetIds = if (uiState.isSpectatorMode) uiState.selectedTargetIds else emptySet()
    }
    LaunchedEffect(uiState.isRotationGizmoVisible, uiState.isSpectatorMode) {
        glRenderer.setRotationGizmoVisible(uiState.isSpectatorMode && uiState.isRotationGizmoVisible)
    }

    // Auto-dismiss save toast after 3 seconds
    LaunchedEffect(uiState.saveToastMessage) {
        if (uiState.saveToastMessage != null) {
            delay(3000)
            viewModel.clearSaveToast()
        }
    }

    // Drag-and-drop state for placing 3D models over other objects
    var draggingModelId by remember { mutableStateOf<String?>(null) }
    var dragScreenPos by remember { mutableStateOf(Offset.Zero) }
    var dragHoverTarget by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current.density

    // Intercept back press in spectator mode to show exit spectator confirmation popup
    BackHandler(enabled = uiState.isSpectatorMode && !uiState.isCleanSpectatorView) {
        viewModel.requestExitSpectator()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. OpenGL ES 3D Surface View (Background)
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(glRenderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glSurfaceView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    glViewOriginInRoot = coordinates.positionInRoot()
                    glViewSize = coordinates.size
                }
        )

        // 2. Camera Touch Pan, Pinch-to-Zoom & Active Object Moving Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    touchLayerOriginInRoot = coordinates.positionInRoot()
                }
                .pointerInput(
                    uiState.isSpectatorMode,
                    uiState.isEditingObject,
                    uiState.isRotationGizmoVisible,
                    uiState.isCleanSpectatorView,
                    uiState.isMultiSelect,
                    uiState.selectedTargetIds
                ) {
                    if (uiState.isSpectatorMode && !uiState.isCleanSpectatorView) {
                        if (uiState.isRotationGizmoVisible) {
                            var activeAxis: RotationAxis? = null
                            detectDragGestures(
                                onDragStart = { start ->
                                    val glPoint = touchLayerPointToGl(start)
                                    activeAxis = glRenderer.beginRotationGizmoDrag(glPoint.x, glPoint.y)
                                },
                                onDragEnd = {
                                    if (activeAxis != null) viewModel.finishRotationGizmoDrag()
                                    activeAxis = null
                                },
                                onDragCancel = {
                                    if (activeAxis != null) viewModel.finishRotationGizmoDrag()
                                    activeAxis = null
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val axis = activeAxis ?: return@detectDragGestures
                                    val scaleX = glRenderer.screenWidth.toFloat() / glViewSize.width.coerceAtLeast(1)
                                    val scaleY = glRenderer.screenHeight.toFloat() / glViewSize.height.coerceAtLeast(1)
                                    viewModel.rotateSelectedObjectAxis(axis, dragAmount.x * scaleX, dragAmount.y * scaleY)
                                }
                            )
                        } else if (uiState.isEditingObject) {
                            detectDragGestures(
                                onDragEnd = { viewModel.finishMovingSelectedObject(glRenderer) },
                                onDragCancel = { viewModel.finishMovingSelectedObject(glRenderer) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val glPoint = touchLayerPointToGl(change.position)
                                    viewModel.updateMovingActiveObject(glPoint.x, glPoint.y, glRenderer)
                                }
                            )
                        } else if (uiState.isMultiSelect) {
                            // One detector handles short taps and long-press drag selection so tap
                            // and marquee gestures do not compete for pointer-event consumption.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val pointerId = down.id
                                val startPoint = down.position
                                val startGlPoint = touchLayerPointToGl(startPoint)
                                val objectAtStart = glRenderer.findObjectAtScreenPos(startGlPoint.x, startGlPoint.y)
                                var currentPoint = startPoint
                                var marqueeStarted = false
                                var movedBeforeLongPress = false
                                var movingGroup = false
                                var ended = false
                                marqueeStart = null
                                marqueeEnd = null

                                while (!ended) {
                                    val elapsed = (android.os.SystemClock.uptimeMillis() - down.uptimeMillis).coerceAtLeast(0L)
                                    val remaining = (viewConfiguration.longPressTimeoutMillis - elapsed).coerceAtLeast(1L)
                                    val event = if (!marqueeStarted && !movedBeforeLongPress) {
                                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }

                                    if (event == null) {
                                        marqueeStarted = true
                                        marqueeStart = startPoint
                                        marqueeEnd = currentPoint
                                        continue
                                    }

                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null) continue
                                    currentPoint = change.position
                                    val movedDistance = (currentPoint - startPoint).getDistance()
                                    val eventElapsed = change.uptimeMillis - down.uptimeMillis

                                    if (!marqueeStarted && !movingGroup && movedDistance > viewConfiguration.touchSlop) {
                                        if (eventElapsed >= viewConfiguration.longPressTimeoutMillis) {
                                            marqueeStarted = true
                                            marqueeStart = startPoint
                                            marqueeEnd = currentPoint
                                        } else if (objectAtStart != null &&
                                            objectAtStart in uiState.selectedTargetIds && uiState.selectedTargetIds.size > 1
                                        ) {
                                            // Dragging a selected member moves the whole group directly,
                                            // without requiring the separate Move button or leaving multi-select.
                                            viewModel.startMovingSelectedObject(
                                                anchorTargetId = objectAtStart,
                                                deferEditorUntilDragEnd = true
                                            )
                                            movingGroup = true
                                            val glPoint = touchLayerPointToGl(currentPoint)
                                            viewModel.updateMovingActiveObject(glPoint.x, glPoint.y, glRenderer)
                                        } else {
                                            movedBeforeLongPress = true
                                        }
                                    }

                                    if (marqueeStarted) {
                                        marqueeEnd = currentPoint
                                        change.consume()
                                    }
                                    if (movingGroup) {
                                        change.consume()
                                        val glPoint = touchLayerPointToGl(currentPoint)
                                        viewModel.updateMovingActiveObject(glPoint.x, glPoint.y, glRenderer)
                                    }

                                    if (!change.pressed) {
                                        if (movingGroup) {
                                            viewModel.finishMovingSelectedObject(glRenderer)
                                        } else if (marqueeStarted) {
                                            val startGl = touchLayerPointToGl(startPoint)
                                            val endGl = touchLayerPointToGl(currentPoint)
                                            viewModel.addObjectsToSelection(
                                                glRenderer.findObjectsInScreenRect(startGl.x, startGl.y, endGl.x, endGl.y)
                                            )
                                        } else if (!movedBeforeLongPress && movedDistance <= viewConfiguration.touchSlop) {
                                            val glPoint = touchLayerPointToGl(currentPoint)
                                            viewModel.selectObjectAtScreenPos(glPoint.x, glPoint.y, glRenderer)
                                        }
                                        marqueeStart = null
                                        marqueeEnd = null
                                        ended = true
                                    }
                                }
                            }
                        } else {
                            detectTapGestures(
                                onTap = { offset ->
                                    val glPoint = touchLayerPointToGl(offset)
                                    viewModel.selectObjectAtScreenPos(glPoint.x, glPoint.y, glRenderer)
                                }
                            )
                        }
                    }
                }
                .pointerInput(
                    uiState.isSpectatorMode,
                    uiState.isEditingObject,
                    uiState.isRotationGizmoVisible,
                    uiState.isCleanSpectatorView,
                    uiState.isMultiSelect
                ) {
                    if (uiState.isCleanSpectatorView || (!uiState.isMultiSelect && !uiState.isEditingObject && !uiState.isRotationGizmoVisible)) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (abs(zoom - 1.0f) > 0.005f) {
                                viewModel.onZoomDelta((zoom - 1.0f) * 160f)
                            }
                            if (pan.x != 0f || pan.y != 0f) {
                                viewModel.onCameraSwipe(pan.x, pan.y)
                            }
                        }
                    }
                }
        ) {
            val start = marqueeStart
            val end = marqueeEnd
            if (uiState.isSpectatorMode && uiState.isMultiSelect && start != null && end != null) {
                val left = min(start.x, end.x)
                val top = min(start.y, end.y)
                val widthPx = max(1f, abs(end.x - start.x))
                val heightPx = max(1f, abs(end.y - start.y))
                val widthDp = with(LocalDensity.current) { widthPx.toDp() }
                val heightDp = with(LocalDensity.current) { heightPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(width = widthDp, height = heightDp)
                        .background(Color(0x2633A8FF))
                        .border(1.5.dp, Color(0xFF35A9FF), RoundedCornerShape(3.dp))
                        .zIndex(250f)
                )
            }
        }

        // Real model-load progress. The parser reports work as it reads the file, analyzes
        // geometry, builds mesh/texture data, and finalizes the loaded mesh.
        if (uiState.modelLoadingName != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 10.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xEE14221F))
                    .border(1.dp, Color(0xFF4DB6AC), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .zIndex(300f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Loading 3D model: ${uiState.modelLoadingName}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = uiState.modelLoadingProgress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF80CBC4),
                    trackColor = Color(0xFF33433F)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(uiState.modelLoadingStage, color = Color(0xFFD2E7E2), fontSize = 11.sp)
                    Text("${uiState.modelLoadingProgress}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Clean spectator mode intentionally hides every editor/HUD overlay below. The GL scene
        // and camera gestures remain active, with only an exit X rendered in the clean branch.
        if (!uiState.isCleanSpectatorView) {
        // 2b. Genshin Impact Furnishing Info Overlay (Top-Left)
        if (uiState.isSpectatorMode && uiState.isEditingObject) {
            TeapotObjectInfo(
                objectName = uiState.selectedTargetName,
                description = uiState.activeObjectDescription,
                height = uiState.activeObjectHeight,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // 3. TOP BARS (Standard Mode vs Genshin Teapot Spectator Mode)
        if (uiState.isSpectatorMode) {
            // GENSHIN IMPACT SERENITEA POT TOP BAR
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                TeapotTopBar(
                    areaName = uiState.teapotAreaName,
                    onAreaPrevious = { viewModel.cycleTeapotArea(forward = false) },
                    onAreaNext = { viewModel.cycleTeapotArea(forward = true) },
                    onExitSpectator = { viewModel.requestExitSpectator() },
                    onCameraSnapshot = { viewModel.toggleCleanSpectatorView() },
                    onRotateTarget = { viewModel.toggleRotationGizmo() },
                    onDeleteSelected = { viewModel.deleteSelectedObject() },
                    onSaveFurnishings = { viewModel.saveFurnishings() },
                    onExportGlb = { glbExportLauncher.launch("blockworld-scene.glb") },
                    onOpenImportPicker = {
                        modelPickerLauncher.launch(arrayOf("model/*", "application/octet-stream", "*/*"))
                    },
                    onMultiSelectToggle = { viewModel.toggleMultiSelect() },
                    isMultiSelect = uiState.isMultiSelect,
                    isRotationGizmoVisible = uiState.isRotationGizmoVisible,
                )
            }
        } else {
            // STANDARD TOP-LEFT SPECTATOR BUTTON
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 14.dp, top = 12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x66000000))
                        .border(1.5.dp, Color(0x88FFFFFF), RoundedCornerShape(14.dp))
                        .clickable { viewModel.toggleSpectatorMode() }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("btn_spectator_mode")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Toggle Spectator Mode",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SPECTATOR",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // STANDARD TOP-CENTER COORDINATES HUD
            if (uiState.settings.showCoordinates) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp)
                ) {
                    CoordinatesHUD(
                        playerCoords = uiState.playerCoords,
                        nearestTargetInfo = uiState.nearestTargetInfo,
                        score = uiState.score,
                        hitCombo = uiState.hitCombo,
                        playerHp = uiState.playerHp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // STANDARD TOP-RIGHT COMPASS & SETTINGS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 14.dp, top = 12.dp)
            ) {
                CompassWidget(
                    compassAngle = uiState.compassAngle,
                    onClick = { viewModel.snapCameraToNorth() },
                    modifier = Modifier.padding(end = 6.dp)
                )

                // Camera perspective toggle stays with the right-side gameplay buttons.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (uiState.isFirstPersonMode) Color(0xCC00897B) else Color(0x55000000))
                        .border(1.5.dp, if (uiState.isFirstPersonMode) Color(0xFF80CBC4) else Color(0x66FFFFFF), CircleShape)
                        .clickable { viewModel.toggleFirstPersonView() }
                        .testTag("btn_first_person_view")
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = if (uiState.isFirstPersonMode) "Switch to third-person view" else "Switch to first-person view",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = if (uiState.isFirstPersonMode) "1P" else "3P",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x55000000))
                        .border(1.5.dp, Color(0x66FFFFFF), CircleShape)
                        .testTag("btn_open_settings")
                ) {
                    IconButton(
                        onClick = { viewModel.onOpenSettings() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open Settings",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // ANIMATION CONTROL OVERLAY BEGIN
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, top = 56.dp)
                .zIndex(1000f)
        ) {
            AnimationQuickControl(
                menuExpanded = uiState.animationMenuExpanded,
                animationNames = uiState.availableAnimations,
                activeAnimationName = uiState.activeAnimationName,
                animationEnabled = uiState.animationEnabled,
                autoAnimationEnabled = uiState.autoAnimationEnabled,
                supportsSkeletalAnimation = uiState.supportsSkeletalAnimation,
                onToggleMenu = { viewModel.toggleAnimationMenu() },
                onCloseMenu = { viewModel.closeAnimationMenu() },
                onSetEnabled = { viewModel.setSelectedModelAnimationEnabled(it) },
                onSetAuto = { viewModel.setSelectedModelAutoAnimation(it) },
                onSelectAnimation = { viewModel.selectSelectedModelAnimation(it) }
            )
        }
        // ANIMATION CONTROL OVERLAY END
        // 4. RIGHT-SIDE ACTIONS
        // The four regular Teapot actions now live in the top bar. Keep the placement
        // confirmation controls on the right only while an object is actively being edited.
        if (uiState.isSpectatorMode && uiState.isEditingObject) {
            TeapotPlacementControls(
                isVerticalAdjustment = uiState.isVerticalAdjustment,
                onCancel = { viewModel.cancelActivePlacement() },
                onRotate = { viewModel.rotateActiveObject() },
                onToggleAdjustmentMode = { viewModel.toggleAdjustmentMode() },
                onConfirm = { viewModel.confirmActivePlacement() },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        } else if (!uiState.isSpectatorMode) {
            // Standard Zoom In / Out Controls
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
            ) {
                ZoomControls(
                    onZoomIn = { viewModel.onZoomIn() },
                    onZoomOut = { viewModel.onZoomOut() },
                    currentZoom = uiState.currentZoom
                )
            }
        }

        // 5. BOTTOM COMPONENTS (Standard Action Controls vs Teapot Furnishing Drawer)
        if (uiState.isSpectatorMode) {
            // GENSHIN IMPACT SERENITEA POT BOTTOM DRAWER
            // Drawer hides when an object is being placed/edited!
            AnimatedVisibility(
                visible = !uiState.isEditingObject,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .zIndex(10f)
            ) {
                TeapotBottomBar(
                    selectedTargetId = uiState.selectedTargetId,
                    selectedTargetName = if (uiState.isMultiSelect && uiState.selectedTargetIds.size > 1)
                        "${uiState.selectedTargetIds.size} objects selected" else uiState.selectedTargetName,
                    showTargetControls = uiState.settings.showTeapotTargetControls,
                    availableTargets = viewModel.getAvailableTargets(),
                    // Selecting a target must keep the drawer open; Move is a separate action.
                    onSelectTarget = { viewModel.selectTarget(it) },
                    onCycleTarget = { viewModel.cycleNextTarget() },
                    // A tap swaps the selected target's visual mesh without replacing its game behavior.
                    // Long-press + drag places a copy; PLACE COPY is a reliable non-drag fallback.
                    onSwapModel = { viewModel.swapModelForSelectedTarget(it) },
                    onPlaceModel = { viewModel.spawnAndEditModel(it, glRenderer) },
                    onStartMoveSelected = { viewModel.startMovingSelectedObject() },
                    isMovingSelected = uiState.isMovingObject,
                    onOpenImportPicker = {
                        // Open file picker supporting .glb, .obj, .fbx, and generic 3D models
                        modelPickerLauncher.launch(
                            arrayOf(
                                "model/*",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    mediaItems = uiState.mediaItems,
                    onOpenImagePicker = { imageMediaPickerLauncher.launch(arrayOf("image/*")) },
                    onOpenVideoPicker = { videoMediaPickerLauncher.launch(arrayOf("video/*")) },
                    onPlaceMedia = { mediaId -> viewModel.placeMedia(mediaId) },
                    onModelDragStart = { modelId, offset ->
                        draggingModelId = modelId
                        dragScreenPos = offset
                        glRenderer.hoverPreviewModelId = modelId
                        val glPoint = rootPointToGl(offset)
                        viewModel.onModelDragHover(glPoint.x, glPoint.y, glRenderer, modelId)
                    },
                    onModelDrag = { dragAmount ->
                        dragScreenPos += dragAmount
                        val glPoint = rootPointToGl(dragScreenPos)
                        viewModel.onModelDragHover(glPoint.x, glPoint.y, glRenderer, draggingModelId)
                    },
                    onModelDragEnd = {
                        draggingModelId?.let { mId ->
                            val glPoint = rootPointToGl(dragScreenPos)
                            viewModel.onModelDragEnd(glPoint.x, glPoint.y, mId, glRenderer)
                        }
                        draggingModelId = null
                        glRenderer.hoverPreviewPosition = null
                        glRenderer.hoverPreviewSize = null
                        glRenderer.hoverPreviewModelId = null
                    },
                    onModelDragCancel = {
                        viewModel.onModelDragCancel(glRenderer)
                        draggingModelId = null
                        glRenderer.hoverPreviewPosition = null
                        glRenderer.hoverPreviewSize = null
                        glRenderer.hoverPreviewModelId = null
                    },
                    isJoystickActive = uiState.isJoystickActive
                )
            }
        } else {
            // STANDARD ACTION CONTROLS (Jump, Attack, Dash)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 12.dp, bottom = 10.dp)
                    .zIndex(10f)
            ) {
                ActionControls(
                    onJump = { viewModel.onJump() },
                    onAttack = { viewModel.onAttack() },
                    onDash = { viewModel.onDash() },
                    onFireball = { viewModel.onFireball() },
                    modifier = Modifier.size(160.dp)
                )
            }
        }

        // 6. FLOATING 3D MODEL DRAG PREVIEW (Follows user's finger during long-press drag)
        val currentDraggingId = draggingModelId
        if (currentDraggingId != null) {
            // Do not remember the object: a lazy gallery stub is replaced with real mesh data
            // asynchronously. The load-completion state update triggers this preview to refresh.
            val draggedModel = com.example.teapot.TeapotModelRegistry.getModel(currentDraggingId)
            if (draggedModel != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (dragScreenPos.x - 38.dp.toPx()).roundToInt(),
                                (dragScreenPos.y - 75.dp.toPx()).roundToInt()
                            )
                        }
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xD0004D40))
                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(14.dp))
                        .zIndex(200f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        com.example.teapot.ModelMeshPreview(
                            model = draggedModel,
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = draggedModel.name,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 7. BOTTOM-LEFT: Single Virtual Joystick (Highest Z-index 100f so it is never obstructed)
        // Moves character in standard mode; moves spectator camera with zoom-dependent speed in spectator mode
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, bottom = 14.dp)
                .zIndex(100f)
        ) {
            VirtualJoystick(
                onMove = { dx, dy -> viewModel.onJoystickMoved(dx, dy) },
                sizeDp = 130
            )
        }

        // 7. GOLDEN SAVE NOTIFICATION BANNER (Genshin Style)
        AnimatedVisibility(
            visible = uiState.saveToastMessage != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xEE1B2421))
                    .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.saveToastMessage ?: "",
                        color = Color(0xFFFFD54F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 8. Settings Dialog
        if (uiState.isSettingsOpen) {
            SettingsDialog(
                settings = uiState.settings,
                onUpdateSettings = { viewModel.updateSettings(it) },
                onResetPlayer = { viewModel.onResetPlayer() },
                onResetWorld = { viewModel.onResetWorld() },
                onDismiss = { viewModel.onCloseSettings() }
            )
        }

        // 9. EXIT SPECTATOR CONFIRMATION POPUP PANEL (Requirement 3: save, cancel, discard buttons when user presses X)
        if (uiState.showExitConfirmationDialog) {
            ExitSpectatorDialog(
                onSaveAndExit = { viewModel.saveAndExitSpectator() },
                onDiscardAndExit = { viewModel.discardAndExitSpectator() },
                onCancel = { viewModel.dismissExitSpectatorDialog() }
            )
        }
        } else {
            // Minimal clean spectator overlay: this is the only Compose UI shown in this mode.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xDD14202B))
                    .border(2.dp, Color(0xCCFFFFFF), CircleShape)
                    .clickable { viewModel.toggleCleanSpectatorView() }
                    .testTag("btn_exit_clean_spectator_view")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit clean spectator view",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Safe helper to query the real filename from a Content Uri.
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
    }
    return uri.path?.substringAfterLast('/')
}
