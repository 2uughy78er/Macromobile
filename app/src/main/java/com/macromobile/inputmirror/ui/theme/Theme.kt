package com.macromobile.inputmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2A5DB0),
    secondary = Color(0xFF2E7D5B),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FC3FF),
    secondary = Color(0xFF7CD9B0),
    error = Color(0xFFF2B8B5),
)

val OkColorLight = Color(0xFF2E7D32)
val OkColorDark = Color(0xFF7CD992)

@Composable
fun InputMirrorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}

@Composable
fun okColor(): Color = if (isSystemInDarkTheme()) OkColorDark else OkColorLight
