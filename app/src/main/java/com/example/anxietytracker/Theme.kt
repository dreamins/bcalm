package com.example.anxietytracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Restrained, near-monochrome palette: paper, white cards, hairlines and one muted navy accent.
/** Named design tokens shared by both colour schemes (see [MaterialTheme.bcalm]). */
@Immutable
class BcalmTokens(
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val innerDivider: Color,
    val segmentedTrack: Color,
    val ink: Color,
    val secondary: Color,
    val accent: Color,
    val accentTint: Color,
    val mutedBar: Color,
    val joy: Color,
    val onAccent: Color,
)

private val LightTokens = BcalmTokens(
    background = Color(0xFFF7F6F2), card = Color(0xFFFFFFFF), cardBorder = Color(0xFFE6E3DC),
    innerDivider = Color(0xFFEFEDE8), segmentedTrack = Color(0xFFEDEBE6), ink = Color(0xFF1C1C1E),
    secondary = Color(0xFF6B6B70), accent = Color(0xFF1F3A5F), accentTint = Color(0xFFEEF1F5),
    mutedBar = Color(0xFFC9D2DE), joy = Color(0xFF8A6A3B), onAccent = Color(0xFFFFFFFF),
)

private val DarkTokens = BcalmTokens(
    background = Color(0xFF121212), card = Color(0xFF1C1C1E), cardBorder = Color(0xFF2C2C2E),
    innerDivider = Color(0xFF262628), segmentedTrack = Color(0xFF232325), ink = Color(0xFFF2F2F2),
    secondary = Color(0xFF9A9AA0), accent = Color(0xFF8FA9C9), accentTint = Color(0xFF1F2733),
    mutedBar = Color(0xFF343C48), joy = Color(0xFFC9A66B), onAccent = Color(0xFF0F1A2A),
)

val LocalBcalm = staticCompositionLocalOf { LightTokens }

val MaterialTheme.bcalm: BcalmTokens
    @Composable @ReadOnlyComposable get() = LocalBcalm.current

private fun schemeLight(t: BcalmTokens) = lightColorScheme(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentTint,
    onPrimaryContainer = t.accent,
    inversePrimary = DarkTokens.accent,
    secondary = t.secondary,
    onSecondary = t.card,
    secondaryContainer = t.segmentedTrack,
    onSecondaryContainer = t.ink,
    tertiary = t.secondary,
    onTertiary = t.card,
    tertiaryContainer = t.segmentedTrack,
    onTertiaryContainer = t.ink,
    background = t.background,
    onBackground = t.ink,
    surface = t.background,
    onSurface = t.ink,
    surfaceVariant = t.segmentedTrack,
    onSurfaceVariant = t.secondary,
    surfaceTint = t.background,
    inverseSurface = t.ink,
    inverseOnSurface = t.background,
    outline = Color(0xFFB8B8BD),
    outlineVariant = t.innerDivider,
    surfaceBright = t.card,
    surfaceDim = t.segmentedTrack,
    surfaceContainerLowest = t.card,
    surfaceContainerLow = t.card,
    surfaceContainer = t.card,
    surfaceContainerHigh = t.card,
    surfaceContainerHighest = t.segmentedTrack,
)

private fun schemeDark(t: BcalmTokens) = darkColorScheme(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentTint,
    onPrimaryContainer = t.accent,
    inversePrimary = LightTokens.accent,
    secondary = t.secondary,
    onSecondary = t.card,
    secondaryContainer = t.segmentedTrack,
    onSecondaryContainer = t.ink,
    tertiary = t.secondary,
    onTertiary = t.card,
    tertiaryContainer = t.segmentedTrack,
    onTertiaryContainer = t.ink,
    background = t.background,
    onBackground = t.ink,
    surface = t.background,
    onSurface = t.ink,
    surfaceVariant = t.segmentedTrack,
    onSurfaceVariant = t.secondary,
    surfaceTint = t.background,
    inverseSurface = t.ink,
    inverseOnSurface = t.background,
    outline = Color(0xFF58585C),
    outlineVariant = t.innerDivider,
    surfaceBright = t.card,
    surfaceDim = t.background,
    surfaceContainerLowest = t.background,
    surfaceContainerLow = t.card,
    surfaceContainer = t.card,
    surfaceContainerHigh = t.card,
    surfaceContainerHighest = t.segmentedTrack,
)

private val LightScheme = schemeLight(LightTokens)
private val DarkScheme = schemeDark(DarkTokens)

private val BaseType = Typography()
private val BcalmTypography = Typography(
    titleLarge = BaseType.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun BcalmTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBcalm provides (if (dark) DarkTokens else LightTokens)) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = BcalmTypography,
            content = content,
        )
    }
}
