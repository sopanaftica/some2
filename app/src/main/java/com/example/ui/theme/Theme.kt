package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = MinPrimary,
    secondary = MinPrimary,
    tertiary = MinPrimary,
    background = MinBackground,
    surface = MinSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = MinText,
    onSurface = MinText,
    surfaceVariant = MinCardBackground,
    onSurfaceVariant = MinSecondaryText,
    outline = MinBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
