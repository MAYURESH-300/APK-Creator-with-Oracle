package com.example.teapot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.window.Dialog

/**
 * Genshin Impact Serenitea Pot Exit Confirmation Popup Panel (Requirement 3):
 * Prompts user when pressing [✕] to exit spectator placement mode with:
 * - Save & Exit: Saves all furnishing positions, swaps, and placed objects, then exits.
 * - Discard & Exit: Discards unconfirmed changes, reverts to last saved state, then exits.
 * - Cancel: Dismisses the dialog and remains in placement mode.
 */
@Composable
fun ExitSpectatorDialog(
    onSaveAndExit: () -> Unit,
    onDiscardAndExit: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF51E1916)) // Genshin warm dark teakwood tone
                .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(20.dp)) // Royal gold border
                .padding(22.dp)
                .testTag("dialog_exit_spectator")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Top Teapot Icon Emblem
                Text(text = "🏺", fontSize = 34.sp)
                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Text(
                    text = "Exit Placement Mode?",
                    color = Color(0xFFFFD54F),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Description
                Text(
                    text = "Would you like to save changes made to furnishing placements and swaps before exiting back to action mode?",
                    color = Color(0xFFECEFF1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Button 1: Save & Exit (Primary Gold Button)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF00E676))
                        .clickable { onSaveAndExit() }
                        .padding(vertical = 12.dp)
                        .testTag("btn_dialog_save_exit")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF003314),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save & Exit",
                            color = Color(0xFF003314),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Button 2: Discard & Exit (Warning Red Button)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x35FF3D71))
                        .border(1.2.dp, Color(0xFFFF5252), RoundedCornerShape(14.dp))
                        .clickable { onDiscardAndExit() }
                        .padding(vertical = 11.dp)
                        .testTag("btn_dialog_discard_exit")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Discard Changes & Exit",
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Button 3: Cancel (Stay in Spectator Mode)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x25FFFFFF))
                        .clickable { onCancel() }
                        .padding(vertical = 10.dp)
                        .testTag("btn_dialog_cancel")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Color(0xFFCFD8DC),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cancel",
                            color = Color(0xFFCFD8DC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
