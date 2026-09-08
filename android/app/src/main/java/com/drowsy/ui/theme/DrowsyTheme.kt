package com.drowsy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DrowsyColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF001E2E),
    primaryContainer = Color(0xFF004C6B),
    secondary = Color(0xFF81C784),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFFF6B6B),
)

@Composable
fun DrowsyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DrowsyColors, content = content)
}

object DrowsyPalette {
    val normal = Color(0xFF2EA043)
    val attention = Color(0xFFD29922)
    val fatigue = Color(0xFFDB6D28)
    val highRisk = Color(0xFFDA3633)
    val cameraBg = Color(0xFF010409)
    val overlay = Color(0xCC000000)
}
