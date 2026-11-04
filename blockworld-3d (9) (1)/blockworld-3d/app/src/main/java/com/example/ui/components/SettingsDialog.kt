package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.game.CharacterRotationMode
import com.example.game.GameSettings
import com.example.game.SkyEnvironmentType
import java.util.Locale
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import com.example.game.EnvironmentSettingsActivity
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext


/**
 * Settings Dialog (Requirements 5, 6, 9).
 * Allows adjusting Character Facing Mode, Right-Screen Genshin Joystick vs Camera Swipe,
 * Sensitivity, Zoom, Sky Environment, Coordinates display, and Reset buttons.
 */


@Composable
fun SettingsDialog(
    settings: GameSettings,
    onUpdateSettings: (GameSettings) -> Unit,
    onResetPlayer: () -> Unit,
    onResetWorld: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Keep your existing Dialog(...) and all its current settings below.
    // Do NOT call ObjectAppearanceSettings() or
    // EnvironmentAppearanceSettings() here.

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xF0181A20),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color(0xFF40C4FF),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Game Settings",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xAAFFFFFF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. CHARACTER ROTATION MODE (Requirement 5)
                Text(
                    text = "Character Facing Mode",
                    color = Color(0xFF81D4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeSelectCard(
                        title = "Rotate to Move",
                        subtitle = "Turns with walking",
                        selected = settings.characterRotationMode == CharacterRotationMode.ROTATING_TO_MOVEMENT,
                        onClick = { onUpdateSettings(settings.copy(characterRotationMode = CharacterRotationMode.ROTATING_TO_MOVEMENT)) },
                        modifier = Modifier.weight(1f)
                    )
                    ModeSelectCard(
                        title = "Camera Lock",
                        subtitle = "Faces camera & strafes",
                        selected = settings.characterRotationMode == CharacterRotationMode.NON_ROTATING_CAMERA_LOCKED,
                        onClick = { onUpdateSettings(settings.copy(characterRotationMode = CharacterRotationMode.NON_ROTATING_CAMERA_LOCKED)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. SENSITIVITY & ZOOM SLIDERS
                Text(
                    text = "Controls & Camera Sensitivity",
                    color = Color(0xFF81D4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                SliderWithLabel(
                    label = "Swipe Sensitivity",
                    value = settings.cameraSensitivity,
                    range = 0.4f..2.5f,
                    onValueChange = { onUpdateSettings(settings.copy(cameraSensitivity = it)) }
                )
                SliderWithLabel(
                    label = "Default Camera Distance",
                    value = settings.defaultZoomDistance,
                    range = 4f..18f,
                    valueText = "${settings.defaultZoomDistance.toInt()}m",
                    onValueChange = { onUpdateSettings(settings.copy(defaultZoomDistance = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. SKY ENVIRONMENT
                Text(
                    text = "Sky Environment",
                    color = Color(0xFF81D4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SkyChoicePill(
                        label = "Blue Sky",
                        selected = settings.skyEnvironment == SkyEnvironmentType.STYLIZED_BLUE_SKY,
                        onClick = { onUpdateSettings(settings.copy(skyEnvironment = SkyEnvironmentType.STYLIZED_BLUE_SKY)) },
                        modifier = Modifier.weight(1f)
                    )
                    SkyChoicePill(
                        label = "Sunset",
                        selected = settings.skyEnvironment == SkyEnvironmentType.SUNSET_HORIZON,
                        onClick = { onUpdateSettings(settings.copy(skyEnvironment = SkyEnvironmentType.SUNSET_HORIZON)) },
                        modifier = Modifier.weight(1f)
                    )
                    SkyChoicePill(
                        label = "HDR Sky",
                        selected = settings.skyEnvironment == SkyEnvironmentType.CUSTOM_HDR,
                        onClick = { onUpdateSettings(settings.copy(skyEnvironment = SkyEnvironmentType.CUSTOM_HDR)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. AUDIO CONTROLS
                Text(
                    text = "Sound",
                    color = Color(0xFF81D4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                SettingToggleRow(
                    title = "Master Sound",
                    checked = settings.soundEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(soundEnabled = it)) }
                )
                SettingToggleRow(
                    title = "Sound Effects",
                    checked = settings.soundEffectsEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(soundEffectsEnabled = it)) }
                )
                SettingToggleRow(
                    title = "Background Music",
                    checked = settings.backgroundMusicEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(backgroundMusicEnabled = it)) }
                )
                SettingToggleRow(
                    title = "Haptic Vibration Feedback",
                    checked = settings.hapticEnabled,
                    onCheckedChange = {
                        onUpdateSettings(settings.copy(hapticEnabled = it))
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        // Visual environment settings are persisted by EnvironmentVisualSettingsStore.
                        // Do not pass GameSettings through an Intent or expect an activity result.
                        context.startActivity(
                            Intent(context, EnvironmentSettingsActivity::class.java)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text("Environment & Object View")
                }

                Spacer(modifier = Modifier.height(12.dp))

                SliderWithLabel(
                    label = "Master Volume",
                    value = settings.masterVolume,
                    range = 0f..1f,
                    valueText = "${(settings.masterVolume * 100).toInt()}%",
                    onValueChange = { onUpdateSettings(settings.copy(masterVolume = it)) }
                )
                SliderWithLabel(
                    label = "Effects Volume",
                    value = settings.soundEffectsVolume,
                    range = 0f..1f,
                    valueText = "${(settings.soundEffectsVolume * 100).toInt()}%",
                    onValueChange = { onUpdateSettings(settings.copy(soundEffectsVolume = it)) }
                )
                SliderWithLabel(
                    label = "Music Volume",
                    value = settings.backgroundMusicVolume,
                    range = 0f..1f,
                    valueText = "${(settings.backgroundMusicVolume * 100).toInt()}%",
                    onValueChange = { onUpdateSettings(settings.copy(backgroundMusicVolume = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 6. TOGGLES
                SettingToggleRow(
                    title = "Show Target Controls (category tabs always stay visible)",
                    checked = settings.showTeapotTargetControls,
                    onCheckedChange = { onUpdateSettings(settings.copy(showTeapotTargetControls = it)) }
                )
                SettingToggleRow(
                    title = "Show (X, Y, Z) Coordinates HUD",
                    checked = settings.showCoordinates,
                    onCheckedChange = { onUpdateSettings(settings.copy(showCoordinates = it)) }
                )
                SettingToggleRow(
                    title = "Invert Camera Pitch",
                    checked = settings.invertPitch,
                    onCheckedChange = { onUpdateSettings(settings.copy(invertPitch = it)) }
                )
                SettingToggleRow(
                    title = "Haptic Vibration Feedback",
                    checked = settings.hapticEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(hapticEnabled = it)) }
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 7. RESET BUTTONS (Requirement 9)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onResetPlayer()
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFAB40)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_reset_player")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Reset Player", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            onResetWorld()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_reset_world")
                    ) {
                        Icon(imageVector = Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Reset All", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "BlockWorld 3D • Version 1.6 (Build 8)",
                    color = Color(0x88FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun ModeSelectCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x3340C4FF) else Color(0x22FFFFFF))
            .border(
                1.5.dp,
                if (selected) Color(0xFF40C4FF) else Color(0x33FFFFFF),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Column {
            Text(
                text = title,
                color = if (selected) Color.White else Color(0xCCFFFFFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = if (selected) Color(0xFF81D4FA) else Color(0x88FFFFFF),
                fontSize = 10.sp
            )
        }

    }

}
@Composable
private fun AppearanceSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title)

            Text(
                String.format(
                    Locale.US,
                    "%.2f",
                    value
                )
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@Composable
fun ObjectAppearanceSettings(
    settings: GameSettings,
    onUpdateSettings: (GameSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Text("Object Appearance")

        AppearanceSlider(
            title = "Brightness",
            value = settings.objectBrightness,
            range = 0.0f..3.0f
        ) {
            onUpdateSettings(
                settings.copy(objectBrightness = it)
            )
        }

        AppearanceSlider(
            title = "Saturation",
            value = settings.objectSaturation,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(objectSaturation = it)
            )
        }

        AppearanceSlider(
            title = "Opacity",
            value = settings.objectOpacity,
            range = 0.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(objectOpacity = it)
            )
        }

        AppearanceSlider(
            title = "Contrast",
            value = settings.objectContrast,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(objectContrast = it)
            )
        }

        AppearanceSlider(
            title = "Color Temperature",
            value = settings.objectColorTemperature,
            range = -1.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(objectColorTemperature = it)
            )
        }

        AppearanceSlider(
            title = "Texture Alpha-Edge Softness",
            value = settings.objectTextureEdgeSoftness,
            range = 0.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(objectTextureEdgeSoftness = it)
            )
        }

        Button(
            onClick = {
                val defaults = GameSettings()

                onUpdateSettings(
                    settings.copy(
                        objectBrightness = defaults.objectBrightness,
                        objectSaturation = defaults.objectSaturation,
                        objectOpacity = defaults.objectOpacity,
                        objectContrast = defaults.objectContrast,
                        objectColorTemperature =
                            defaults.objectColorTemperature,
                        objectTextureEdgeSoftness =
                            defaults.objectTextureEdgeSoftness
                    )
                )
            }
        ) {
            Text("Reset Object Appearance")
        }
    }
}
@Composable
fun EnvironmentAppearanceSettings(
    settings: GameSettings,
    onUpdateSettings: (GameSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Text("Fog")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Enable Fog",
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = settings.fogEnabled,
                onCheckedChange = {
                    onUpdateSettings(
                        settings.copy(fogEnabled = it)
                    )
                }
            )
        }

        AppearanceSlider(
            title = "Fog Brightness",
            value = settings.fogBrightness,
            range = 0.0f..3.0f
        ) {
            onUpdateSettings(
                settings.copy(fogBrightness = it)
            )
        }

        AppearanceSlider(
            title = "Fog Opacity",
            value = settings.fogOpacity,
            range = 0.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(fogOpacity = it)
            )
        }

        AppearanceSlider(
            title = "Fog Density",
            value = settings.fogDensity,
            range = 0.0f..0.1f
        ) {
            onUpdateSettings(
                settings.copy(fogDensity = it)
            )
        }

        AppearanceSlider(
            title = "Fog Start Distance",
            value = settings.fogStartDistance,
            range = 0.0f..200.0f
        ) {
            onUpdateSettings(
                settings.copy(
                    fogStartDistance = minOf(
                        it,
                        settings.fogEndDistance - 1.0f
                    )
                )
            )
        }

        AppearanceSlider(
            title = "Fog End Distance",
            value = settings.fogEndDistance,
            range = 1.0f..500.0f
        ) {
            onUpdateSettings(
                settings.copy(
                    fogEndDistance = maxOf(
                        it,
                        settings.fogStartDistance + 1.0f
                    )
                )
            )
        }

        AppearanceSlider(
            title = "Fog Softness",
            value = settings.fogSoftness,
            range = 0.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(fogSoftness = it)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text("Sunlight and Lighting")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Enable Sunlight",
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = settings.sunlightEnabled,
                onCheckedChange = {
                    onUpdateSettings(
                        settings.copy(sunlightEnabled = it)
                    )
                }
            )
        }

        AppearanceSlider(
            title = "Sunlight Intensity",
            value = settings.sunIntensity,
            range = 0.0f..4.0f
        ) {
            onUpdateSettings(
                settings.copy(sunIntensity = it)
            )
        }

        AppearanceSlider(
            title = "Ambient Light",
            value = settings.ambientLightIntensity,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(ambientLightIntensity = it)
            )
        }

        AppearanceSlider(
            title = "Sun Direction X",
            value = settings.sunDirectionX,
            range = -1.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(sunDirectionX = it)
            )
        }

        AppearanceSlider(
            title = "Sun Direction Y",
            value = settings.sunDirectionY,
            range = -1.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(sunDirectionY = it)
            )
        }

        AppearanceSlider(
            title = "Sun Direction Z",
            value = settings.sunDirectionZ,
            range = -1.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(sunDirectionZ = it)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text("Sky and Atmosphere")

        AppearanceSlider(
            title = "Sky Brightness",
            value = settings.skyBrightness,
            range = 0.0f..3.0f
        ) {
            onUpdateSettings(
                settings.copy(skyBrightness = it)
            )
        }

        AppearanceSlider(
            title = "Sky Saturation",
            value = settings.skySaturation,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(skySaturation = it)
            )
        }

        AppearanceSlider(
            title = "Atmosphere Strength",
            value = settings.atmosphereStrength,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(atmosphereStrength = it)
            )
        }

        AppearanceSlider(
            title = "Sky Transition Softness",
            value = settings.skyTransitionSoftness,
            range = 0.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(skyTransitionSoftness = it)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text("Environment Color Grading")

        AppearanceSlider(
            title = "Environment Brightness",
            value = settings.environmentBrightness,
            range = 0.0f..3.0f
        ) {
            onUpdateSettings(
                settings.copy(environmentBrightness = it)
            )
        }

        AppearanceSlider(
            title = "Environment Saturation",
            value = settings.environmentSaturation,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(environmentSaturation = it)
            )
        }

        AppearanceSlider(
            title = "Environment Contrast",
            value = settings.environmentContrast,
            range = 0.0f..2.0f
        ) {
            onUpdateSettings(
                settings.copy(environmentContrast = it)
            )
        }

        AppearanceSlider(
            title = "Environment Color Temperature",
            value = settings.environmentColorTemperature,
            range = -1.0f..1.0f
        ) {
            onUpdateSettings(
                settings.copy(
                    environmentColorTemperature = it
                )
            )
        }
    }
}
@Composable
private fun SkyChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x4440C4FF) else Color(0x22FFFFFF))
            .border(1.dp, if (selected) Color(0xFF40C4FF) else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xAAFFFFFF),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SliderWithLabel(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color(0xCCFFFFFF), fontSize = 12.sp)
            Text(
                text = valueText ?: String.format("%.2fx", value),
                color = Color(0xFF81D4FA),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            valueRange = range,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF40C4FF),
                activeTrackColor = Color(0xFF40C4FF),
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = title, color = Color(0xEEFFFFFF), fontSize = 12.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF40C4FF),
                checkedTrackColor = Color(0x4440C4FF),
                uncheckedThumbColor = Color(0x88FFFFFF),
                uncheckedTrackColor = Color(0x22FFFFFF)
            )
        )
    }
}
