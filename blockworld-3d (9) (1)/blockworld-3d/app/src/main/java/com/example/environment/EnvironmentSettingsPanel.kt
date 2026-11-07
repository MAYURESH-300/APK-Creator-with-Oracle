package com.example.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun EnvironmentSettingsPanel(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val s = EnvironmentController.current

    val update:
                ((EnvironmentSettings) -> EnvironmentSettings) -> Unit =
        { transform ->
            EnvironmentController.update(context, transform)
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            "World Environment",
            style = MaterialTheme.typography.headlineSmall
        )

        // FOG

        SectionTitle("Fog")

        ToggleRow(
            "Enable fog",
            s.fogEnabled
        ) { enabled ->
            update { it.copy(fogEnabled = enabled) }
        }

        EnvSlider("Fog Red", s.fogColorR, 0f..1f) {
            update { s0 -> s0.copy(fogColorR = it) }
        }

        EnvSlider("Fog Green", s.fogColorG, 0f..1f) {
            update { s0 -> s0.copy(fogColorG = it) }
        }

        EnvSlider("Fog Blue", s.fogColorB, 0f..1f) {
            update { s0 -> s0.copy(fogColorB = it) }
        }

        EnvSlider("Fog Density", s.fogDensity, 0f..0.2f) {
            update { s0 -> s0.copy(fogDensity = it) }
        }

        EnvSlider("Fog Start Distance", s.fogStart, 0f..500f) {
            update { s0 -> s0.copy(fogStart = it) }
        }

        EnvSlider("Fog End Distance", s.fogEnd, 1f..1000f) {
            update { s0 -> s0.copy(fogEnd = it) }
        }

        Spacer(Modifier.height(8.dp))

        // SKY

        SectionTitle("Sky & Atmosphere")

        ToggleRow(
            "Override sky color",
            s.skyOverrideEnabled
        ) { enabled ->
            update { it.copy(skyOverrideEnabled = enabled) }
        }

        EnvSlider("Sky Red", s.skyColorR, 0f..1f) {
            update { s0 -> s0.copy(skyColorR = it) }
        }

        EnvSlider("Sky Green", s.skyColorG, 0f..1f) {
            update { s0 -> s0.copy(skyColorG = it) }
        }

        EnvSlider("Sky Blue", s.skyColorB, 0f..1f) {
            update { s0 -> s0.copy(skyColorB = it) }
        }

        EnvSlider("Atmosphere", s.atmosphere, 0f..1f) {
            update { s0 -> s0.copy(atmosphere = it) }
        }

        Spacer(Modifier.height(8.dp))

        // SUN AND LIGHTING

        SectionTitle("Sun & Lighting")

        ToggleRow(
            "Enable environment sun lighting",
            s.sunLightingEnabled
        ) { enabled ->
            update { it.copy(sunLightingEnabled = enabled) }
        }

        EnvSlider("Sun Red", s.sunColorR, 0f..1f) {
            update { s0 -> s0.copy(sunColorR = it) }
        }

        EnvSlider("Sun Green", s.sunColorG, 0f..1f) {
            update { s0 -> s0.copy(sunColorG = it) }
        }

        EnvSlider("Sun Blue", s.sunColorB, 0f..1f) {
            update { s0 -> s0.copy(sunColorB = it) }
        }

        EnvSlider("Sun Intensity", s.sunIntensity, 0f..4f) {
            update { s0 -> s0.copy(sunIntensity = it) }
        }

        EnvSlider("Sun Direction X", s.sunDirectionX, -1f..1f) {
            update { s0 -> s0.copy(sunDirectionX = it) }
        }

        EnvSlider("Sun Direction Y", s.sunDirectionY, -1f..1f) {
            update { s0 -> s0.copy(sunDirectionY = it) }
        }

        EnvSlider("Sun Direction Z", s.sunDirectionZ, -1f..1f) {
            update { s0 -> s0.copy(sunDirectionZ = it) }
        }

        EnvSlider("Ambient Light", s.ambientIntensity, 0f..2f) {
            update { s0 -> s0.copy(ambientIntensity = it) }
        }

        Spacer(Modifier.height(8.dp))

        // COLOR GRADING

        SectionTitle("Color Grading")

        EnvSlider("Temperature", s.temperature, -1f..1f) {
            update { s0 -> s0.copy(temperature = it) }
        }

        EnvSlider("Saturation", s.saturation, 0f..2f) {
            update { s0 -> s0.copy(saturation = it) }
        }

        EnvSlider("Brightness", s.brightness, 0f..2f) {
            update { s0 -> s0.copy(brightness = it) }
        }

        EnvSlider("Contrast", s.contrast, 0f..2f) {
            update { s0 -> s0.copy(contrast = it) }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun EnvSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title)

            Text(
                String.format(Locale.US, "%.2f", value)
            )
        }

        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}