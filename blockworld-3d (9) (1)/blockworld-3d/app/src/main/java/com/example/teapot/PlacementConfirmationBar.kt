package com.example.teapot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Floating Placement Confirmation Toolbar for Serenitea Pot object manipulation:
 * 1. Top Drop Target Banners: Drag object to Tic [✓] or Cross [✕] to save or discard when released.
 * 2. Floating Overhead Confirmation Pill: Glowing green [✓] and red [✕] hovering directly above the 3D model.
 * 3. Docked Placement Ribbon: Fixed bottom bar with object name, Confirm [✓], and Cancel [✕] buttons.
 */
@Composable
fun PlacementConfirmationBar(
    objectName: String,
    screenPos: Offset?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStartMove: () -> Unit,
    isMoving: Boolean,
    modifier: Modifier = Modifier,
    dragHoverTarget: String? = null, // "tic", "cross", or null
    onDragDelta: ((Float, Float) -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {

        // 1. TOP DROP TARGET BANNERS (Active when moving or when object is selected)
        // Allows user to drag object directly to Tic [✓] or Cross [✕] targets to save or cancel!
        AnimatedVisibility(
            visible = isMoving || screenPos != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp)
                .zIndex(180f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xD91A1613))
                    .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(30.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Cross Drop Target [✕ Cancel / Revert]
                val isCrossHovered = dragHoverTarget == "cross"
                val crossScale by animateFloatAsState(
                    targetValue = if (isCrossHovered) 1.15f else 1.0f,
                    animationSpec = tween(120),
                    label = "crossScale"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .scale(crossScale)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isCrossHovered)
                                Brush.horizontalGradient(listOf(Color(0xFFFF1744), Color(0xFFFF5252)))
                            else
                                Brush.horizontalGradient(listOf(Color(0x55FF3D71), Color(0x33FF1744)))
                        )
                        .border(
                            width = if (isCrossHovered) 2.dp else 1.dp,
                            color = Color(0xFFFF5252),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onCancel() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("target_drop_cross")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF3D71))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel or Drag to Revert",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isCrossHovered) "Release to Discard" else "Drag to ✕ Cancel",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Revert position",
                                color = Color(0xFFFFCDD2),
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                // Middle Drag Status Indicator
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x40000000))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isMoving) "✥ Moving: $objectName" else "Selected: $objectName",
                        color = Color(0xFFFFE082),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Tic Drop Target [✓ Save Position]
                val isTicHovered = dragHoverTarget == "tic"
                val ticScale by animateFloatAsState(
                    targetValue = if (isTicHovered) 1.15f else 1.0f,
                    animationSpec = tween(120),
                    label = "ticScale"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .scale(ticScale)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isTicHovered)
                                Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF69F0AE)))
                            else
                                Brush.horizontalGradient(listOf(Color(0x5500E676), Color(0x3300C853)))
                        )
                        .border(
                            width = if (isTicHovered) 2.dp else 1.dp,
                            color = Color(0xFF00E676),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onConfirm() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("target_drop_tic")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Confirm or Drag to Save",
                                tint = Color(0xFF003314),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isTicHovered) "Release to Save" else "Drag to ✓ Save",
                                color = if (isTicHovered) Color(0xFF003314) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Save to position",
                                color = if (isTicHovered) Color(0xFF003314) else Color(0xFFC8E6C9),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. FLOATING OVERHEAD CONFIRMATION PILL (Directly hovering above the 3D model)
        if (screenPos != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (screenPos.x - 70.dp.toPx()).roundToInt().coerceIn(10, 2000),
                            (screenPos.y - 50.dp.toPx()).roundToInt().coerceIn(60, 2000)
                        )
                    }
                    .zIndex(170f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xE81E1B18))
                        .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(24.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    // Green Tick Button [✓]
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                            .clickable { onConfirm() }
                            .testTag("btn_confirm_placement_overhead")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save Position (Tic)",
                            tint = Color(0xFF003314),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Move / Drag Handle Icon (Can be tapped or dragged directly!)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isMoving) Color(0xFF00B0FF) else Color(0x35FFFFFF))
                            .clickable { onStartMove() }
                            .then(
                                if (onDragDelta != null) {
                                    Modifier.pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { onStartMove() },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                onDragDelta(dragAmount.x, dragAmount.y)
                                            }
                                        )
                                    }
                                } else Modifier
                            )
                            .testTag("btn_move_placement_overhead")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenWith,
                            contentDescription = "Drag to Move Object",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Red Cross Button [✕]
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3D71))
                            .clickable { onCancel() }
                            .testTag("btn_cancel_placement_overhead")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Placement (Cross)",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // 3. DOCKED BOTTOM PLACEMENT STATUS RIBBON (Above bottom bar, cleared for virtual joystick)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 140.dp, end = 16.dp, bottom = 148.dp) // Sits neatly above furnishing drawer
                .zIndex(140f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEB1F1A17))
                    .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Object Info Label
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isMoving) Color(0xFF00E5FF) else Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isMoving) "Repositioning: $objectName" else "Selected: $objectName",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Drag object over platforms, tap ✓ to save or ✕ to cancel",
                            color = Color(0xFFB0BEC5),
                            fontSize = 10.sp
                        )
                    }
                }

                // Action Buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Cancel Cross Button [✕]
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x35FF3D71))
                            .border(1.dp, Color(0xFFFF3D71), RoundedCornerShape(12.dp))
                            .clickable { onCancel() }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("btn_cancel_placement_docked")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cancel",
                                color = Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Confirm Tick Button [✓]
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF00E676))
                            .clickable { onConfirm() }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                            .testTag("btn_confirm_placement_docked")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Confirm",
                                tint = Color(0xFF003314),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Save ✓",
                                color = Color(0xFF003314),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}
