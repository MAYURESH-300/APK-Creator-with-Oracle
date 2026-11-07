package com.example.teapot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top-left furnishing information overlay matching the Genshin Impact Serenitea Pot Placement Screen:
 * - Furnishing title (e.g. "Sub-Space Cabochon: Up, Up and Away")
 * - Subtitle description ("Can be placed side by side and can be stacked vertically.")
 * - Dynamic Platform Height ("Platform Height: 14.0/30")
 */
@Composable
fun TeapotObjectInfo(
    objectName: String,
    description: String,
    height: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x55000000))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = objectName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color(0xFFD0D7DE),
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                val clampedHeight = height.coerceIn(0f, 30f)
                Text(
                    text = "Platform Height: ${String.format("%.1f", clampedHeight)}/30",
                    color = Color(0xFFFFD54F), // Genshin warm gold
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
