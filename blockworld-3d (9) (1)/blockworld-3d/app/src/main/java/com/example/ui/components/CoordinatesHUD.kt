package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Vector3

/**
 * Semi-transparent Real-time (X, Y, Z) Coordinate System HUD (Requirement 7 & 8).
 * Keeps UI components small and transparent so the 3D world is prominently visible.
 */
@Composable
fun CoordinatesHUD(
    playerCoords: Vector3,
    nearestTargetInfo: String?,
    score: Int,
    hitCombo: Int,
    playerHp: Float,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .testTag("coordinates_hud")
            .background(Color(0x35000000), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        // Player X, Y, Z Coordinates
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoordinateBadge(label = "X", value = playerCoords.x, color = Color(0xFFFF5252))
            CoordinateBadge(label = "Y", value = playerCoords.y, color = Color(0xFF69F0AE))
            CoordinateBadge(label = "Z", value = playerCoords.z, color = Color(0xFF40C4FF))

            // Score Badge
            Box(
                modifier = Modifier
                    .background(Color(0x44FFD700), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "★ $score",
                    color = Color(0xFFFFD700),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Hit Combo Counter
            if (hitCombo > 1) {
                Box(
                    modifier = Modifier
                        .background(Color(0x66FF3D00), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${hitCombo}x COMBO!",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // Nearest Tracked Object / Target Coordinates
        if (nearestTargetInfo != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Target: $nearestTargetInfo",
                color = Color(0xCCEEEEEE),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CoordinateBadge(label: String, value: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = String.format("%+5.1f", value),
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}
