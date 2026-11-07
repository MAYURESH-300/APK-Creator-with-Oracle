package com.example.teapot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RotateRight
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
 * Floating side actions matching Genshin Impact Serenitea Pot Placement Screen:
 * - Multi-Select Mode
 * - Regular Rotation
 * - Save Button (Persists model swaps)
 */
@Composable
fun TeapotSideActions(
    onMultiSelectToggle: () -> Unit,
    onRotateTarget: () -> Unit,
    onSaveFurnishings: () -> Unit,
    isMultiSelect: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
    ) {
        // Multi-Select Mode Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onMultiSelectToggle() }
                .testTag("btn_teapot_multiselect")
        ) {
            Text(
                text = "Multi-Select\nMode",
                color = if (isMultiSelect) Color(0xFFFFD54F) else Color(0xEEFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 13.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isMultiSelect) Color(0xCCFFB300) else Color(0x66000000))
                    .border(1.5.dp, Color(0x99FFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = "Multi-Select Mode",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Regular Rotation Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onRotateTarget() }
                .testTag("btn_teapot_rotate")
        ) {
            Text(
                text = "Regular\nRotation",
                color = Color(0xEEFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 13.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .border(1.5.dp, Color(0x99FFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.RotateRight,
                    contentDescription = "Rotate Target",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // SAVE BUTTON (Requirement 4 - Genshin Teapot Save)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onSaveFurnishings() }
                .testTag("btn_teapot_save")
        ) {
            Text(
                text = "Save",
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xAAFF8F00))
                    .border(2.dp, Color(0xFFFFD54F), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Save Furnishings",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
