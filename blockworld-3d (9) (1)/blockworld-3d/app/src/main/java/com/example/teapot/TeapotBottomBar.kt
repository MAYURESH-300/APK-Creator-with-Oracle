package com.example.teapot

import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.example.media.ImportedMedia
import com.example.media.MediaKind

/**
 * Bottom Furnishing Drawer matching Genshin Impact Serenitea Pot Placement Screen:
 * - Subtle low opacity (~0.30 - 0.40) matching the virtual joystick.
 * - Placed with start padding so the bottom-left Virtual Joystick is never covered.
 * - Clean category tabs with rounded pill indicator for active tab.
 * - No subcategory icon strip; the category row remains visible.
 * - Horizontal cards with load coin `🪙 20` and quantity count `×1`.
 * - Import is available in the top action bar and as a card in the Imported tab.
 * - Target selector banner with `[Cycle Target]` and glowing blue outline status.
 */
@Composable
fun TeapotBottomBar(
    selectedTargetId: String,
    selectedTargetName: String,
    availableTargets: List<Pair<String, String>>,
    onSelectTarget: (String) -> Unit,
    onCycleTarget: () -> Unit,
    onSwapModel: (modelId: String) -> Unit,
    onPlaceModel: (modelId: String) -> Unit = {},
    onOpenImportPicker: () -> Unit,
    mediaItems: List<ImportedMedia> = emptyList(),
    onOpenImagePicker: () -> Unit = {},
    onOpenVideoPicker: () -> Unit = {},
    onPlaceMedia: (mediaId: String) -> Unit = {},
    onModelDragStart: (modelId: String, screenOffset: Offset) -> Unit = { _, _ -> },
    onModelDrag: (dragAmount: Offset) -> Unit = {},
    onModelDragEnd: () -> Unit = {},
    onModelDragCancel: () -> Unit = {},
    onStartMoveSelected: () -> Unit = {},
    isMovingSelected: Boolean = false,
    @Suppress("UNUSED_PARAMETER") showTargetControls: Boolean = true,
    isJoystickActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("Pre Models") }
    val categories = remember {
        listOf("Media" , "Pre Models",  "Imported")
    }

    // Re-read the registry on recomposition. A remembered list would stay stale after an
    // asynchronous import completed, making a successfully imported model look missing.
    val models = if (selectedCategory == "Media") {
        emptyList()
    } else if (selectedCategory == "Imported") {
        TeapotModelRegistry.getModels().filter { it.isUserImported }
    } else if (selectedCategory == "Pre Models") {
        TeapotModelRegistry.getModels().filter { !it.isUserImported }
    } else if (selectedCategory == "Landform" || selectedCategory == "Building" || selectedCategory == "Companion" || selectedCategory == "Landscape") {
        TeapotModelRegistry.getModelsByCategory(selectedCategory)
    } else {
        TeapotModelRegistry.getModels()
    }

    var isTargetDropdownOpen by remember { mutableStateOf(false) }

    // Smooth hide animation when joystick is active
    val animAlpha by animateFloatAsState(
        targetValue = if (isJoystickActive) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "teapot_bottom_alpha"
    )

    AnimatedVisibility(
        visible = !isJoystickActive,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(animAlpha)
                // Low opacity gradient matching the Genshin Serenitea Pot screenshot and joystick
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x00000000),
                            Color(0x333A2E26), // Low-opacity soft warm brown
                            Color(0x483A2E26)
                        )
                    )
                )
                .padding(start = 135.dp, end = 12.dp, bottom = 6.dp) // Start padding clears joystick area!
        ) {
            // 2. Genshin Impact Category Tabs Row (Matching Screenshot)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                categories.forEach { cat ->
                    val isSelected = cat == selectedCategory
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xE8FFFFFF)) // White pill matching screenshot
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                } else {
                                    Modifier
                                        .clickable { selectedCategory = cat }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                }
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = cat,
                                color = if (isSelected) Color(0xFF2E241F) else Color(0xDDFFFFFF),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            // Pink alert dot matching screenshot
                            if (cat == "Media" || cat == "Landform" || cat == "Landscape" || cat == "Companion" || cat == "Imported") {
                                Spacer(modifier = Modifier.width(3.dp))
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF3D71))
                                )
                            }
                        }
                    }
                }
            }

            // Subcategory icon strip removed; keep the main category tabs above visible.

            // 3. Horizontal model gallery. Keep the category row clean without the old helper banner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Media import actions. SAF selections are copied into the app's private media library.
                if (selectedCategory == "Media") {
                    MediaActionCard(
                        title = "Import Image",
                        subtitle = "PNG / JPG / WebP",
                        onClick = onOpenImagePicker,
                        tag = "card_import_image"
                    )
                    MediaActionCard(
                        title = "Import Video",
                        subtitle = "MP4 / WebM",
                        onClick = onOpenVideoPicker,
                        tag = "card_import_video"
                    )
                    mediaItems.forEach { media ->
                        val thumbnailPath = File(File(context.filesDir, "media_library/thumbnails"), media.thumbnailName).absolutePath
                        val thumbnail = remember(thumbnailPath) {
                            BitmapFactory.decodeFile(thumbnailPath)?.asImageBitmap()
                        }
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(108.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x33202020))
                                .border(1.dp, Color(0x554FC3F7), RoundedCornerShape(10.dp))
                                .clickable { onPlaceMedia(media.id) }
                                .padding(5.dp)
                                .testTag("card_media_${media.id}")
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Top,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = "Preview of ${media.displayName}",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(54.dp)
                                            .clip(RoundedCornerShape(6.dp)).background(Color(0xFF18212A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(if (media.kind == MediaKind.VIDEO) "VIDEO" else "IMAGE", color = Color.White, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = media.displayName,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = if (media.kind == MediaKind.VIDEO) "VIDEO • ${formatMediaDuration(media.durationMs)}" else "IMAGE • ${media.width}×${media.height}",
                                    color = Color(0xFFB3E5FC),
                                    fontSize = 7.sp,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(17.dp)
                                        .clip(RoundedCornerShape(4.dp)).background(Color(0x4438BDF8))
                                        .border(0.6.dp, Color(0x6680D8FF), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("PLACE", color = Color(0xFFE3F6FF), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Card 0: Import 3D model card (kept separate from the new Media library).
                if (selectedCategory == "Imported") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(88.dp)
                            .height(108.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x28004D40))
                            .border(1.dp, Color(0x4080CBC4), RoundedCornerShape(10.dp))
                            .clickable { onOpenImportPicker() }
                            .padding(6.dp)
                            .testTag("card_import_new")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color(0xFF80CBC4),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Import File\n(.glb / .obj)",
                                color = Color(0xFFE0F2F1),
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 11.sp
                            )
                        }
                    }
                }

                // Existing 3D model cards remain unchanged outside the Media category.
                if (selectedCategory != "Media") {
                models.forEach { model ->
                    val isCurrentSwap = ModelSwapManager.getActiveSwapModelId(selectedTargetId) == model.id
                    var cardScreenPos by remember { mutableStateOf(Offset.Zero) }

                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .height(108.dp)
                            .onGloballyPositioned { coordinates ->
                                cardScreenPos = coordinates.positionInRoot()
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCurrentSwap) Color(0x4000E5FF) else Color(0x22000000)
                            )
                            .border(
                                1.dp,
                                if (isCurrentSwap) Color(0xFF00E5FF) else Color(0x25FFFFFF),
                                RoundedCornerShape(10.dp)
                            )
                            // Keep click first and the long-press pointer detector inside it in the
                            // modifier chain. This lets the inner drag detector consume the long-press
                            // gesture before clickable can turn the same gesture into a model swap.
                            .clickable { onSwapModel(model.id) }
                            .pointerInput(model.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { localOffset ->
                                        onModelDragStart(model.id, cardScreenPos + localOffset)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onModelDrag(dragAmount)
                                    },
                                    onDragEnd = {
                                        onModelDragEnd()
                                    },
                                    onDragCancel = {
                                        onModelDragCancel()
                                    }
                                )
                            }
                            .padding(4.dp)
                            .testTag("card_model_${model.id}")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Render a small isometric projection of the actual mesh triangles,
                            // rather than a placeholder emoji or a generic box icon.
                            ModelMeshPreview(
                                model = model,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF18212A))
                                    .border(0.8.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            // Model Name
                            Text(
                                text = model.name,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(3.dp))

                            // Reliable fallback for devices where a long-press drag is awkward:
                            // tap PLACE to spawn a copy in front of the camera and edit its position.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x4438BDF8))
                                    .border(0.6.dp, Color(0x6680D8FF), RoundedCornerShape(4.dp))
                                    .clickable { onPlaceModel(model.id) }
                                    .testTag("btn_place_model_${model.id}")
                            ) {
                                Text(
                                    text = "PLACE COPY",
                                    color = Color(0xFFE3F6FF),
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Bottom Info Badge: Adeptal Load `🪙 20` and Quantity `×1`
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x35000000))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "🪙", fontSize = 7.sp)
                                    Text(
                                        text = "${model.loadCost}",
                                        color = Color(0xFFFFD54F),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = if (isCurrentSwap) "ACTIVE" else "×${model.count}",
                                    color = if (isCurrentSwap) Color(0xFF00E5FF) else Color(0xCCFFFFFF),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}


@Composable
private fun MediaActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String
) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(108.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x28004D40))
            .border(1.dp, Color(0x5580CBC4), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(5.dp))
            Text(title, color = Color(0xFFE0F2F1), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = Color(0xFFB2DFDB), fontSize = 7.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun formatMediaDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
