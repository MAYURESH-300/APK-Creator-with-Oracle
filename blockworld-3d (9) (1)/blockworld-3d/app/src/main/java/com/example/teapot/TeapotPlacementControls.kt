package com.example.teapot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Right-side placement action controls matching the Genshin Impact Serenitea Pot Placement Screen:
 * - Cancel [✕]
 * - Rotate [⟲]
 * - Switch: Vertical Adjustment / Switch: Horizontal [↕ / ↔]
 * - Confirm [✓]
 */
@Composable
fun TeapotPlacementControls(
    isVerticalAdjustment: Boolean,
    onCancel: () -> Unit,
    onRotate: () -> Unit,
    onToggleAdjustmentMode: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(end = 16.dp)
        ) {
            // 1. Cancel [✕]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onCancel() }
                    .testTag("btn_teapot_cancel")
            ) {
                Text(
                    text = "Cancel",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xF0EDEAE3)) // Genshin warm cream button surface
                        .border(1.5.dp, Color(0xFFC7BBAA), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Placement",
                        tint = Color(0xFF322822),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 2. Rotate [⟲]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onRotate() }
                    .testTag("btn_teapot_rotate_active")
            ) {
                Text(
                    text = "Rotate",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xF0EDEAE3))
                        .border(1.5.dp, Color(0xFFC7BBAA), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateRight,
                        contentDescription = "Rotate Object",
                        tint = Color(0xFF322822),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // 3. Switch: Vertical Adjustment / Horizontal [↕ / ↔]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onToggleAdjustmentMode() }
                    .testTag("btn_teapot_switch_adjustment")
            ) {
                Text(
                    text = if (isVerticalAdjustment) "Switch:\nHorizontal" else "Switch:\nVertical\nAdjustment",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isVerticalAdjustment) Color(0xFFFFD54F) else Color(0xF0EDEAE3))
                        .border(1.5.dp, if (isVerticalAdjustment) Color(0xFFFFB300) else Color(0xFFC7BBAA), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isVerticalAdjustment) Icons.Default.SwapHoriz else Icons.Default.Height,
                        contentDescription = "Switch Adjustment Mode",
                        tint = Color(0xFF322822),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 4. Confirm [✓]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onConfirm() }
                    .testTag("btn_teapot_confirm")
            ) {
                Text(
                    text = "Confirm",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xF0EDEAE3))
                        .border(1.5.dp, Color(0xFFC7BBAA), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm Placement",
                        tint = Color(0xFF322822),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
