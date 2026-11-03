package com.example.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.environment.EnvironmentVisualSettingsStore
import com.example.environment.VisualEnvironmentSettings
import java.util.Locale

class EnvironmentSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EnvironmentVisualSettingsStore.initialize(applicationContext)

        // Edge-to-edge, immersive controls screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        hideSystemBars()

        setContent {
            MaterialTheme {
                EnvironmentSettingsScreen(
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {
            hide(
                WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars()
            )

            systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun EnvironmentSettingsScreen(
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var settings by remember {
        mutableStateOf(EnvironmentVisualSettingsStore.snapshot())
    }

    fun update(
        transform: (VisualEnvironmentSettings) -> VisualEnvironmentSettings
    ) {
        settings = EnvironmentVisualSettingsStore.update(
            context,
            transform
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080D14))
            .imePadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Environment Controls",
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Live 3D visual settings",
                    color = Color(0xFF9BAFC8),
                    fontSize = 13.sp
                )
            }

            TextButton(onClick = onClose) {
                Text("Done")
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // -----------------------------------------------------
            // OBJECT BRIGHTNESS
            // -----------------------------------------------------

            SettingsCard(title = "Object Brightness") {

                SliderSetting(
                    title = "Brightness",
                    value = settings.objectBrightness,
                    valueRange = 0.0f..2.0f,
                    onValueChange = { value ->
                        update {
                            it.copy(objectBrightness = value)
                        }
                    }
                )

                Text(
                    text = "0% = black, 100% = normal, 200% = twice the brightness.",
                    color = Color(0xFF9BAFC8),
                    fontSize = 12.sp
                )
            }

            // -----------------------------------------------------
            // SKY COLOR
            // -----------------------------------------------------

            SettingsCard(title = "Sky Appearance") {

                HexColorField(
                    label = "Sky Tint (#RRGGBB)",
                    value = settings.skyColorHex,
                    onValidValueChange = { hex ->
                        update {
                            it.copy(skyColorHex = hex)
                        }
                    }
                )

                SliderSetting(
                    title = "Tint Strength",
                    value = settings.skyTintStrength,
                    valueRange = 0.0f..1.0f,
                    onValueChange = { value ->
                        update {
                            it.copy(skyTintStrength = value)
                        }
                    }
                )

                SliderSetting(
                    title = "Sky Brightness",
                    value = settings.skyBrightness,
                    valueRange = 0.0f..2.0f,
                    onValueChange = { value ->
                        update {
                            it.copy(skyBrightness = value)
                        }
                    }
                )

                Text(
                    text = "Use #FFFFFF and 0% tint strength to preserve the original sky colors.",
                    color = Color(0xFF9BAFC8),
                    fontSize = 12.sp
                )
            }

            // -----------------------------------------------------
            // FOG
            // -----------------------------------------------------

            SettingsCard(title = "Fog") {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Enable Fog",
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "Blend distant objects into the fog color.",
                            color = Color(0xFF9BAFC8),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = settings.fogEnabled,
                        onCheckedChange = { enabled ->
                            update {
                                it.copy(fogEnabled = enabled)
                            }
                        }
                    )
                }

                HexColorField(
                    label = "Fog Color (#RRGGBB)",
                    value = settings.fogColorHex,
                    onValidValueChange = { hex ->
                        update {
                            it.copy(fogColorHex = hex)
                        }
                    }
                )

                SliderSetting(
                    title = "Fog Density",
                    value = settings.fogDensity,
                    valueRange = 0.0f..0.08f,
                    onValueChange = { value ->
                        update {
                            it.copy(fogDensity = value)
                        }
                    }
                )

                SliderSetting(
                    title = "Fog Glow",
                    value = settings.fogGlow,
                    valueRange = 0.0f..2.0f,
                    onValueChange = { value ->
                        update {
                            it.copy(fogGlow = value)
                        }
                    }
                )

                Text(
                    text = "Fog Glow adds bright color scattering. A true blurred bloom effect can be added later.",
                    color = Color(0xFF9BAFC8),
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Return to Game")
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF121B27)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFF80B9FF),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            content()
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 14.sp
            )

            Text(
                text = String.format(Locale.US, "%.3f", value),
                color = Color(0xFF9BAFC8),
                fontSize = 12.sp
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun HexColorField(
    label: String,
    value: String,
    onValidValueChange: (String) -> Unit
) {
    var text by remember(value) {
        mutableStateOf(value)
    }

    val isValid = Regex("^#[0-9A-Fa-f]{6}$").matches(text)

    val swatchColor = if (isValid) {
        try {
            Color(
                android.graphics.Color.parseColor(text)
            )
        } catch (_: IllegalArgumentException) {
            Color.DarkGray
        }
    } else {
        Color.DarkGray
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText.uppercase(Locale.US)

                if (Regex("^#[0-9A-F]{6}$").matches(text)) {
                    onValidValueChange(text)
                }
            },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii
            )
        )

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(swatchColor)
        )
    }

    if (!isValid) {
        Text(
            text = "Enter a valid color, for example #4287F5.",
            color = Color(0xFFFF7777),
            fontSize = 12.sp
        )
    }
}

