package io.github.meko123456.ardaivitsko.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Pomegranate red for what needs doing, and gold for Can't miss. Every role is set, so none of
 * Material's baseline lavender shows through in either theme. No dynamic colour: the red and the
 * gold mean something here.
 */

private val Light = lightColorScheme(
    primary = Color(0xFF9A2B3D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DC),
    onPrimaryContainer = Color(0xFF3F0011),
    inversePrimary = Color(0xFFFFB2BA),
    secondary = Color(0xFF765658),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9DB),
    onSecondaryContainer = Color(0xFF2C1517),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA2),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF22191A),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF22191A),
    surfaceVariant = Color(0xFFF4DDDE),
    onSurfaceVariant = Color(0xFF524344),
    surfaceTint = Color(0xFF9A2B3D),
    inverseSurface = Color(0xFF382E2F),
    inverseOnSurface = Color(0xFFFEEDED),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857374),
    outlineVariant = Color(0xFFD7C1C2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFE8D6D7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F0),
    surfaceContainer = Color(0xFFFCEAEA),
    surfaceContainerHigh = Color(0xFFF6E4E5),
    surfaceContainerHighest = Color(0xFFF0DEDF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB2BA),
    onPrimary = Color(0xFF5F1224),
    primaryContainer = Color(0xFF7B2939),
    onPrimaryContainer = Color(0xFFFFD9DC),
    inversePrimary = Color(0xFF9A2B3D),
    secondary = Color(0xFFE5BDBF),
    onSecondary = Color(0xFF43292B),
    secondaryContainer = Color(0xFF5C3F41),
    onSecondaryContainer = Color(0xFFFFD9DB),
    tertiary = Color(0xFFF5BF48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDEA2),
    background = Color(0xFF1A1112),
    onBackground = Color(0xFFF0DEDF),
    surface = Color(0xFF1A1112),
    onSurface = Color(0xFFF0DEDF),
    surfaceVariant = Color(0xFF524344),
    onSurfaceVariant = Color(0xFFD7C1C2),
    surfaceTint = Color(0xFFFFB2BA),
    inverseSurface = Color(0xFFF0DEDF),
    inverseOnSurface = Color(0xFF382E2F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C8D),
    outlineVariant = Color(0xFF524344),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423737),
    surfaceDim = Color(0xFF1A1112),
    surfaceContainerLowest = Color(0xFF140C0D),
    surfaceContainerLow = Color(0xFF22191A),
    surfaceContainer = Color(0xFF271D1E),
    surfaceContainerHigh = Color(0xFF322828),
    surfaceContainerHighest = Color(0xFF3D3233),
)

@Composable
fun ArDaivitskoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
