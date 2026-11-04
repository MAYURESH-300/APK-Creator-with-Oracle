package com.example.teapot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top bar matching the Genshin Impact Serenitea Pot Placement Screen:
 * - Camera Photo button
 * - Area selector ("◄ Nodoka Terrace ►")
 * - Load indicator ("Load ●")
 * - Compact actions: Rotate, Save, Export GLB, Import, Multi-select.
 * - Exit button ("[✕]")
 */
@Composable
fun TeapotTopBar(
    areaName: String = "Nodoka Terrace",
    onAreaPrevious: () -> Unit,
    onAreaNext: () -> Unit,
    onExitSpectator: () -> Unit,
    onCameraSnapshot: () -> Unit,
    onRotateTarget: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSaveFurnishings: () -> Unit,
    onExportGlb: () -> Unit,
    onOpenImportPicker: () -> Unit,
    onMultiSelectToggle: () -> Unit,
    isMultiSelect: Boolean = false,
    isRotationGizmoVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Left Group: Camera Icon & Area Selector Pill
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Camera Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x77000000))
                    .border(1.dp, Color(0x99FFFFFF), CircleShape)
                    .clickable { onCameraSnapshot() }
                    .testTag("btn_teapot_camera")
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = "Enter clean spectator view",
                    tint = Color(0xFFECEFF1),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Area Selector (e.g. ◄ Nodoka Terrace ►)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x77000000))
                    .border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(18.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous Area",
                        tint = Color(0xFFB0BEC5),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onAreaPrevious() }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = areaName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Area",
                        tint = Color(0xFFB0BEC5),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onAreaNext() }
                    )
                }
            }
        }

        // Right Group: Load pill, action icons, Exit Button
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Load Status Pill: "Load ●"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x66000000))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Load", color = Color(0xFFCFD8DC), fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676)) // Smooth green indicator
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Compact action buttons are placed in the old coin-badge slot.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompactTeapotAction(
                    icon = Icons.Default.DeleteOutline,
                    description = "Delete selected object",
                    tag = "btn_delete_selected_object",
                    tint = Color(0xFFFF8A80),
                    onClick = onDeleteSelected
                )
                CompactTeapotAction(
                    icon = Icons.Default.RotateRight,
                    description = "Toggle three-axis object rotation gizmo",
                    tag = "btn_teapot_rotate",
                    active = isRotationGizmoVisible,
                    onClick = onRotateTarget
                )
                CompactTeapotAction(
                    icon = Icons.Default.FileDownload,
                    description = "Save Furnishings",
                    tag = "btn_teapot_save",
                    tint = Color(0xFFFFD54F),
                    onClick = onSaveFurnishings
                )
                CompactTeapotAction(
                    icon = Icons.Default.Share,
                    description = "Export scene as GLB",
                    tag = "btn_export_scene_glb",
                    tint = Color(0xFF80CBC4),
                    onClick = onExportGlb
                )
                CompactTeapotAction(
                    icon = Icons.Default.CloudUpload,
                    description = "Import 3D Model",
                    tag = "btn_import_model",
                    onClick = onOpenImportPicker
                )
                CompactTeapotAction(
                    icon = Icons.Default.CropFree,
                    description = "Multi-select Mode",
                    tag = "btn_teapot_multiselect",
                    active = isMultiSelect,
                    onClick = onMultiSelectToggle
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Exit Button [✕]
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .border(1.5.dp, Color(0xAAFFFFFF), CircleShape)
                    .clickable { onExitSpectator() }
                    .testTag("btn_teapot_exit")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Placement Mode",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}


@Composable
private fun CompactTeapotAction(
    icon: ImageVector,
    description: String,
    tag: String,
    tint: Color = Color.White,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    active -> Color(0xCCFFB300)
                    else -> Color(0x77000000)
                }
            )
            .border(
                1.dp,
                if (active) Color(0xFFFFD54F) else Color(0x77FFFFFF),
                RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) Color.White else tint,
            modifier = Modifier.size(17.dp)
        )
    }
}
