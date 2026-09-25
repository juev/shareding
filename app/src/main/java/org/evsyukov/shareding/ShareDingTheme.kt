package org.evsyukov.shareding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176A70), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EAF0), onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4D6265), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E6E9), onSecondaryContainer = Color(0xFF081F22),
    background = Color(0xFFF7F9F9), onBackground = Color(0xFF181D1E),
    surface = Color(0xFFF7F9F9), onSurface = Color(0xFF181D1E),
    surfaceDim = Color(0xFFD7DEDF), surfaceBright = Color(0xFFF7F9F9),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F5F5),
    surfaceContainer = Color(0xFFECF1F1), surfaceContainerHigh = Color(0xFFE7EDED),
    surfaceContainerHighest = Color(0xFFE1E8E9),
    surfaceVariant = Color(0xFFDCE4E5), onSurfaceVariant = Color(0xFF405154),
    outline = Color(0xFF6D7D80),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84D3DB), onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F54), onPrimaryContainer = Color(0xFFB9EAF0),
    secondary = Color(0xFFB4CACE), onSecondary = Color(0xFF1F3437),
    secondaryContainer = Color(0xFF354B4E), onSecondaryContainer = Color(0xFFD0E6E9),
    background = Color(0xFF101718), onBackground = Color(0xFFDFE4E5),
    surface = Color(0xFF101718), onSurface = Color(0xFFDFE4E5),
    surfaceDim = Color(0xFF101718), surfaceBright = Color(0xFF354041),
    surfaceContainerLowest = Color(0xFF0C1213), surfaceContainerLow = Color(0xFF192122),
    surfaceContainer = Color(0xFF1D2728), surfaceContainerHigh = Color(0xFF293334),
    surfaceContainerHighest = Color(0xFF344041),
    surfaceVariant = Color(0xFF405154), onSurfaceVariant = Color(0xFFBFC9CB),
    outline = Color(0xFF899396),
)

@Composable
fun ShareDingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content)
}
