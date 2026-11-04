package com.example.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small upper-left animation control. Clips are read from the selected target's imported GLB. */
@Composable
fun AnimationQuickControl(
    menuExpanded: Boolean,
    animationNames: List<String>,
    activeAnimationName: String?,
    animationEnabled: Boolean,
    autoAnimationEnabled: Boolean,
    supportsSkeletalAnimation: Boolean,
    onToggleMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetAuto: (Boolean) -> Unit,
    onSelectAnimation: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xD919222C))
                .border(1.dp, if (animationEnabled) Color(0xFF42D7D0) else Color(0x777F8A96), RoundedCornerShape(9.dp))
                .clickable { onToggleMenu() }
                .padding(horizontal = 8.dp)
                .testTag("btn_model_animation")
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Animation controls",
                tint = if (animationEnabled) Color(0xFF42D7D0) else Color(0xFFCFD8DC),
                modifier = Modifier.width(15.dp)
            )
            Spacer(Modifier.width(2.dp))
            val modeText = when {
                animationNames.isEmpty() -> "ANIM —"
                !animationEnabled -> "ANIM OFF"
                autoAnimationEnabled -> "ANIM AUTO"
                else -> (activeAnimationName ?: "ANIM").uppercase().take(9)
            }
            Text(
                text = modeText,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onCloseMenu,
            modifier = Modifier.background(Color(0xFF20242B))
        ) {
            DropdownMenuItem(
                text = { Text(if (animationEnabled) "Pause animation" else "Turn animation on", color = Color.White, fontSize = 13.sp) },
                onClick = { onSetEnabled(!animationEnabled) },
                modifier = Modifier.height(42.dp)
            )
            DropdownMenuItem(
                text = { Text(if (autoAnimationEnabled) "Auto Idle / Walk / Run ✓" else "Auto Idle / Walk / Run", color = Color.White, fontSize = 13.sp) },
                onClick = { onSetAuto(!autoAnimationEnabled) },
                modifier = Modifier.height(42.dp)
            )
            if (animationNames.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Select a swapped GLB with animation clips", color = Color(0xFFB0BEC5), fontSize = 11.sp) },
                    onClick = onCloseMenu,
                    modifier = Modifier.height(44.dp)
                )
            } else {
                animationNames.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (name.equals(activeAnimationName, true)) "✓  $name" else name,
                                color = if (name.equals(activeAnimationName, true)) Color(0xFF42D7D0) else Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = { onSelectAnimation(index) },
                        modifier = Modifier.height(40.dp)
                    )
                }
                if (!supportsSkeletalAnimation) {
                    DropdownMenuItem(
                        text = { Text("Clips found, but no supported skin/joint tracks were detected", color = Color(0xFFFFCC80), fontSize = 11.sp) },
                        onClick = onCloseMenu,
                        modifier = Modifier.height(44.dp)
                    )
                }
            }
        }
    }
}
