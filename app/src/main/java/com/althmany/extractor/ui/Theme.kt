package com.althmany.extractor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFF18F17A),
    onPrimary = Color(0xFF001B0A),
    primaryContainer = Color(0xFF0A3B25),
    onPrimaryContainer = Color(0xFFD4FFE4),
    secondary = Color(0xFF39D9FF),
    onSecondary = Color(0xFF001F29),
    background = Color(0xFF020A12),
    surface = Color(0xFF071824),
    surfaceVariant = Color(0xFF0B2230),
    outline = Color(0xFF284352),
    onBackground = Color(0xFFF3F8FB),
    onSurface = Color(0xFFF3F8FB),
    onSurfaceVariant = Color(0xFFA8B5BF),
    error = Color(0xFFFF4D62)
)

@Composable
fun AlThmanyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography(),
        content = content
    )
}
