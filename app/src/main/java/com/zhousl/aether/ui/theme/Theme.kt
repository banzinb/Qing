package com.zhousl.aether.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zhousl.aether.data.AppAccent
import com.zhousl.aether.data.AppThemeMode
import com.zhousl.aether.platform.LocalReduceMotion
import com.zhousl.aether.platform.rememberPlatformAccessibilityPreferences

private fun aetherLightColors(palette: AetherPalette) = lightColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.onPrimaryContainer,
    secondary = palette.secondary,
    onSecondary = palette.onSecondary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.onSecondaryContainer,
    background = palette.background,
    surface = palette.surface,
    surfaceVariant = palette.surfaceVariant,
    onSurface = palette.onSurface,
    onSurfaceVariant = palette.onSurfaceVariant,
    tertiary = palette.tertiary,
    error = palette.error,
    outline = palette.outline,
)

private fun aetherDarkColors(palette: AetherPalette) = darkColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.onPrimaryContainer,
    secondary = palette.secondary,
    onSecondary = palette.onSecondary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.onSecondaryContainer,
    background = palette.background,
    surface = palette.surface,
    surfaceVariant = palette.surfaceVariant,
    onSurface = palette.onSurface,
    onSurfaceVariant = palette.onSurfaceVariant,
    tertiary = palette.tertiary,
    error = palette.error,
    outline = palette.outline,
)

private val AetherTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun AetherTheme(
    themeMode: AppThemeMode = AppThemeMode.System,
    accent: AppAccent = AppAccent.Teal,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val accessibility = rememberPlatformAccessibilityPreferences()
    val palette = paletteFor(darkTheme, accent, accessibility.increasedContrast)
    SideEffect {
        updateAetherPalette(darkTheme, accent, accessibility.increasedContrast)
    }
    CompositionLocalProvider(LocalReduceMotion provides accessibility.reduceMotion) {
        MaterialTheme(
            colorScheme = if (darkTheme) aetherDarkColors(palette) else aetherLightColors(palette),
            typography = AetherTypography,
            content = content,
        )
    }
}