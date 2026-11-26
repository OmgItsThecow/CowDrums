package com.cowdrums.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CowColors {
    val BgDark = Color(0xFF0D0F14)
    val SurfacePanel = Color(0xFF151922)
    val SurfaceCard = Color(0xFF1C212E)
    val SurfaceElevated = Color(0xFF242B3B)
    val BorderSubtle = Color(0xFF2D3548)

    val TextPrimary = Color(0xFFF3F5F9)
    val TextSecondary = Color(0xFF9AA4B8)
    val TextMuted = Color(0xFF647088)

    val AccentAmber = Color(0xFFF59E0B)
    val AccentCoral = Color(0xFFEF4444)
    val AccentCyan = Color(0xFF06B6D4)
    val AccentGreen = Color(0xFF10B981)
    val AccentViolet = Color(0xFF8B5CF6)
    val AccentPink = Color(0xFFEC4899)
}

private val CowDarkColorScheme = darkColorScheme(
    primary = CowColors.AccentAmber,
    onPrimary = Color(0xFF111318),
    secondary = CowColors.AccentCyan,
    onSecondary = Color(0xFF0A1014),
    tertiary = CowColors.AccentViolet,
    background = CowColors.BgDark,
    onBackground = CowColors.TextPrimary,
    surface = CowColors.SurfacePanel,
    onSurface = CowColors.TextPrimary,
    surfaceVariant = CowColors.SurfaceCard,
    onSurfaceVariant = CowColors.TextSecondary,
    outline = CowColors.BorderSubtle
)

@Composable
fun CowDrumsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CowDarkColorScheme,
        content = content
    )
}
