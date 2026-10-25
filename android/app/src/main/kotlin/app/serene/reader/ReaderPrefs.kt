package app.serene.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Reader view settings, kept apart from the app settings. Compose state, so the view restyles on change. */
class ReaderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** null follows the app's theme: Paper when light, Night when dark. */
    var theme by mutableStateOf(prefs.getString("theme", null)?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } })
        private set
    var textSize by mutableStateOf(prefs.getInt("text_size", 19))
        private set
    var serif by mutableStateOf(prefs.getBoolean("serif", true))
        private set
    var justify by mutableStateOf(prefs.getBoolean("justify", true))
        private set
    /** Taps on the left and right edges turn the page, as on an e-reader. */
    var tapToTurn by mutableStateOf(prefs.getBoolean("tap_to_turn", true))
        private set

    fun setTheme(v: ReaderTheme?) { theme = v; prefs.edit { putString("theme", v?.name) } }
    fun setTextSize(v: Int) { textSize = v.coerceIn(MIN_SIZE, MAX_SIZE); prefs.edit { putInt("text_size", textSize) } }
    fun setSerif(v: Boolean) { serif = v; prefs.edit { putBoolean("serif", v) } }
    fun setJustify(v: Boolean) { justify = v; prefs.edit { putBoolean("justify", v) } }
    fun setTapToTurn(v: Boolean) { tapToTurn = v; prefs.edit { putBoolean("tap_to_turn", v) } }

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
