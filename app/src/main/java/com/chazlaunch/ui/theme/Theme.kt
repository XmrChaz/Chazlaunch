package com.chazlaunch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF5EE6C5)
private val AccentDim = Color(0xFF2A7A68)
private val Bg = Color(0xFF0B0F14)
private val Surface = Color(0xFF121821)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201A),
    secondary = AccentDim,
    background = Bg,
    onBackground = Color(0xFFE6EDF3),
    surface = Surface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2430),
)

private val LightColors = lightColorScheme(
    primary = AccentDim,
    secondary = Accent,
    background = Color(0xFFF7FAFC),
)

@Composable
fun ChazTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
