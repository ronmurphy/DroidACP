package com.droidacp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Android-only: uses your wallpaper colours (Material You) on Android 12+,
// otherwise a warm orange theme that matches the launcher icon.

private val LightColors = lightColorScheme(
    primary = Color(0xFFA6461A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCD),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574B),
    tertiary = Color(0xFF6A5E2F),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB597),
    onPrimary = Color(0xFF5A1C00),
    primaryContainer = Color(0xFF7E2D05),
    onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Color(0xFFE7BEAE),
    tertiary = Color(0xFFD6C68D),
    background = Color(0xFF1A110E),
    surface = Color(0xFF1A110E),
)

@Composable
fun DroidAcpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
