package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Genshin Impact Style Dynamic Following Virtual Joystick:
 * - Dynamic Anchor: Centers on touch position.
 * - Follow Mode: Base shifts along with finger when dragged beyond max radius.
 * - Auto-spring return on release.
 */
@Composable
fun VirtualJoystick(
    onMove: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 120
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var baseOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (isDragging) 0.65f else 0.28f,
        label = "joystick_alpha"
    )

    val maxRadiusPx = (sizeDp * 1.25f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp.dp)
            .offset { IntOffset(baseOffset.x.roundToInt(), baseOffset.y.roundToInt()) }
            .alpha(alphaAnim)
            .clip(CircleShape)
            .background(Color(0x44000000))
            .border(1.5.dp, Color(0x66FFFFFF), CircleShape)
            .testTag("virtual_joystick")
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        baseOffset = Offset.Zero
                        knobOffset = Offset.Zero
                    },
                    onDragEnd = {
                        isDragging = false
                        knobOffset = Offset.Zero
                        baseOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        isDragging = false
                        knobOffset = Offset.Zero
                        baseOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newKnob = knobOffset + dragAmount
                        val dist = sqrt(newKnob.x * newKnob.x + newKnob.y * newKnob.y)

                        if (dist > maxRadiusPx) {
                            // Genshin Impact Dynamic Follow: Base shifts with finger
                            val excess = dist - maxRadiusPx
                            val dirX = newKnob.x / dist
                            val dirY = newKnob.y / dist
                            baseOffset += Offset(dirX * excess * 0.4f, dirY * excess * 0.4f)

                            knobOffset = Offset(dirX * maxRadiusPx, dirY * maxRadiusPx)
                        } else {
                            knobOffset = newKnob
                        }

                        val normalizedX = (knobOffset.x / maxRadiusPx).coerceIn(-1f, 1f)
                        val normalizedY = (-knobOffset.y / maxRadiusPx).coerceIn(-1f, 1f)
                        onMove(normalizedX, normalizedY)
                    }
                )
            }
    ) {
        // Crosshair guides
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .background(Color(0x33FFFFFF))
        )
        Box(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth(0.6f)
                .background(Color(0x33FFFFFF))
        )

        // Floating Knob
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0x99FFFFFF))
                .border(2.dp, Color(0xEEFFFFFF), CircleShape)
        )
    }
}
