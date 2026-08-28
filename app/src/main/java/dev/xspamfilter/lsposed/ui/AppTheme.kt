package dev.xspamfilter.lsposed.ui

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
    primary = Color(0xFF4F52C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF090863),
    secondary = Color(0xFF5D5D72),
    secondaryContainer = Color(0xFFE3E1F9),
    tertiary = Color(0xFF78536A),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFF0EDF5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    onPrimary = Color(0xFF202093),
    primaryContainer = Color(0xFF383AAF),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC7C5DD),
    secondaryContainer = Color(0xFF454559),
    tertiary = Color(0xFFE8B9D1),
    surface = Color(0xFF131318),
    surfaceContainer = Color(0xFF201F25),
)

@Composable
fun XSpamFilterTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
