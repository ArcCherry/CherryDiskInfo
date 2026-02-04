package com.example.cherrydiskinfo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CherryRed,
    onPrimary = Color.White,
    primaryContainer = CherryRedDark,
    onPrimaryContainer = Color.White,
    secondary = Teal200,
    onSecondary = Color.Black,
    secondaryContainer = Teal700,
    onSecondaryContainer = Color.White,
    tertiary = CherryRedLight,
    onTertiary = Color.Black,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondaryDark,
    error = HealthBad,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = CherryRed,
    onPrimary = Color.White,
    primaryContainer = CherryRedLight,
    onPrimaryContainer = CherryRedDark,
    secondary = Teal700,
    onSecondary = Color.White,
    secondaryContainer = Teal200,
    onSecondaryContainer = Color.Black,
    tertiary = CherryRedLight,
    onTertiary = Color.Black,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextSecondaryLight,
    error = HealthBad,
    onError = Color.White
)

@Composable
fun CherryDiskInfoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
