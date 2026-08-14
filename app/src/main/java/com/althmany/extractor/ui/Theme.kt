package com.althmany.extractor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark control-center palette matching the approved AL-thmany Extractor dashboard. */
private val AppColors = darkColorScheme(
    primary = Color(0xFF00D79A),
    onPrimary = Color(0xFF001A12),
    primaryContainer = Color(0xFF073D31),
    onPrimaryContainer = Color(0xFFD7FFF2),
    secondary = Color(0xFF43D7FF),
    onSecondary = Color(0xFF001F29),
    background = Color(0xFF0A0D12),
    surface = Color(0xFF12171F),
    surfaceVariant = Color(0xFF181E28),
    outline = Color(0xFF2A3442),
    onBackground = Color(0xFFF4F7FA),
    onSurface = Color(0xFFF4F7FA),
    onSurfaceVariant = Color(0xFF9AA8B6),
    error = Color(0xFFFF5570)
)

@Composable
fun AlThmanyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography(),
        content = content
    )
}
