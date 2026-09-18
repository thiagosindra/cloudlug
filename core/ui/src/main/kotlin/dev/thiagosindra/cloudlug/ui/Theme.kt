package dev.thiagosindra.cloudlug.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * CloudLug's Material 3 theme.
 *
 * No dynamic colour: the transfer screens use colour to mean something — a
 * failed item, a conflict, a waiting state — and a wallpaper-derived palette
 * cannot be relied on to keep those distinguishable (spec §24.3, §24.4).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00658E),
    onPrimary = Color.White,
    secondary = Color(0xFF4E616D),
    error = Color(0xFFBA1A1A),
    tertiary = Color(0xFF625B71),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82CFFF),
    onPrimary = Color(0xFF00344C),
    secondary = Color(0xFFB6C9D8),
    error = Color(0xFFFFB4AB),
    tertiary = Color(0xFFCCC2DC),
)

@Composable
fun CloudLugTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
