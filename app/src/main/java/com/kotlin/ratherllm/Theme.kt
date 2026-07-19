package com.kotlin.ratherllm

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Fixed warm-dark theme matching the "RatherLLM Dark" design (no system light/dark
 * following, no dynamic color — the design is intentionally a single dark look).
 * The Material color scheme is mapped onto our [AppColors] so the few stock
 * Material 3 components we use (bottom sheet, slider) render on-brand.
 */
private val RatherColorScheme = darkColorScheme(
    primary = AppColors.AccentA,
    onPrimary = AppColors.Bg,
    primaryContainer = AppColors.SurfaceBubble,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.AccentB,
    onSecondary = AppColors.Bg,
    background = AppColors.Bg,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceBubble,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.SurfaceMenu,
    surfaceContainerHigh = AppColors.SurfaceMenu,
    surfaceContainerHighest = AppColors.CodeBg,
    surfaceContainerLow = AppColors.SurfaceDrawer,
    error = AppColors.Danger,
    onError = AppColors.Bg,
    outline = AppColors.cream(0.16f),
    outlineVariant = AppColors.cream(0.08f),
    scrim = androidx.compose.ui.graphics.Color(0x99000000),
)

private val RatherTypography = Typography(
    bodyLarge = AppText.Body,
    bodyMedium = AppText.Small,
    labelSmall = AppText.Meta,
    titleMedium = AppText.SheetTitle,
    titleLarge = AppText.Wordmark,
)

// Rounder menus/sheets than the Material default (design uses 14–16px radii).
private val RatherShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun RatherLlmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RatherColorScheme,
        typography = RatherTypography,
        shapes = RatherShapes,
        content = content,
    )
}
