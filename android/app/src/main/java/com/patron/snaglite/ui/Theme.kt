package com.patron.snaglite.ui

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
import com.patron.snaglite.download.SnagLitePrefs

private val LightColors = lightColorScheme(
    primary = Color(0xFF2962FF),
    onPrimary = Color.White,
    secondary = Color(0xFF00BFA5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color.Black,
    secondary = Color(0xFF1DE9B6),
)

enum class ThemeMode { System, Light, Dark }

fun themeModeFromPref(value: String): ThemeMode = when (value) {
    SnagLitePrefs.THEME_LIGHT -> ThemeMode.Light
    SnagLitePrefs.THEME_DARK -> ThemeMode.Dark
    else -> ThemeMode.System
}

fun ThemeMode.toPref(): String = when (this) {
    ThemeMode.Light -> SnagLitePrefs.THEME_LIGHT
    ThemeMode.Dark -> SnagLitePrefs.THEME_DARK
    ThemeMode.System -> SnagLitePrefs.THEME_SYSTEM
}

@Composable
fun SnagLiteTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    SnagLiteTheme(darkTheme = darkTheme, content = content)
}

@Composable
private fun SnagLiteTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
