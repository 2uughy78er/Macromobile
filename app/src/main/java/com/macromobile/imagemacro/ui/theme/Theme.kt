package com.macromobile.imagemacro.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5DB0),
    onPrimary = Color.White,
    secondary = Color(0xFF3F6C86),
    tertiary = Color(0xFFB4552A),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC3FF),
    onPrimary = Color(0xFF00325C),
    secondary = Color(0xFFA7CCE4),
    tertiary = Color(0xFFFFB68C),
    error = Color(0xFFF2B8B5),
)

/** 매크로가 잘 됐다는 표시에 쓰는 색(성공/발견). */
val SuccessColor = Color(0xFF2E7D32)
val SuccessColorDark = Color(0xFF7CD992)
val WarnColor = Color(0xFFB26A00)

@Composable
fun ImageMacroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** 다크/라이트에 맞는 성공 색. */
@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) SuccessColorDark else SuccessColor
