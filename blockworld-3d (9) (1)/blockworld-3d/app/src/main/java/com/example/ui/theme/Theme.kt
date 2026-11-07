package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BlockCyanAccent,
    onPrimary = Color.Black,
    secondary = BlockBluePrimary,
    onSecondary = Color.White,
    tertiary = BlockGold,
    background = BlockDarkSurface,
    surface = BlockDarkCard,
    onBackground = BlockTextPrimary,
    onSurface = BlockTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = BlockBluePrimary,
    onPrimary = Color.White,
    secondary = BlockCyanAccent,
    onSecondary = Color.Black,
    tertiary = BlockGold,
    background = Color(0xFFF0F4F8),
    surface = Color.White,
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our crisp gaming colors by default
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // Game looks best with dark glassmorphic gaming UI
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
