package com.example.environmenteditor.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.example.environmenteditor.data.AssetDefinition
import com.example.environmenteditor.editor.EditorViewModel

private const val TOP_DROP_GUARD_DP = 72
private const val BOTTOM_DROP_GUARD_DP = 94

@Composable
fun EnvironmentEditorScreen(viewModel: EditorViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var joystickX by remember { mutableStateOf(0f) }
    var joystickY by remember { mutableStateOf(0f) }
    var draggingAsset by remember { mutableStateOf<AssetDefinition?>(null) }
    var dragPositionInRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var dropRequest by remember { mutableStateOf<DropRequest?>(null) }
    var dropNonce by remember { mutableIntStateOf(0) }
    var resetCameraToken by remember { mutableIntStateOf(0) }
    var longPressObjectId by remember { mutableStateOf<String?>(null) }
    var renameObjectId by remember { mutableStateOf<String?>(null) }
    var showSurfacePanel by remember { mutableStateOf(false) }
    var heldObjectWasMoved by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportTopLeft by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val topGuardPx = with(density) { TOP_DROP_GUARD_DP.dp.toPx() }
    val bottomGuardPx = with(density) { BOTTOM_DROP_GUARD_DP.dp.toPx() }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importModel(uri, resolveDisplayName(context, uri))
        }
    }

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            kotlinx.coroutines.delay(1_800L)
            viewModel.clearMessages()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF6BA8D4))
            .onGloballyPositioned { coordinates -> rootSize = coordinates.size },
    ) {
        SceneViewport(
            state = state,
            joystickX = joystickX,
            joystickY = joystickY,
            draggingAsset = draggingAsset,
            dragPositionInRoot = dragPositionInRoot,
            dropRequest = dropRequest,
            resetCameraToken = resetCameraToken,
            heldObjectId = state.heldObjectId,
            onSelect = { id ->
                longPressObjectId = null
                viewModel.selectObject(id)
            },
            onHeldObjectChanged = { objectId ->
                if (objectId != null) {
                    heldObjectWasMoved = false
                    viewModel.selectObject(objectId)
                    viewModel.beginInteractiveTransform(objectId, "Move object")
                    viewModel.setHeldObject(objectId)
                } else {
                    val releasedObjectId = state.heldObjectId
                    viewModel.endInteractiveTransform()
                    viewModel.setHeldObject(null)
                    if (releasedObjectId != null && !heldObjectWasMoved) {
                        longPressObjectId = releasedObjectId
                    }
                }
            },
            onMoveHeldObject = { objectId, dx, dz ->
                heldObjectWasMoved = true
                viewModel.updatePositionBy(objectId, dx, 0f, dz)
            },
            onDropComputed = { asset, position ->
                viewModel.placeAssetAt(asset, position)
                dropRequest = null
                draggingAsset = null
                dragPositionInRoot = null
            },
            onDropFailed = {
                dropRequest = null
                draggingAsset = null
                dragPositionInRoot = null
                viewModel.clearMessages()
            },
            onViewportBoundsChanged = { topLeft, size ->
                viewportTopLeft = topLeft
                viewportSize = size
            },
        )

        EnvironmentEditorTopBar(
            modifier = Modifier.align(Alignment.TopCenter),
            state = state,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onDelete = {
                state.selectedObjectId?.let(viewModel::deleteObject)
            },
            onResetCamera = {
                resetCameraToken++
            },
            onImport = {
                importLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
            },
            onSave = viewModel::saveNow,
        )

        state.selectedObjectId?.let { selectedId ->
            state.scene.objects.firstOrNull { it.id == selectedId }?.let { selected ->
                TransformHud(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 8.dp)
                        .fillMaxWidth(0.43f),
                    sceneObject = selected,
                    surfaceHeight = state.scene.surfaces.firstOrNull()?.heightY ?: 0f,
                    onApplyTransform = viewModel::applyTransformFields,
                    onNudgePosition = viewModel::nudgePosition,
                    onNudgeRotation = viewModel::nudgeRotation,
                    onOpenSurface = { showSurfacePanel = true },
                    onDelete = { viewModel.deleteObject(selected.id) },
                )
            }
        }

        GameJoystick(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 13.dp, bottom = 92.dp)
                .zIndex(30f),
            onValueChanged = { x, y ->
                joystickX = x
                joystickY = y
            },
            onReleased = {
                joystickX = 0f
                joystickY = 0f
            },
        )

        AssetBrowser(
            modifier = Modifier.align(Alignment.BottomCenter),
            assets = state.assets,
            onImport = {
                importLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
            },
            onPlace = viewModel::placeAsset,
            onDragStart = { asset, center ->
                draggingAsset = asset
                dragPositionInRoot = center
            },
            onDrag = { dragAmount ->
                dragPositionInRoot = (dragPositionInRoot ?: androidx.compose.ui.geometry.Offset.Zero) + dragAmount
            },
            onDragEnd = {
                val point = dragPositionInRoot
                val viewportRight = viewportTopLeft.x + viewportSize.width
                val viewportBottom = viewportTopLeft.y + viewportSize.height
                val insideViewport = point != null &&
                    point.x >= viewportTopLeft.x &&
                    point.x <= viewportRight &&
                    point.y >= viewportTopLeft.y &&
                    point.y <= viewportBottom &&
                    point.y > topGuardPx &&
                    point.y < rootSize.height - bottomGuardPx

                if (insideViewport && draggingAsset != null) {
                    dropNonce++
                    dropRequest = DropRequest(
                        nonce = dropNonce,
                        asset = draggingAsset!!,
                        rootPosition = point!!,
                    )
                } else {
                    draggingAsset = null
                    dragPositionInRoot = null
                }
            },
        )

        draggingAsset?.let { asset ->
            dragPositionInRoot?.let { point ->
                Box(
                    modifier = Modifier
                        .zIndex(40f)
                        .offset {
                            IntOffset((point.x - 38f).roundToInt(), (point.y - 38f).roundToInt())
                        }
                        .size(76.dp)
                        .background(Color(0xAA18304A), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = asset.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            MessageBanner(
                message = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 68.dp, start = 8.dp, end = 8.dp),
            )
        }

        state.infoMessage?.let { message ->
            if (message != "Scene saved") {
                MessageBanner(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 68.dp, start = 8.dp, end = 8.dp),
                )
            }
        }

        longPressObjectId?.let { objectId ->
            state.scene.objects.firstOrNull { it.id == objectId }?.let { selectedObject ->
                ObjectOptionsDialog(
                    objectName = selectedObject.name,
                    onDismiss = { longPressObjectId = null },
                    onRename = {
                        renameObjectId = objectId
                        longPressObjectId = null
                    },
                    onDuplicate = {
                        viewModel.duplicateObject(objectId)
                        longPressObjectId = null
                    },
                    onDelete = {
                        viewModel.deleteObject(objectId)
                        longPressObjectId = null
                    },
                )
            }
        }

        if (showSurfacePanel) {
            SurfaceSettingsDialog(
                state = state,
                viewModel = viewModel,
                onDismiss = { showSurfacePanel = false },
            )
        }

        renameObjectId?.let { objectId ->
            RenameDialog(
                initialName = state.scene.objects.firstOrNull { it.id == objectId }?.name.orEmpty(),
                onDismiss = { renameObjectId = null },
                onConfirm = { name ->
                    viewModel.renameObject(objectId, name)
                    renameObjectId = null
                },
            )
        }
    }
}

@Composable
private fun MessageBanner(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xB0101822)),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else uri.lastPathSegment
    }
}
