package com.taktey.simplepdfreader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF255C4A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EADF), onPrimaryContainer = Color(0xFF163E30),
    background = Color(0xFFF6F5F0), surface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFFE7E9E2), onSurface = Color(0xFF202923),
    onSurfaceVariant = Color(0xFF626C65), outlineVariant = Color(0xFFD5DAD2),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D3BA), onPrimary = Color(0xFF113828),
    primaryContainer = Color(0xFF2E5140), onPrimaryContainer = Color(0xFFD9EADF),
    background = Color(0xFF151A17), surface = Color(0xFF1D231F),
    surfaceVariant = Color(0xFF303A33), onSurface = Color(0xFFE3E8DF),
    onSurfaceVariant = Color(0xFFB5C1B6), outlineVariant = Color(0xFF414C43),
)

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