//
//package com.example.game
//
//import android.app.Activity
//import android.content.Intent
//import android.os.Build
//import android.os.Bundle
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.height
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.rememberScrollState
//import androidx.compose.foundation.verticalScroll
//import androidx.compose.material3.Button
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.OutlinedButton
//import androidx.compose.material3.Surface
//import androidx.compose.material3.Text
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import com.example.ui.components.EnvironmentAppearanceSettings
//import com.example.ui.components.ObjectAppearanceSettings
//
//class EnvironmentSettingsActivity : ComponentActivity() {
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        val initialSettings = readSettings(intent)
//
//        if (initialSettings == null) {
//            finish()
//            return
//        }
//
//        setContent {
//            MaterialTheme {
//                var draft by remember {
//                    mutableStateOf(initialSettings)
//                }
//
//                Surface(modifier = Modifier.fillMaxSize()) {
//                    Column(
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .padding(16.dp)
//                    ) {
//                        Text(
//                            text = "Environment & Object View",
//                            style = MaterialTheme.typography.headlineSmall
//                        )
//
//                        Spacer(Modifier.height(12.dp))
//
//                        Column(
//                            modifier = Modifier
//                                .weight(1f)
//                                .fillMaxWidth()
//                                .verticalScroll(rememberScrollState())
//                        ) {
//                            ObjectAppearanceSettings(
//                                settings = draft,
//                                onUpdateSettings = { draft = it }
//                            )
//
//                            EnvironmentAppearanceSettings(
//                                settings = draft,
//                                onUpdateSettings = { draft = it }
//                            )
//                        }
//
//                        Spacer(Modifier.height(12.dp))
//
//                        Row(
//                            modifier = Modifier.fillMaxWidth(),
//                            horizontalArrangement = Arrangement.spacedBy(12.dp)
//                        ) {
//                            OutlinedButton(
//                                onClick = {
//                                    setResult(Activity.RESULT_CANCELED)
//                                    finish()
//                                },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("Cancel")
//                            }
//
//                            Button(
//                                onClick = {
//                                    val result = Intent().putExtra(
//                                        EXTRA_SETTINGS,
//                                        draft
//                                    )
//
//                                    setResult(Activity.RESULT_OK, result)
//                                    finish()
//                                },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("Apply")
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    private fun readSettings(source: Intent): GameSettings? {
//        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            source.getSerializableExtra(
//                EXTRA_SETTINGS,
//                GameSettings::class.java
//            )
//        } else {
//            @Suppress("DEPRECATION")
//            source.getSerializableExtra(EXTRA_SETTINGS) as? GameSettings
//        }
//    }
//
//    companion object {
//        const val EXTRA_SETTINGS =
//            "com.example.game.EXTRA_ENVIRONMENT_SETTINGS"
//    }
//}
//
//
////package com.example.game
////
////import android.app.Activity
////import android.content.Intent
////import android.os.Build
////import android.os.Bundle
////import androidx.activity.ComponentActivity
////import androidx.activity.compose.setContent
////import androidx.compose.foundation.layout.Arrangement
////import androidx.compose.foundation.layout.Column
////import androidx.compose.foundation.layout.Row
////import androidx.compose.foundation.layout.Spacer
////import androidx.compose.foundation.layout.fillMaxSize
////import androidx.compose.foundation.layout.fillMaxWidth
////import androidx.compose.foundation.layout.height
////import androidx.compose.foundation.layout.padding
////import androidx.compose.foundation.rememberScrollState
////import androidx.compose.foundation.verticalScroll
////import androidx.compose.material3.Button
////import androidx.compose.material3.MaterialTheme
////import androidx.compose.material3.Slider
////import androidx.compose.material3.Switch
////import androidx.compose.material3.Text
////import androidx.compose.material3.OutlinedButton
////import androidx.compose.runtime.Composable
////import androidx.compose.runtime.getValue
////import androidx.compose.runtime.mutableStateOf
////import androidx.compose.runtime.remember
////import androidx.compose.runtime.setValue
////import androidx.compose.ui.Modifier
////import androidx.compose.ui.unit.dp
////import java.io.Serializable
////import java.util.Locale
////
////class EnvironmentSettingsActivity : ComponentActivity() {
////
////    override fun onCreate(savedInstanceState: Bundle?) {
////        super.onCreate(savedInstanceState)
////
////        val initialSettings = readSettings(intent)
////
////        if (initialSettings == null) {
////            finish()
////            return
////        }
////
////        setContent {
////            MaterialTheme {
////                EnvironmentSettingsScreen(
////                    initialSettings = initialSettings,
////                    onCancel = {
////                        setResult(Activity.RESULT_CANCELED)
////                        finish()
////                    },
////                    onApply = { updatedSettings ->
////                        val resultIntent = Intent().putExtra(
////                            EXTRA_SETTINGS,
////                            updatedSettings
////                        )
////
////                        setResult(Activity.RESULT_OK, resultIntent)
////                        finish()
////                    }
////                )
////            }
////        }
////    }
////
////    private fun readSettings(source: Intent): GameSettings? {
////        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
////            source.getSerializableExtra(
////                EXTRA_SETTINGS,
////                GameSettings::class.java
////            )
////        } else {
////            @Suppress("DEPRECATION")
////            source.getSerializableExtra(EXTRA_SETTINGS) as? GameSettings
////        }
////    }
////
////    companion object {
////        const val EXTRA_SETTINGS =
////            "com.example.game.EXTRA_ENVIRONMENT_SETTINGS"
////    }
////}
////
////@Composable
////private fun EnvironmentSettingsScreen(
////    initialSettings: GameSettings,
////    onCancel: () -> Unit,
////    onApply: (GameSettings) -> Unit
////) {
////    var draft by remember(initialSettings) {
////        mutableStateOf(initialSettings)
////    }
////
////    Column(
////        modifier = Modifier
////            .fillMaxSize()
////            .padding(16.dp)
////    ) {
////        Text(
////            text = "Environment & Object View",
////            style = MaterialTheme.typography.headlineSmall
////        )
////
////        Spacer(Modifier.height(6.dp))
////
////        Text(
////            text = "Customize the appearance of the world and its objects.",
////            style = MaterialTheme.typography.bodyMedium
////        )
////
////        Spacer(Modifier.height(12.dp))
////
////        // Only this content scrolls. The action buttons remain visible.
////        Column(
////            modifier = Modifier
////                .weight(1f)
////                .fillMaxWidth()
////                .verticalScroll(rememberScrollState()),
////            verticalArrangement = Arrangement.spacedBy(8.dp)
////        ) {
////            // -------------------------------------------------
////            // OBJECT APPEARANCE
////            // -------------------------------------------------
////
////            SectionTitle("Object Appearance")
////
////            FloatSetting(
////                title = "Object brightness",
////                value = draft.objectBrightness,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(objectBrightness = it)
////            }
////
////            FloatSetting(
////                title = "Object saturation",
////                value = draft.objectSaturation,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(objectSaturation = it)
////            }
////
////            FloatSetting(
////                title = "Object opacity",
////                value = draft.objectOpacity,
////                range = 0f..1f
////            ) {
////                draft = draft.copy(objectOpacity = it)
////            }
////
////            FloatSetting(
////                title = "Object contrast",
////                value = draft.objectContrast,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(objectContrast = it)
////            }
////
////            FloatSetting(
////                title = "Object color temperature",
////                value = draft.objectColorTemperature,
////                range = -1f..1f
////            ) {
////                draft = draft.copy(objectColorTemperature = it)
////            }
////
////            FloatSetting(
////                title = "Texture edge softness",
////                value = draft.objectTextureEdgeSoftness,
////                range = 0f..1f
////            ) {
////                draft = draft.copy(objectTextureEdgeSoftness = it)
////            }
////
////            Spacer(Modifier.height(12.dp))
////
////            // -------------------------------------------------
////            // FOG
////            // -------------------------------------------------
////
////            SectionTitle("Fog")
////
////            BooleanSetting(
////                title = "Enable fog",
////                checked = draft.fogEnabled
////            ) {
////                draft = draft.copy(fogEnabled = it)
////            }
////
////            FloatSetting(
////                title = "Fog brightness",
////                value = draft.fogBrightness,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(fogBrightness = it)
////            }
////
////            FloatSetting(
////                title = "Fog opacity",
////                value = draft.fogOpacity,
////                range = 0f..1f
////            ) {
////                draft = draft.copy(fogOpacity = it)
////            }
////
////            FloatSetting(
////                title = "Fog density",
////                value = draft.fogDensity,
////                range = 0f..0.1f
////            ) {
////                draft = draft.copy(fogDensity = it)
////            }
////
////            FloatSetting(
////                title = "Fog start distance",
////                value = draft.fogStartDistance,
////                range = 0f..200f
////            ) {
////                draft = draft.copy(
////                    fogStartDistance = minOf(
////                        it,
////                        draft.fogEndDistance - 1f
////                    ).coerceAtLeast(0f)
////                )
////            }
////
////            FloatSetting(
////                title = "Fog end distance",
////                value = draft.fogEndDistance,
////                range = 1f..300f
////            ) {
////                draft = draft.copy(
////                    fogEndDistance = maxOf(
////                        it,
////                        draft.fogStartDistance + 1f
////                    )
////                )
////            }
////
////            FloatSetting(
////                title = "Fog softness",
////                value = draft.fogSoftness,
////                range = 0f..1f
////            ) {
////                draft = draft.copy(fogSoftness = it)
////            }
////
////            SectionTitle("Fog Color")
////
////            FloatSetting("Red", draft.fogRed, 0f..1f) {
////                draft = draft.copy(fogRed = it)
////            }
////
////            FloatSetting("Green", draft.fogGreen, 0f..1f) {
////                draft = draft.copy(fogGreen = it)
////            }
////
////            FloatSetting("Blue", draft.fogBlue, 0f..1f) {
////                draft = draft.copy(fogBlue = it)
////            }
////
////            Spacer(Modifier.height(12.dp))
////
////            // -------------------------------------------------
////            // SUNLIGHT
////            // -------------------------------------------------
////
////            SectionTitle("Sunlight")
////
////            BooleanSetting(
////                title = "Enable sunlight",
////                checked = draft.sunlightEnabled
////            ) {
////                draft = draft.copy(sunlightEnabled = it)
////            }
////
////            FloatSetting(
////                title = "Sunlight intensity",
////                value = draft.sunlightIntensity,
////                range = 0f..3f
////            ) {
////                draft = draft.copy(sunlightIntensity = it)
////            }
////
////            FloatSetting(
////                title = "Ambient light intensity",
////                value = draft.ambientLightIntensity,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(ambientLightIntensity = it)
////            }
////
////            SectionTitle("Sunlight Color")
////
////            FloatSetting(
////                "Red",
////                draft.sunlightRed,
////                0f..1f
////            ) {
////                draft = draft.copy(sunlightRed = it)
////            }
////
////            FloatSetting(
////                "Green",
////                draft.sunlightGreen,
////                0f..1f
////            ) {
////                draft = draft.copy(sunlightGreen = it)
////            }
////
////            FloatSetting(
////                "Blue",
////                draft.sunlightBlue,
////                0f..1f
////            ) {
////                draft = draft.copy(sunlightBlue = it)
////            }
////
////            SectionTitle("Sunlight Direction")
////
////            FloatSetting(
////                "Direction X",
////                draft.sunDirectionX,
////                -1f..1f
////            ) {
////                draft = draft.copy(sunDirectionX = it)
////            }
////
////            FloatSetting(
////                "Direction Y",
////                draft.sunDirectionY,
////                -1f..1f
////            ) {
////                draft = draft.copy(sunDirectionY = it)
////            }
////
////            FloatSetting(
////                "Direction Z",
////                draft.sunDirectionZ,
////                -1f..1f
////            ) {
////                draft = draft.copy(sunDirectionZ = it)
////            }
////
////            Spacer(Modifier.height(12.dp))
////
////            // -------------------------------------------------
////            // SKY AND ATMOSPHERE
////            // -------------------------------------------------
////
////            SectionTitle("Sky & Atmosphere")
////
////            BooleanSetting(
////                title = "Enable custom sky color",
////                checked = draft.customSkyEnabled
////            ) {
////                draft = draft.copy(customSkyEnabled = it)
////            }
////
////            FloatSetting(
////                title = "Sky brightness",
////                value = draft.skyBrightness,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(skyBrightness = it)
////            }
////
////            FloatSetting(
////                title = "Sky saturation",
////                value = draft.skySaturation,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(skySaturation = it)
////            }
////
////            FloatSetting(
////                title = "Atmosphere strength",
////                value = draft.atmosphereStrength,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(atmosphereStrength = it)
////            }
////
////            FloatSetting(
////                title = "Sky transition softness",
////                value = draft.skyTransitionSoftness,
////                range = 0f..1f
////            ) {
////                draft = draft.copy(skyTransitionSoftness = it)
////            }
////
////            SectionTitle("Sky Color")
////
////            FloatSetting("Red", draft.skyRed, 0f..1f) {
////                draft = draft.copy(skyRed = it)
////            }
////
////            FloatSetting("Green", draft.skyGreen, 0f..1f) {
////                draft = draft.copy(skyGreen = it)
////            }
////
////            FloatSetting("Blue", draft.skyBlue, 0f..1f) {
////                draft = draft.copy(skyBlue = it)
////            }
////
////            Spacer(Modifier.height(12.dp))
////
////            SectionTitle("Overall Environment")
////
////            FloatSetting(
////                title = "Environment brightness",
////                value = draft.environmentBrightness,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(environmentBrightness = it)
////            }
////
////            FloatSetting(
////                title = "Environment saturation",
////                value = draft.environmentSaturation,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(environmentSaturation = it)
////            }
////
////            FloatSetting(
////                title = "Environment contrast",
////                value = draft.environmentContrast,
////                range = 0f..2f
////            ) {
////                draft = draft.copy(environmentContrast = it)
////            }
////
////            FloatSetting(
////                title = "Environment color temperature",
////                value = draft.environmentColorTemperature,
////                range = -1f..1f
////            ) {
////                draft = draft.copy(environmentColorTemperature = it)
////            }
////
////            Spacer(Modifier.height(12.dp))
////        }
////
////        Row(
////            modifier = Modifier
////                .fillMaxWidth()
////                .padding(top = 12.dp),
////            horizontalArrangement = Arrangement.spacedBy(12.dp)
////        ) {
////            OutlinedButton(
////                onClick = onCancel,
////                modifier = Modifier.weight(1f)
////            ) {
////                Text("Cancel")
////            }
////
////            Button(
////                onClick = { onApply(draft) },
////                modifier = Modifier.weight(1f)
////            ) {
////                Text("Apply")
////            }
////        }
////    }
////}
////
////@Composable
////private fun SectionTitle(title: String) {
////    Text(
////        text = title,
////        style = MaterialTheme.typography.titleMedium,
////        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
////    )
////}
////
////@Composable
////private fun FloatSetting(
////    title: String,
////    value: Float,
////    range: ClosedFloatingPointRange<Float>,
////    onValueChange: (Float) -> Unit
////) {
////    Column(
////        modifier = Modifier
////            .fillMaxWidth()
////            .padding(vertical = 3.dp)
////    ) {
////        Text(
////            text = "$title: ${
////                String.format(Locale.US, "%.2f", value)
////            }",
////            style = MaterialTheme.typography.bodyMedium
////        )
////
////        Slider(
////            value = value.coerceIn(range.start, range.endInclusive),
////            onValueChange = onValueChange,
////            valueRange = range
////        )
////    }
////}
////
////@Composable
////private fun BooleanSetting(
////    title: String,
////    checked: Boolean,
////    onCheckedChange: (Boolean) -> Unit
////) {
////    Row(
////        modifier = Modifier
////            .fillMaxWidth()
////            .padding(vertical = 4.dp),
////        horizontalArrangement = Arrangement.SpaceBetween
////    ) {
////        Text(
////            text = title,
////            modifier = Modifier.padding(top = 12.dp)
////        )
////
////        Switch(
////            checked = checked,
////            onCheckedChange = onCheckedChange
////        )
////    }
////}