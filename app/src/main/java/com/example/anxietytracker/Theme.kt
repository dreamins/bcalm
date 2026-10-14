package com.example.anxietytracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Restrained, near-monochrome palette: paper, ink, hairlines and one muted navy accent.
val InkNavy = Color(0xFF1F3A5F)
private val NavyOnDark = Color(0xFF8FA9C9)

private val LightScheme = lightColorScheme(
    primary = InkNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEBE6),
    onPrimaryContainer = Color(0xFF1C1C1E),
    inversePrimary = NavyOnDark,
    secondary = Color(0xFF6E6E73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEBE6),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF6E6E73),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEBE6),
    onTertiaryContainer = Color(0xFF1C1C1E),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF0EEEA),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceTint = Color(0xFFFAF9F6),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    outline = Color(0xFFB8B8BD),
    outlineVariant = Color(0xFFE5E5EA),
    surfaceBright = Color(0xFFFAF9F6),
    surfaceDim = Color(0xFFE5E3DF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F3EF),
    surfaceContainer = Color(0xFFF0EEEA),
    surfaceContainerHigh = Color(0xFFEBE9E5),
    surfaceContainerHighest = Color(0xFFE5E3DF),
)

private val DarkScheme = darkColorScheme(
    primary = NavyOnDark,
    onPrimary = Color(0xFF0F1E33),
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color(0xFFEDEDED),
    inversePrimary = InkNavy,
    secondary = Color(0xFF9A9AA0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFF9A9AA0),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF2C2C2E),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceTint = Color(0xFF121212),
    inverseSurface = Color(0xFFE5E5EA),
    inverseOnSurface = Color(0xFF1C1C1E),
    outline = Color(0xFF58585C),
    outlineVariant = Color(0xFF2C2C2E),
    surfaceBright = Color(0xFF2C2C2E),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
)

private val BaseType = Typography()
private val BcalmTypography = Typography(
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun BcalmTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = BcalmTypography,
        content = content,
    )
}
