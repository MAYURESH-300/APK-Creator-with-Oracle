package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semi-transparent Action Controls (Jump, Attack, Dash) & Zoom Buttons.
 * Dash cooldown removed for rapid execution (Requirement 1).
 */
@Composable
fun ActionControls(
    onJump: () -> Unit,
    onAttack: () -> Unit,
    onDash: () -> Unit,
    onFireball: () -> Unit = {},
    dashCooldownPercent: Float = 0f,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // JUMP BUTTON (Top right of cluster)
        ActionGlassButton(
            icon = Icons.Default.ArrowUpward,
            label = "JUMP",
            buttonColor = Color(0x334CAF50),
            borderColor = Color(0x664CAF50),
            onClick = onJump,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-40).dp)
                .testTag("btn_jump")
        )

        // ATTACK BUTTON (Center, prominent)
        ActionGlassButton(
            icon = Icons.Default.FlashOn,
            label = "ATTACK",
            buttonColor = Color(0x38FF3D00),
            borderColor = Color(0x88FF5722),
            iconSize = 28,
            buttonSize = 64,
            onClick = onAttack,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 10.dp)
                .testTag("btn_attack")
        )

        // FIREBALL BUTTON (Left side of the action cluster). The world-space projectile uses
        // the transparent sprite at app/src/main/assets/vfx/fireball.png.
        ActionGlassButton(
            icon = Icons.Default.Whatshot,
            label = "FIREBALL",
            buttonColor = Color(0x44FF6D00),
            borderColor = Color(0xBBFF8A00),
            iconSize = 24,
            buttonSize = 52,
            onClick = onFireball,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp)
                .testTag("btn_fireball")
        )

        // DASH BUTTON (Bottom left of cluster - instant, no cooldown)
        ActionGlassButton(
            icon = Icons.Default.FastForward,
            label = "DASH",
            buttonColor = Color(0x382979FF),
            borderColor = Color(0x882979FF),
            onClick = onDash,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = (-45).dp, y = 10.dp)
                .testTag("btn_dash")
        )
    }
}

/**
 * Quick Zoom In / Zoom Out Floating Controls (Requirement 1).
 */
@Composable
fun ZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    currentZoom: Float,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0x33000000), CircleShape)
            .border(1.dp, Color(0x44FFFFFF), CircleShape)
            .padding(4.dp)
            .testTag("zoom_controls")
    ) {
        // Zoom In (+)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x44FFFFFF))
                .clickable(onClick = onZoomIn)
                .testTag("btn_zoom_in")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Zoom In",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Current zoom distance indicator
        Text(
            text = "${currentZoom.toInt()}m",
            color = Color(0xCCFFFFFF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        // Zoom Out (-)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x44FFFFFF))
                .clickable(onClick = onZoomOut)
                .testTag("btn_zoom_out")
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ActionGlassButton(
    icon: ImageVector,
    label: String,
    buttonColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Int = 54,
    iconSize: Int = 24
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleAnim by animateFloatAsState(targetValue = if (isPressed) 0.88f else 1.0f, label = "btn_scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.scale(scaleAnim)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(buttonSize.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .border(1.5.dp, borderColor, CircleShape)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(iconSize.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = Color(0xBBFFFFFF),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
