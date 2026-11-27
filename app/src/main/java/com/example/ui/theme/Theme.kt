package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FaintRecDarkColorScheme = darkColorScheme(
    primary = CyanPrimaryDark,
    onPrimary = OnCyanDark,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = OnCyanContainerDark,
    secondary = AmberSecondaryDark,
    onSecondary = OnAmberDark,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = OnAmberContainerDark,
    tertiary = EmeraldTertiaryDark,
    onTertiary = OnEmeraldDark,
    tertiaryContainer = EmeraldContainerDark,
    onTertiaryContainer = OnEmeraldContainerDark,
    error = RecCrimsonDark,
    onError = OnRecCrimsonDark,
    errorContainer = RecCrimsonContainerDark,
    onErrorContainer = OnRecCrimsonContainerDark,
    background = StudioBgDark,
    onBackground = TextPrimaryDark,
    surface = StudioSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = StudioSurfaceContainerDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = StudioBgDark,
    surfaceContainerLow = StudioSurfaceDark,
    surfaceContainer = StudioSurfaceContainerDark,
    surfaceContainerHigh = StudioSurfaceHighDark,
    surfaceContainerHighest = StudioSurfaceHighDark,
    outline = StudioOutlineDark,
    outlineVariant = StudioOutlineDark
)

private val FaintRecLightColorScheme = lightColorScheme(
    primary = CyanPrimaryLight,
    onPrimary = OnCyanLight,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = OnCyanContainerLight,
    secondary = AmberSecondaryLight,
    onSecondary = OnAmberLight,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = OnAmberContainerLight,
    tertiary = EmeraldTertiaryLight,
    onTertiary = OnEmeraldLight,
    tertiaryContainer = EmeraldContainerLight,
    onTertiaryContainer = OnEmeraldContainerLight,
    error = RecCrimsonLight,
    onError = OnRecCrimsonLight,
    errorContainer = RecCrimsonContainerLight,
    onErrorContainer = OnRecCrimsonContainerLight,
    background = StudioBgLight,
    onBackground = TextPrimaryLight,
    surface = StudioSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = StudioSurfaceContainerLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = StudioSurfaceLight,
    surfaceContainerLow = StudioBgLight,
    surfaceContainer = StudioSurfaceContainerLight,
    surfaceContainerHigh = StudioSurfaceHighLight,
    surfaceContainerHighest = StudioSurfaceHighLight,
    outline = StudioOutlineLight,
    outlineVariant = StudioOutlineLight
)

val FaintRecShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class ThemePreference { SYSTEM, DARK, LIGHT }

@Composable
fun FaintRecTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    val colorScheme = if (darkTheme) FaintRecDarkColorScheme else FaintRecLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FaintRecTypography,
        shapes = FaintRecShapes,
        content = content
    )
}
