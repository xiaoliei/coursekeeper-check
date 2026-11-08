package com.coursekeeper.a.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KeeperBlue = Color(0xFF315EFB)
val KeeperBackground = Color(0xFFF5F7FC)
val KeeperInk = Color(0xFF17233D)
val KeeperMuted = Color(0xFF687790)
@Composable fun CourseKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = KeeperBlue, onPrimary = Color.White, secondary = Color(0xFF21856C),
        background = KeeperBackground, onBackground = KeeperInk,
        surface = Color.White, onSurface = KeeperInk, surfaceVariant = Color(0xFFEAF0FF),
        onSurfaceVariant = KeeperMuted, outlineVariant = Color(0xFFE5EBF5),
    ), content = content)
}
