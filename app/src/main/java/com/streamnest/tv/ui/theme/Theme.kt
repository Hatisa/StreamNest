package com.streamnest.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A dark-first palette.
 *
 * The app is intentionally dark-only: IPTV viewing happens in dim rooms, OLED
 * phones benefit from a near-black background, and a second palette that is
 * never reachable would just be untested code. If a light theme is ever wanted,
 * it should be added together with a real user setting.
 */
private val StreamNestColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF03271B),
    primaryContainer = Color(0xFF11493A),
    onPrimaryContainer = Color(0xFFB9F3DD),
    secondary = Color(0xFF7DB2FF),
    onSecondary = Color(0xFF0A2340),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE4E9F2),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE4E9F2),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFFB4BFD4),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    outline = Color(0xFF3A4763),
)

@Composable
fun StreamNestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StreamNestColors,
        content = content,
    )
}
