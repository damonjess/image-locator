package com.example.location_finder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cyber Hacker Palette
val CyberGreen = Color(0xFF00FF66)          // Matrix / Cyber Neon Green
val CyberGreenDark = Color(0xFF003B19)      // Dark Cyber Container
val CyberGreenBright = Color(0xFF39FF14)    // Electric Matrix Lime
val CyberCyan = Color(0xFF00E5FF)           // Cyber Neon Cyan
val CyberCyanDark = Color(0xFF003344)       // Dark Cyan Container
val CyberVoid = Color(0xFF05090E)           // Deep Pitch Black Void
val CyberSurface = Color(0xFF0A1017)        // Terminal Dark Surface
val CyberSurfaceVariant = Color(0xFF101924) // Secondary Cyber Surface
val CyberOnSurface = Color(0xFFD0F8E0)      // Matrix Glowing Text
val CyberOnSurfaceMuted = Color(0xFF648A73) // Muted Matrix Text
val CyberOutline = Color(0xFF00FF66)        // Neon Green Outline / Border

private val CyberColorScheme = darkColorScheme(
    primary = CyberGreen,
    onPrimary = Color(0xFF00220B),
    primaryContainer = CyberGreenDark,
    onPrimaryContainer = Color(0xFF66FF99),
    secondary = CyberCyan,
    onSecondary = Color(0xFF002533),
    secondaryContainer = CyberCyanDark,
    onSecondaryContainer = Color(0xFF80F4FF),
    tertiary = CyberGreenBright,
    background = CyberVoid,
    onBackground = CyberOnSurface,
    surface = CyberSurface,
    onSurface = CyberOnSurface,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberOnSurfaceMuted,
    outline = CyberOutline,
    outlineVariant = Color(0xFF1E3A2B)
)

private val defaultTypography = Typography()
val CyberTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = FontFamily.Monospace),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = FontFamily.Monospace),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = FontFamily.Monospace),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = FontFamily.Monospace)
)

@Composable
fun LocationFinderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography = CyberTypography,
        content = content
    )
}

