package de.tankradar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mirrors assets/style.css so the phone app and the desktop dashboard read as
// one product.
val TankRadarBackground = Color(0xFF07111F)
val TankRadarSurface = Color(0xFF0D1A2B)
val TankRadarSurfaceHigh = Color(0xFF12233A)
val TankRadarAccent = Color(0xFF45A3FF)
val TankRadarSuccess = Color(0xFF22E77B)
val TankRadarWarning = Color(0xFFFFD33D)
val TankRadarDanger = Color(0xFFFF5C54)
val TankRadarText = Color(0xFFF6FBFF)
val TankRadarTextDim = Color(0xFF91A4BA)

private val DarkColors = darkColorScheme(
    primary = TankRadarAccent,
    onPrimary = TankRadarBackground,
    secondary = TankRadarSuccess,
    onSecondary = TankRadarBackground,
    background = TankRadarBackground,
    onBackground = TankRadarText,
    surface = TankRadarSurface,
    onSurface = TankRadarText,
    surfaceVariant = TankRadarSurfaceHigh,
    onSurfaceVariant = TankRadarTextDim,
    error = TankRadarDanger,
)

@Composable
fun TankRadarTheme(
    // The dashboard has always been dark-only; a light variant would need its own
    // chart palette, so the app commits to dark as well.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
