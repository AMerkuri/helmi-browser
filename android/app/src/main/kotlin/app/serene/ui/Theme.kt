package app.serene.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.serene.ThemeMode

// Helmi's colours: verdigris and brass on chart paper (light) or lamp black (dark). The brand
// colours are used on every Android version, so Material You wallpaper colours are off.
private val Light = lightColorScheme(
    primary = Color(0xFF2F6B62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE6DF),
    onPrimaryContainer = Color(0xFF0E2A25),
    secondary = Color(0xFF8F6417),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DEB0),
    onSecondaryContainer = Color(0xFF2C1E00),
    tertiary = Color(0xFF8F6417),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F0E4),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFF4F0E4),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE7E1CF),
    onSurfaceVariant = Color(0xFF6B665A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F6EE),
    surfaceContainer = Color(0xFFEFEADC),
    surfaceContainerHigh = Color(0xFFE9E4D4),
    surfaceContainerHighest = Color(0xFFE3DDCB),
    outline = Color(0xFF8C8676),
    outlineVariant = Color(0xFFD2CBB6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FB5A6),
    onPrimary = Color(0xFF0E2A25),
    primaryContainer = Color(0xFF1F4A44),
    onPrimaryContainer = Color(0xFFCDE6DF),
    secondary = Color(0xFFD9A441),
    onSecondary = Color(0xFF2C1E00),
    secondaryContainer = Color(0xFF4A3712),
    onSecondaryContainer = Color(0xFFF3DEB0),
    tertiary = Color(0xFFD9A441),
    onTertiary = Color(0xFF2C1E00),
    background = Color(0xFF161917),
    onBackground = Color(0xFFEDE7D6),
    surface = Color(0xFF161917),
    onSurface = Color(0xFFEDE7D6),
    surfaceVariant = Color(0xFF2A302D),
    onSurfaceVariant = Color(0xFFA39C88),
    surfaceContainerLowest = Color(0xFF101211),
    surfaceContainerLow = Color(0xFF1A1E1C),
    surfaceContainer = Color(0xFF1D2220),
    surfaceContainerHigh = Color(0xFF242A27),
    surfaceContainerHighest = Color(0xFF2E3531),
    outline = Color(0xFF7D786A),
    outlineVariant = Color(0xFF3A3833),
)

// Private tabs, in the light theme and the dark: night at sea, deep blue-grey with the brass, so a
// private tab never looks like a normal one (Chrome's Incognito is dark grey the same way).
private val Private = darkColorScheme(
    primary = Color(0xFFD9A441),
    onPrimary = Color(0xFF2C1E00),
    primaryContainer = Color(0xFF4A3712),
    onPrimaryContainer = Color(0xFFF3DEB0),
    secondary = Color(0xFF9FB4D8),
    onSecondary = Color(0xFF13233D),
    secondaryContainer = Color(0xFF2E3B55),
    onSecondaryContainer = Color(0xFFD8E2F5),
    tertiary = Color(0xFF6FB5A6),
    onTertiary = Color(0xFF0E2A25),
    background = Color(0xFF1A1F2B),
    onBackground = Color(0xFFE4E7EE),
    surface = Color(0xFF1A1F2B),
    onSurface = Color(0xFFE4E7EE),
    surfaceVariant = Color(0xFF2E3546),
    onSurfaceVariant = Color(0xFFA7AFC0),
    surfaceContainerLowest = Color(0xFF141821),
    surfaceContainerLow = Color(0xFF1E2431),
    surfaceContainer = Color(0xFF222836),
    surfaceContainerHigh = Color(0xFF29303F),
    surfaceContainerHighest = Color(0xFF333A4A),
    outline = Color(0xFF7D8598),
    outlineVariant = Color(0xFF3A4152),
)

@Composable
fun SereneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/**
 * [isPrivate]: what is inside is a private tab's, in the private tabs' colours; else the theme as
 * it is. The same composition either way, so a tab turning private (the bar's cube going from a
 * normal tab to a private one) keeps what is remembered inside.
 */
@Composable
fun PrivateTheme(isPrivate: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isPrivate) Private else MaterialTheme.colorScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
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
