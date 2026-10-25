package app.serene.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import app.serene.ThemeMode

private val Sage = Color(0xFF4F7A6A)

@Composable
fun SereneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9CCBB8))
        else -> lightColorScheme(primary = Sage)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * The Theme setting as the app's night mode. From API 31 the system keeps it for the app and
 * changes the configuration in place (uiMode is handled, see the manifest); before that,
 * MainActivity overrides its own configuration with [nightUiMode] and is recreated.
 */
fun applyNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < 31) return
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
        when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        },
    )
}

/** The night bits of uiMode for [mode], or null to follow the system. */
fun nightUiMode(mode: ThemeMode): Int? = when (mode) {
    ThemeMode.SYSTEM -> null
    ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
    ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
}
