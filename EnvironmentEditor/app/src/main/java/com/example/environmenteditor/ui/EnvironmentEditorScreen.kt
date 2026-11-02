
package com.example.environmenteditor.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.environmenteditor.data.AssetDefinition
import com.example.environmenteditor.data.EditorUiState
import com.example.environmenteditor.data.PlacementSettings
import com.example.environmenteditor.data.SceneObject
import com.example.environmenteditor.data.Vec3
import com.example.environmenteditor.editor.EditorViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import androidx.compose.ui.window.DialogProperties

@Composable
fun EnvironmentEditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var longPressObjectId by remember { mutableStateOf<String?>(null) }
    var renameObjectId by remember { mutableStateOf<String?>(null) }
    var showSurfacePanel by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = resolveDisplayName(context, uri)
            viewModel.importModel(uri, name)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090C10))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopEditorBar(
                state = state,
                onImport = {
                    importLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
                },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = viewModel::saveNow,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SceneViewport(
                    state = state,
                    onSelect = viewModel::selectObject,
                    onLongPress = { longPressObjectId = it },
                )

                state.selectedObjectId?.let { selectedId ->
                    val selected = state.scene.objects.firstOrNull { it.id == selectedId }
                    if (selected != null) {
                        TransformHud(
                            modifier = Modifier.align(Alignment.TopEnd),
                            sceneObject = selected,
                            surfaceHeight = state.scene.surfaces.firstOrNull()?.heightY ?: 0f,
                            onApplyTransform = viewModel::applyTransformFields,
                            onNudgePosition = viewModel::nudgePosition,
                            onNudgeRotation = viewModel::nudgeRotation,
                            onOpenSurface = { showSurfacePanel = true },
                        )

                        Joystick(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 18.dp, bottom = 18.dp),
                            onStart = { viewModel.beginInteractiveTransform(selected.id, "Move object") },
                            onMove = { dx, dy ->
                                viewModel.updatePositionBy(
                                    selected.id,
                                    deltaX = dx * 0.035f,
                                    deltaY = 0f,
                                    deltaZ = dy * 0.035f,
                                )
                            },
                            onEnd = viewModel::endInteractiveTransform,
                        )
                    }
                }
            }

            AssetBrowser(
                assets = state.assets,
                onImport = {
                    importLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
                },
                onPlace = viewModel::placeAsset,
            )
        }

        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::clearMessages)
        }

        state.infoMessage?.let { message ->
            if (!state.isSaved || message == "Scene saved") {
                InfoBanner(message = message, onDismiss = viewModel::clearMessages)
            }
        }

        longPressObjectId?.let { objectId ->
            ObjectOptionsMenu(
                objectName = state.scene.objects.firstOrNull { it.id == objectId }?.name ?: "Object",
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
                onConfirm = { newName ->
                    viewModel.renameObject(objectId, newName)
                    renameObjectId = null
                },
            )
        }
    }
}

@Composable
private fun TopEditorBar(
    state: EditorUiState,
    onImport: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = Color(0xFF11161D)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("☰", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Column(modifier = Modifier.weight(1f)) {
                Text(state.scene.name, color = Color.White)
                Text(
                    text = "${state.scene.objects.size} objects${if (state.isSaved) "" else " • unsaved"}",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
            Button(onClick = onImport) { Text("Import") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

@Composable
private fun SceneViewport(
    state: EditorUiState,
    onSelect: (String?) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(8f, 7f, 12f)
        lookAt(Position(0f, 0f, 0f))
    }
    val cameraManipulator = rememberCameraManipulator(
        orbitRadius = 15f,
        targetPosition = Position(0f, 0f, 0f),
    )
    val groundMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            Color(0.16f, 0.19f, 0.23f, 1f),
            metallic = 0f,
            roughness = 0.9f,
        )
    }
    val gizmoMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0.16f, 0.55f, 1f, 1f))
    }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        cameraManipulator = cameraManipulator,
        mainLightNode = rememberMainLightNode(engine),
        fillLightNode = null,
        autoCenterContent = false,
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, node ->
                onSelect(node?.name)
            },
            onDoubleTap = { _, node ->
                onSelect(node?.name)
            },
            onLongPress = { _, node ->
                node?.name?.takeIf { name ->
                    state.scene.objects.any { it.id == name }
                }?.let(onLongPress)
            },
        ),
    ) {
        PlaneNode(
            size = Size(40f, 40f),
            materialInstance = groundMaterial,
            position = Position(y = state.scene.surfaces.firstOrNull()?.heightY ?: 0f),
        )

        state.scene.objects.forEach { sceneObject ->
            val asset = state.assets.firstOrNull { it.id == sceneObject.assetId }
            if (asset == null) return@forEach
            androidx.compose.runtime.key(sceneObject.id, asset.filePath) {
                val instance = rememberModelInstance(
                    modelLoader,
                    android.net.Uri.fromFile(java.io.File(asset.filePath)).toString(),
                )
                instance?.let {
                    /*
                     * SceneView 4.38 exposes ModelNode transforms as part of the
                     * composable API. Keep this call limited to the stable
                     * rendering parameters so a model-loading problem cannot
                     * break the rest of the editor UI.
                     */
                    ModelNode(
                        it,
                        false,
                        null,
                        false,
                        1f,
                        null,
                        Position(0f, -1f, 0f),
                        sceneObject.transform.position.toScenePosition(),
                        sceneObject.transform.rotation.toSceneRotation(),
                        sceneObject.transform.scale.toSceneScale(),
                        true,
                        false
                    )
                }
            }
        }

        state.selectedObjectId?.let { selectedId ->
            state.scene.objects.firstOrNull { it.id == selectedId }?.let { obj ->
//                SelectionGizmo(obj, gizmoMaterial)
                SelectionGizmo(
                    engine = engine,
                    position = obj.transform.position.toScenePosition(),
                    material = gizmoMaterial
                )

            }
        }
    }
}

