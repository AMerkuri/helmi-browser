package app.serene.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The status and navigation bar icons. They follow the app's theme, unless a screen drawn over the
 * browser has a colour of its own (the tab switcher's black, the reader's page): then the newest
 * such screen decides while it is shown.
 *
 * Worked out again from what is on screen every time, never saved and put back. The screens used
 * to save the icons they found and put them back when they went, so a theme change while one was
 * open, or the reader going while the tab switcher was over it, left the old theme's icons: light
 * icons on the light theme until the next theme change.
 */
class SystemBars(private val activity: ComponentActivity) {
    /** One screen's ask: icons for a dark background, a light one, or null for none (the app's). */
    class Ask {
        var dark by mutableStateOf<Boolean?>(null)
    }

    private val asks = mutableStateListOf<Ask>()

    fun add(ask: Ask) { asks += ask }
    fun remove(ask: Ask) { asks -= ask }

    /** What the screens on top ask for (snapshot state: read it in a snapshotFlow to follow it). */
    val asked: Boolean? get() = newestAsk(asks.map { it.dark })

    // enableEdgeToEdge() runs its setup again on every configuration change (a hidden view in the
    // window), after the activity's onConfigurationChanged, so setting the icons there was undone:
    // the asks have to be in the styles it works out the icons from.
    private fun dark(resources: Resources) = newestAsk(asks.map { it.dark })
        ?: (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private val status = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, ::dark)
    // The scrims behind 3-button navigation are enableEdgeToEdge's defaults.
    private val navigation = SystemBarStyle.auto(Color.argb(0xe6, 0xFF, 0xFF, 0xFF), Color.argb(0x80, 0x1b, 0x1b, 0x1b), ::dark)

    /** Edge to edge, with the icons for what is on screen now and the activity's theme. */
    fun apply() = activity.enableEdgeToEdge(status, navigation)
}

/** The newest screen's ask, oldest first in [asks]; null when none asks (the app's theme decides). */
internal fun newestAsk(asks: List<Boolean?>): Boolean? = asks.lastOrNull { it != null }

val LocalSystemBars = staticCompositionLocalOf<SystemBars?> { null }

/** While this is composed, the bar icons are for a [dark] background (null: the app's theme decides). */
@Composable
fun SystemBarIcons(dark: Boolean?) {
    val bars = LocalSystemBars.current ?: return
    val ask = remember(bars) { SystemBars.Ask() }
    DisposableEffect(bars, ask) {
        bars.add(ask)
        onDispose { bars.remove(ask) }
    }
    SideEffect { ask.dark = dark }
}
