package com.serverprobe.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.serverprobe.manager.data.repo.DisplayMode

private val Teal = Color(0xFF17B8A6)
private val TealDark = Color(0xFF0E8C7F)
private val Navy = Color(0xFF12365B)

private val LightColors = lightColorScheme(
    primary = TealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F2EA),
    onPrimaryContainer = Color(0xFF042F2B),
    secondary = Navy,
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6ECF3),
    onSurfaceVariant = Color(0xFF44505C),
)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF04201D),
    primaryContainer = Color(0xFF0B4A43),
    onPrimaryContainer = Color(0xFFB7F2EA),
    secondary = Color(0xFF9FC3DC),
    background = Color(0xFF10131A),
    surface = Color(0xFF181C26),
    surfaceVariant = Color(0xFF232936),
    onSurfaceVariant = Color(0xFFB3BCC9),
)

@Composable
fun ServerProbeTheme(displayMode: DisplayMode, content: @Composable () -> Unit) {
    val dark = when (displayMode) {
        DisplayMode.SYSTEM -> isSystemInDarkTheme()
        DisplayMode.LIGHT -> false
        DisplayMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
