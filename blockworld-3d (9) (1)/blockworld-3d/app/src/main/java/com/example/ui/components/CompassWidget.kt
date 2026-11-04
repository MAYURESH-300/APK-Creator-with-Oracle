package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semi-transparent North Compass Widget (Requirement 5).
 * Features a half-transparent 'N' that points towards North (-Z axis)
 * relative to camera rotation.
 */
@Composable
fun CompassWidget(
    compassAngle: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedAngle by animateFloatAsState(targetValue = compassAngle, label = "compass_rot")

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0x33000000)) // Semi-transparent black background
            .border(1.dp, Color(0x55FFFFFF), CircleShape)
            .clickable(onClick = onClick)
            .testTag("compass_north_widget")
    ) {
        // Rotating compass needle dial with 'N'
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(animatedAngle),
            contentAlignment = Alignment.Center
        ) {
            // Half-transparent North indicator letter 'N'
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxHeight()
            ) {
                // North tip with red dot and semi-transparent 'N'
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xCCFF3D00)) // Crimson North needle tip
                )
                Text(
                    text = "N",
                    color = Color(0xCCFF3D00), // Semi-transparent red/orange
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 14.sp
                )
            }

            // Center pivot point
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(0x88FFFFFF))
            )
        }
    }
}
