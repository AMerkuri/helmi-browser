package app.serene.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Reader view settings, kept apart from the app settings. Compose state, so the view restyles on change. */
class ReaderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private var themeState by mutableStateOf(prefs.getString("theme", null)?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } })
    private var textSizeState by mutableStateOf(prefs.getInt("text_size", 19))
    private var serifState by mutableStateOf(prefs.getBoolean("serif", true))
    private var justifyState by mutableStateOf(prefs.getBoolean("justify", true))
    private var tapToTurnState by mutableStateOf(prefs.getBoolean("tap_to_turn", true))

    /** null follows the app's theme: Paper when light, Night when dark. */
    var theme: ReaderTheme?
        get() = themeState
        set(v) { themeState = v; prefs.edit { putString("theme", v?.name) } }
    var textSize: Int
        get() = textSizeState
        set(v) { textSizeState = v.coerceIn(MIN_SIZE, MAX_SIZE); prefs.edit { putInt("text_size", textSizeState) } }
    var serif: Boolean
        get() = serifState
        set(v) { serifState = v; prefs.edit { putBoolean("serif", v) } }
    var justify: Boolean
        get() = justifyState
        set(v) { justifyState = v; prefs.edit { putBoolean("justify", v) } }
    /** Taps on the left and right edges turn the page, as on an e-reader. */
    var tapToTurn: Boolean
        get() = tapToTurnState
        set(v) { tapToTurnState = v; prefs.edit { putBoolean("tap_to_turn", v) } }

    fun style(appDark: Boolean) = ReaderStyle(
        theme = theme ?: if (appDark) ReaderTheme.NIGHT else ReaderTheme.PAPER,
        textSize = textSize,
        serif = serif,
        justify = justify,
    )

    companion object {
        const val MIN_SIZE = 14
        const val MAX_SIZE = 30
    }
}
