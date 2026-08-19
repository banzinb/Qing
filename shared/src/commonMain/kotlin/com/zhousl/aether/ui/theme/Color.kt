package com.zhousl.aether.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.zhousl.aether.data.AppAccent

data class AetherPalette(
    val background: Color,
    val backgroundGradientTop: Color,
    val settingsBackground: Color,
    val sidebarBackground: Color,
    val sidebarControl: Color,
    val settingsIcon: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSoft: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val error: Color,
    val messageBubble: Color,
    val scrim: Color,
)

data class QingNeutralPalette(
    val background: Color,
    val backgroundGradientTop: Color,
    val settingsBackground: Color,
    val sidebarBackground: Color,
    val sidebarControl: Color,
    val settingsIcon: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSoft: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val error: Color,
    val messageBubble: Color,
    val scrim: Color,
)

data class QingAccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
)

private val LightQingNeutral = QingNeutralPalette(
    background = Color(0xFFFAFAFA),
    backgroundGradientTop = Color(0xFFF5F5F7),
    settingsBackground = Color(0xFFF2F2F7),
    sidebarBackground = Color(0xFFF7F7F9),
    sidebarControl = Color(0xFFF2F2F4),
    settingsIcon = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF2F2F4),
    surfaceHigher = Color(0xFFE8E8EA),
    surfaceVariant = Color(0xFFEFEFF2),
    outline = Color(0xFFE3E3E8),
    outlineSoft = Color(0xFFECECEF),
    onSurface = Color(0xFF1D1D1F),
    onSurfaceVariant = Color(0xFF6E6E73),
    error = Color(0xFFB43E3E),
    messageBubble = Color(0xFFF2F2F4),
    scrim = Color(0x22000000),
)

private val DarkQingNeutral = QingNeutralPalette(
    background = Color(0xFF0B0B0D),
    backgroundGradientTop = Color(0xFF000000),
    settingsBackground = Color(0xFF0B0B0D),
    sidebarBackground = Color(0xFF151517),
    sidebarControl = Color(0xFF232326),
    settingsIcon = Color(0xFFF5F5F7),
    surface = Color(0xFF1C1C1E),
    surfaceHigh = Color(0xFF232326),
    surfaceHigher = Color(0xFF2C2C2E),
    surfaceVariant = Color(0xFF2A2A2D),
    outline = Color(0xFF3A3A3C),
    outlineSoft = Color(0xFF323234),
    onSurface = Color(0xFFF5F5F7),
    onSurfaceVariant = Color(0xFF98989D),
    error = Color(0xFFFF8E8E),
    messageBubble = Color(0xFF232326),
    scrim = Color(0x66000000),
)

private val LightHighContrastQingNeutral = LightQingNeutral.copy(
    outline = Color(0xFFA8A8AD),
    outlineSoft = Color(0xFFC7C7CC),
    onSurface = Color(0xFF111113),
    onSurfaceVariant = Color(0xFF525258),
)

private val DarkHighContrastQingNeutral = DarkQingNeutral.copy(
    outline = Color(0xFF6B6B70),
    outlineSoft = Color(0xFF55555A),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD2D2D7),
)

val QingAzureLight = QingAccentColors(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = Color(0xFF003B73),
    secondary = Color(0xFF2A6F97),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEAF2),
    onSecondaryContainer = Color(0xFF103B51),
    tertiary = Color(0xFF64B5FF),
)

val QingTealLight = QingAccentColors(
    primary = Color(0xFF00A6A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCF2F0),
    onPrimaryContainer = Color(0xFF005454),
    secondary = Color(0xFF2F7D77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4EDE9),
    onSecondaryContainer = Color(0xFF0C4A45),
    tertiary = Color(0xFF6FD3D3),
)

val QingIndigoLight = QingAccentColors(
    primary = Color(0xFF5E5CE6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF2621A8),
    secondary = Color(0xFF5856D6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0DFFF),
    onSecondaryContainer = Color(0xFF302EA6),
    tertiary = Color(0xFF9C9AF8),
)

val QingGreenLight = QingAccentColors(
    primary = Color(0xFF34C759),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7F7DF),
    onPrimaryContainer = Color(0xFF0B5E2A),
    secondary = Color(0xFF2F9E4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3F0DB),
    onSecondaryContainer = Color(0xFF0C4A22),
    tertiary = Color(0xFF6FD98A),
)

val QingAzureDark = QingAccentColors(
    primary = Color(0xFF64B5FF),
    onPrimary = Color(0xFF002E5E),
    primaryContainer = Color(0xFF003B73),
    onPrimaryContainer = Color(0xFFDDEBFF),
    secondary = Color(0xFF82C0F7),
    onSecondary = Color(0xFF0A334F),
    secondaryContainer = Color(0xFF103B51),
    onSecondaryContainer = Color(0xFFD6E9FA),
    tertiary = Color(0xFF0A84FF),
)

val QingTealDark = QingAccentColors(
    primary = Color(0xFF6FD3D3),
    onPrimary = Color(0xFF003B38),
    primaryContainer = Color(0xFF005454),
    onPrimaryContainer = Color(0xFFCCF2F0),
    secondary = Color(0xFF8ACFC9),
    onSecondary = Color(0xFF0A3E3A),
    secondaryContainer = Color(0xFF0C4A45),
    onSecondaryContainer = Color(0xFFD4EDE9),
    tertiary = Color(0xFF00A6A6),
)