//@Composable
//private fun SelectionGizmo(
//    obj: SceneObject,
//    material: com.google.android.filament.MaterialInstance,
//) {
//    val p = obj.transform.position.toScenePosition()
//
//    /*
//     * Use stable CubeNode primitives for the MVP gizmo.
//     * This avoids version-sensitive TorusNode/LineNode/ConeNode constructors.
//     * The three thin cubes represent X/Y/Z handles.
//     */
//    CubeNode(
//        size = Size(1.2f, 0.025f, 0.025f),
//        materialInstance = material,
//        position = Position(p.x + 0.6f, p.y + 0.02f, p.z)
//    )
//
//    CubeNode(
//        size = Size(0.025f, 1.2f, 0.025f),
//        materialInstance = material,
//        position = Position(p.x, p.y + 0.6f, p.z)
//    )
//
//    CubeNode(
//        size = Size(0.025f, 0.025f, 1.2f),
//        materialInstance = material,
//        position = Position(p.x, p.y + 0.02f, p.z + 0.6f)
//    )
//
//    SphereNode(
//        radius = 0.08f,
//        materialInstance = material,
//        position = Position(p.x, p.y + 0.02f, p.z)
//    )
//}

@Composable
private fun TransformHud(
    sceneObject: SceneObject,
    modifier: Modifier = Modifier,
    surfaceHeight: Float,
    onApplyTransform: (String, Vec3, Vec3) -> Unit,
    onNudgePosition: (String, Float, Float, Float) -> Unit,
    onNudgeRotation: (String, Float, Float, Float) -> Unit,
    onOpenSurface: () -> Unit,
) {
    var x by remember(sceneObject.id, sceneObject.transform.position.x) { mutableStateOf(format(sceneObject.transform.position.x)) }
    var y by remember(sceneObject.id, sceneObject.transform.position.y) { mutableStateOf(format(sceneObject.transform.position.y)) }
    var z by remember(sceneObject.id, sceneObject.transform.position.z) { mutableStateOf(format(sceneObject.transform.position.z)) }
    var rx by remember(sceneObject.id, sceneObject.transform.rotation.x) { mutableStateOf(format(sceneObject.transform.rotation.x)) }
    var ry by remember(sceneObject.id, sceneObject.transform.rotation.y) { mutableStateOf(format(sceneObject.transform.rotation.y)) }
    var rz by remember(sceneObject.id, sceneObject.transform.rotation.z) { mutableStateOf(format(sceneObject.transform.rotation.z)) }

    Card(
        modifier = modifier
            .padding(12.dp)
            .fillMaxWidth(0.46f),
        colors = CardDefaults.cardColors(containerColor = Color(0xE611161D)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(sceneObject.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("X ←  •  Y ↑  •  Z →", color = Color(0xFF7FBFFF), style = MaterialTheme.typography.labelSmall)
            Text("Position", color = Color.LightGray)
            TransformRow(
                x = x,
                y = y,
                z = z,
                onX = { x = it },
                onY = { y = it },
                onZ = { z = it },
            )
            Text("Rotation", color = Color.LightGray)
            TransformRow(
                x = rx,
                y = ry,
                z = rz,
                suffix = "°",
                onX = { rx = it },
                onY = { ry = it },
                onZ = { rz = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val position = Vec3(x.toFloatOrNull() ?: 0f, y.toFloatOrNull() ?: 0f, z.toFloatOrNull() ?: 0f)
                        val rotation = Vec3(rx.toFloatOrNull() ?: 0f, ry.toFloatOrNull() ?: 0f, rz.toFloatOrNull() ?: 0f)
                        onApplyTransform(sceneObject.id, position, rotation)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
                TextButton(onClick = onOpenSurface) { Text("Surface") }
            }
            Text("Move gizmo", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NudgeButton("X−") { onNudgePosition(sceneObject.id, -0.25f, 0f, 0f) }
                NudgeButton("X+") { onNudgePosition(sceneObject.id, 0.25f, 0f, 0f) }
                NudgeButton("Z−") { onNudgePosition(sceneObject.id, 0f, 0f, -0.25f) }
                NudgeButton("Z+") { onNudgePosition(sceneObject.id, 0f, 0f, 0.25f) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NudgeButton("Y−") { onNudgePosition(sceneObject.id, 0f, -0.25f, 0f) }
                NudgeButton("Y+") { onNudgePosition(sceneObject.id, 0f, 0.25f, 0f) }
                NudgeButton("↺") { onNudgeRotation(sceneObject.id, 0f, -15f, 0f) }
                NudgeButton("↻") { onNudgeRotation(sceneObject.id, 0f, 15f, 0f) }
            }
            Text(
                text = "Surface Y ${format(surfaceHeight)}  •  Object bottom ${format(sceneObject.transform.position.y)}",
                color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun TransformRow(
    x: String,
    y: String,
    z: String,
    suffix: String = "",
    onX: (String) -> Unit,
    onY: (String) -> Unit,
    onZ: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallNumberField("X", x, suffix, onX, Modifier.weight(1f))
        SmallNumberField("Y", y, suffix, onY, Modifier.weight(1f))
        SmallNumberField("Z", z, suffix, onZ, Modifier.weight(1f))
    }
}

@Composable
private fun SmallNumberField(
    label: String,
    value: String,
    suffix: String,
    onValue: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        label = { Text(label) },
        suffix = if (suffix.isBlank()) null else ({ Text(suffix) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun Joystick(
    modifier: Modifier,
    onStart: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onEnd: () -> Unit,
) {
    val radiusPx = remember { mutableFloatStateOf(1f) }
    var stick by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(108.dp)
            .background(Color(0x5525303E), CircleShape)
            .border(2.dp, Color(0xAA6AAEFF), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        radiusPx.floatValue = minOf(size.width, size.height) / 2f
                        stick = it
                        onStart()
                    },
                    onDrag = { change, dragAmount ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        var next = stick + dragAmount
                        val maxRadius = minOf(size.width, size.height) * 0.38f
                        val vector = next - center
                        if (vector.getDistance() > maxRadius) {
                            next = center + vector / vector.getDistance() * maxRadius
                        }
                        stick = next
                        val delta = next - center
                        val deadZone = maxRadius * 0.12f
                        if (delta.getDistance() > deadZone) {
                            onMove(delta.x / maxRadius, delta.y / maxRadius)
                        }
                    },
                    onDragEnd = {
                        stick = Offset.Zero
                        onEnd()
                    },
                    onDragCancel = {
                        stick = Offset.Zero
                        onEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF2687FF), CircleShape)
        )
    }
}

@Composable
private fun AssetBrowser(
    assets: List<AssetDefinition>,
    onImport: () -> Unit,
    onPlace: (AssetDefinition) -> Unit,
) {
    Surface(color = Color(0xFF11161D)) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssetCard(
                        title = "Import 3D Model",
                        subtitle = "GLB",
                        onClick = onImport,
                        isImport = true,
                    )
                }
                items(assets, key = { it.id }) { asset ->
                    AssetCard(
                        title = asset.name,
                        subtitle = asset.format.uppercase(),
                        onClick = { onPlace(asset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isImport: Boolean = false,
) {
    Card(
        modifier = Modifier
            .size(width = 118.dp, height = 118.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (isImport) Color(0xFF16263A) else Color(0xFF171D25)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(Color(0xFF0C1117), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isImport) "+" else "3D", color = Color(0xFF80B9FF), style = MaterialTheme.typography.headlineSmall)
            }
            Text(title, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            Text(subtitle, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ObjectOptionsMenu(
    objectName: String,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            modifier = Modifier.align(Alignment.Center),
        ) {
            DropdownMenuItem(text = { Text("Rename $objectName") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = onDuplicate)
            DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
        }
    }
}

@Composable
private fun SurfaceSettingsDialog(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val selected = state.scene.objects.firstOrNull { it.id == state.selectedObjectId }
    if (selected == null) return

    var heightText by remember(state.scene.surfaces.firstOrNull()?.heightY) {
        mutableStateOf(format(state.scene.surfaces.firstOrNull()?.heightY ?: 0f))
    }
    var settings by remember(selected.id, selected.placement) { mutableStateOf(selected.placement) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Surface placement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("Surface Height (Y)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                PlacementSwitch("Surface Lock", settings.surfaceLock) { settings = settings.copy(surfaceLock = it) }
                PlacementSwitch("Snap to Surface", settings.snapToSurface) { settings = settings.copy(snapToSurface = it) }
                PlacementSwitch("Prevent Below Surface", settings.preventBelowSurface) { settings = settings.copy(preventBelowSurface = it) }
                PlacementSwitch("Allow Floating", settings.allowFloating) { settings = settings.copy(allowFloating = it) }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.setSurfaceHeight(heightText.toFloatOrNull() ?: 0f)
                viewModel.setPlacementSettings(selected.id, settings)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

@Composable
private fun PlacementSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename object") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .imePadding(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1518)),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun InfoBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF132234)),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else uri.lastPathSegment
    }
}

private fun format(value: Float): String = "%.2f".format(java.util.Locale.US, value)

private fun Vec3.toScenePosition() = Position(x, y, z)
private fun Vec3.toSceneRotation() = Rotation(x, y, z)
private fun Vec3.toSceneScale() = Scale(x, y, z)


