package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MinimalPrimaryDark,
    primaryContainer = MinimalContainerDark,
    onPrimaryContainer = MinimalOnContainerDark,
    secondary = GreenInDark,
    tertiary = RedOutDark,
    background = MinimalBgDark,
    surface = MinimalSurfaceDark,
    surfaceVariant = MinimalCardDark,
    onPrimary = MinimalBgDark,
    onSecondary = MinimalBgDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = MinimalPrimary,
    primaryContainer = MinimalContainerLight,
    onPrimaryContainer = MinimalOnContainerLight,
    secondary = GreenIn,
    tertiary = RedOut,
    background = MinimalBgLight,
    surface = MinimalSurfaceLight,
    surfaceVariant = MinimalCardLight,
    onPrimary = MinimalBgLight,
    onSecondary = MinimalBgLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Enforce our high-contrast minimalist colors
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
