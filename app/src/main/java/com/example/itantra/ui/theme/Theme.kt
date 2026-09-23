package com.example.itantra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RetroColorScheme = darkColorScheme(
    primary = RetroAmber,
    onPrimary = RetroBackground,
    primaryContainer = RetroAmberDark,
    onPrimaryContainer = RetroWhite,
    secondary = RetroCyan,
    onSecondary = RetroBackground,
    secondaryContainer = RetroSurfaceVariant,
    onSecondaryContainer = RetroWhite,
    tertiary = RetroGreen,
    onTertiary = RetroBackground,
    background = RetroBackground,
    onBackground = RetroWhite,
    surface = RetroSurface,
    onSurface = RetroWhite,
    surfaceVariant = RetroSurfaceVariant,
    onSurfaceVariant = RetroGray,
    error = RetroRed,
    onError = RetroWhite,
    outline = RetroDarkGray,
    outlineVariant = RetroGray
)

@Composable
fun ITantraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RetroColorScheme,
        content = content
    )
}
