package de.tankradar.tagebuch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F6466),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDEA),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFFB7791F),
    secondaryContainer = Color(0xFFFFE3B0),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF3D5A80),
    background = Color(0xFFF6F8F7),
    surface = Color(0xFFF6F8F7),
    surfaceVariant = Color(0xFFE2E8E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD4CF),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF0F4F50),
    onPrimaryContainer = Color(0xFFCDEDEA),
    secondary = Color(0xFFF2C46B),
    secondaryContainer = Color(0xFF5A3F00),
    onSecondaryContainer = Color(0xFFFFE3B0),
    tertiary = Color(0xFFA9C7F0),
    background = Color(0xFF101514),
    surface = Color(0xFF101514),
    surfaceVariant = Color(0xFF2A3331),
)

@Composable
fun TankTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