val QingIndigoDark = QingAccentColors(
    primary = Color(0xFF9C9AF8),
    onPrimary = Color(0xFF221E9E),
    primaryContainer = Color(0xFF4038C2),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFFB0AEF9),
    onSecondary = Color(0xFF29259E),
    secondaryContainer = Color(0xFF302EA6),
    onSecondaryContainer = Color(0xFFE0DFFF),
    tertiary = Color(0xFF5E5CE6),
)

val QingGreenDark = QingAccentColors(
    primary = Color(0xFF6FD98A),
    onPrimary = Color(0xFF0B4A20),
    primaryContainer = Color(0xFF1E6B33),
    onPrimaryContainer = Color(0xFFD7F7DF),
    secondary = Color(0xFF92DDA6),
    onSecondary = Color(0xFF0A4020),
    secondaryContainer = Color(0xFF0C4A22),
    onSecondaryContainer = Color(0xFFD3F0DB),
    tertiary = Color(0xFF34C759),
)

fun accentColors(accent: AppAccent, darkTheme: Boolean): QingAccentColors = when (accent) {
    AppAccent.Azure -> if (darkTheme) QingAzureDark else QingAzureLight
    AppAccent.Teal -> if (darkTheme) QingTealDark else QingTealLight
    AppAccent.Indigo -> if (darkTheme) QingIndigoDark else QingIndigoLight
    AppAccent.Green -> if (darkTheme) QingGreenDark else QingGreenLight
}

fun neutralPalette(darkTheme: Boolean, increasedContrast: Boolean): QingNeutralPalette = when {
    darkTheme && increasedContrast -> DarkHighContrastQingNeutral
    darkTheme -> DarkQingNeutral
    increasedContrast -> LightHighContrastQingNeutral
    else -> LightQingNeutral
}

fun aetherPalette(neutral: QingNeutralPalette, accent: QingAccentColors): AetherPalette = AetherPalette(
    background = neutral.background,
    backgroundGradientTop = neutral.backgroundGradientTop,
    surface = neutral.surface,
    surfaceHigh = neutral.surfaceHigh,
    surfaceHigher = neutral.surfaceHigher,
    surfaceVariant = neutral.surfaceVariant,
    outline = neutral.outline,
    outlineSoft = neutral.outlineSoft,
    onSurface = neutral.onSurface,
    onSurfaceVariant = neutral.onSurfaceVariant,
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary,
    onSecondary = accent.onSecondary,
    secondaryContainer = accent.secondaryContainer,
    onSecondaryContainer = accent.onSecondaryContainer,
    tertiary = accent.tertiary,
    error = neutral.error,
    settingsBackground = neutral.settingsBackground,
    sidebarBackground = neutral.sidebarBackground,
    sidebarControl = neutral.sidebarControl,
    settingsIcon = neutral.settingsIcon,
    messageBubble = neutral.messageBubble,
    scrim = neutral.scrim,
)

fun paletteFor(
    darkTheme: Boolean,
    accent: AppAccent = AppAccent.Teal,
    increasedContrast: Boolean = false,
): AetherPalette = aetherPalette(
    neutralPalette(darkTheme, increasedContrast),
    accentColors(accent, darkTheme),
)

private var currentPalette by mutableStateOf(paletteFor(darkTheme = false))

fun updateAetherPalette(
    darkTheme: Boolean,
    accent: AppAccent = AppAccent.Teal,
    increasedContrast: Boolean = false,
) {
    val palette = paletteFor(darkTheme, accent, increasedContrast)
    if (currentPalette != palette) {
        currentPalette = palette
    }
}

val AetherBackground: Color
    get() = currentPalette.background

val AetherBackgroundGradientTop: Color
    get() = currentPalette.backgroundGradientTop

val AetherSettingsBackground: Color
    get() = currentPalette.settingsBackground

val AetherSidebarBackground: Color
    get() = currentPalette.sidebarBackground

val AetherSidebarControl: Color
    get() = currentPalette.sidebarControl

val AetherSettingsIcon: Color
    get() = currentPalette.settingsIcon

val AetherSurface: Color
    get() = currentPalette.surface

val AetherSurfaceHigh: Color
    get() = currentPalette.surfaceHigh

val AetherSurfaceHigher: Color
    get() = currentPalette.surfaceHigher

val AetherSurfaceVariant: Color
    get() = currentPalette.surfaceVariant

val AetherOutline: Color
    get() = currentPalette.outline

val AetherOutlineSoft: Color
    get() = currentPalette.outlineSoft

val AetherOnSurface: Color
    get() = currentPalette.onSurface

val AetherOnSurfaceVariant: Color
    get() = currentPalette.onSurfaceVariant

val AetherPrimary: Color
    get() = currentPalette.primary

val AetherOnPrimary: Color
    get() = currentPalette.onPrimary

val AetherPrimaryContainer: Color
    get() = currentPalette.primaryContainer

val AetherOnPrimaryContainer: Color
    get() = currentPalette.onPrimaryContainer

val AetherSecondary: Color
    get() = currentPalette.secondary

val AetherOnSecondary: Color
    get() = currentPalette.onSecondary

val AetherSecondaryContainer: Color
    get() = currentPalette.secondaryContainer

val AetherOnSecondaryContainer: Color
    get() = currentPalette.onSecondaryContainer

val AetherTertiary: Color
    get() = currentPalette.tertiary

val AetherError: Color
    get() = currentPalette.error

val AetherMessageBubble: Color
    get() = currentPalette.messageBubble

val AetherScrim: Color
    get() = currentPalette.scrim