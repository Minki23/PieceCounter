package com.piececounter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Soft, non-aggressive palette for comfortable part inspection
val EmeraldSelected = Color(0xFF4D9E7D)
val EmeraldSelectedFill = Color(0x364D9E7D)
val CyanAccent = Color(0xFF6E98B8)
val AmberReference = Color(0xFFD49754)
val AmberReferenceFill = Color(0x2ED49754)
val RoseDeselected = Color(0xFFB87A7A)
val RoseDeselectedFill = Color(0x24B87A7A)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF74B89B),
    onPrimary = Color(0xFF102E22),
    primaryContainer = Color(0xFF23483A),
    onPrimaryContainer = Color(0xFFD6EFE3),
    secondary = Color(0xFF8AA8C0),
    onSecondary = Color(0xFF172B3A),
    secondaryContainer = Color(0xFF2B4153),
    onSecondaryContainer = Color(0xFFDCE9F5),
    tertiary = Color(0xFFD49754),
    onTertiary = Color(0xFF382208),
    background = Color(0xFF171A20),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF21262E),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF2D333D),
    onSurfaceVariant = Color(0xFFA6B0BE)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B7A62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EFE5),
    onPrimaryContainer = Color(0xFF14382A),
    secondary = Color(0xFF4F6D82),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8F2),
    onSecondaryContainer = Color(0xFF192E3D),
    tertiary = Color(0xFFB3793B),
    onTertiary = Color.White,
    background = Color(0xFFF5F7F9),
    onBackground = Color(0xFF1E242B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E242B),
    surfaceVariant = Color(0xFFE7ECF0),
    onSurfaceVariant = Color(0xFF525D6B)
)

@Composable
fun PieceCounterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
