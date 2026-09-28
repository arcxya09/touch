package com.arcxya09.touch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF396B4B), secondary = Color(0xFFB95143),
    background = Color(0xFFF7F5EF), surface = Color(0xFFF7F5EF), surfaceVariant = Color(0xFFE9EDE4),
    onBackground = Color(0xFF25352B), onSurface = Color(0xFF25352B), primaryContainer = Color(0xFFDDEBD9))
private val Dark = darkColorScheme(primary = Color(0xFFA2D1A8), secondary = Color(0xFFFFB4A7),
    background = Color(0xFF141D17), surface = Color(0xFF141D17), surfaceVariant = Color(0xFF29372B),
    primaryContainer = Color(0xFF304E35))

@Composable fun TouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
