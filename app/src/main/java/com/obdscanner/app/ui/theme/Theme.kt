package com.obdscanner.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obdscanner.app.Accent
import com.obdscanner.app.AppSettings
import com.obdscanner.app.ThemeMode

val Danger = Color(0xFFE53935)
val Ok = Color(0xFF43A047)
val Caution = Color(0xFFFB8C00)

private fun darkScheme(accent: Color): ColorScheme {
    val background = Color(0xFF0E141B)
    return darkColorScheme(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.4f) Color(0xFF14110A) else Color.White,
        primaryContainer = lerp(background, accent, 0.25f),
        onPrimaryContainer = Color.White,
        secondary = lerp(accent, Color.White, 0.35f),
        background = background,
        surface = background,
        surfaceVariant = Color(0xFF1A2430),
        surfaceContainer = Color(0xFF151D26),
        surfaceContainerHigh = Color(0xFF1A2430),
        onSurfaceVariant = Color(0xFFA9B4C0),
        outlineVariant = Color(0xFF2A3644),
    )
}

private fun lightScheme(accent: Color): ColorScheme {
    // Darken the accent so text and icons in it stay readable on white.
    val primary = lerp(accent, Color.Black, 0.3f)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = lerp(Color.White, accent, 0.22f),
        onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
        secondary = lerp(accent, Color.Black, 0.45f),
        background = Color(0xFFF6F7F9),
        surface = Color(0xFFF6F7F9),
        surfaceVariant = Color(0xFFE9ECF0),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFF0F2F5),
        outlineVariant = Color(0xFFD5DAE0),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun ObdScannerTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = Color(settings.accent.argb)
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkScheme(accent)
        else -> lightScheme(accent)
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

/** Preview swatch for the accent picker. */
fun Accent.color(): Color = Color(argb)
