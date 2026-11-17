package com.obdscanner.app.ui.theme

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

val Amber = Color(0xFFFFB300)
val Danger = Color(0xFFE53935)
val Ok = Color(0xFF43A047)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1300),
    secondary = Color(0xFF90CAF9),
    background = Color(0xFF0F1720),
    surface = Color(0xFF0F1720),
    surfaceVariant = Color(0xFF1B2A3A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB26A00),
    secondary = Color(0xFF1565C0),
)

@Composable
fun ObdScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
