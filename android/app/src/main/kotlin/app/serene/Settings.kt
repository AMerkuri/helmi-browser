package app.serene

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** App settings in SharedPreferences, exposed as Compose state so screens update on change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("serene", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = Pref(key, prefs.getBoolean(key, default)) { putBoolean(key, it) }
    private fun string(key: String, default: String) = Pref(key, prefs.getString(key, default) ?: default) { putString(key, it) }

    private inner class Pref<T>(val key: String, initial: T, val write: android.content.SharedPreferences.Editor.(T) -> Unit) {
        var state by mutableStateOf(initial)
        fun set(v: T) { state = v; prefs.edit { write(v) } }
    }

    private val engineEnabledPref = bool("engine_enabled", true)
    var engineEnabled: Boolean get() = engineEnabledPref.state; set(v) = engineEnabledPref.set(v)

    private val modelEnabledPref = bool("model_enabled", true)
    /** Use the model for uncertain overlays once it is downloaded. */
    var modelEnabled: Boolean get() = modelEnabledPref.state; set(v) = modelEnabledPref.set(v)

    private val modelIdPref = string("model_id", ModelCatalog.DEFAULT.id)
    var modelId: String get() = modelIdPref.state; set(v) = modelIdPref.set(v)

    private val skipOnSaverPref = bool("skip_model_battery_saver", true)
    var skipModelOnBatterySaver: Boolean get() = skipOnSaverPref.state; set(v) = skipOnSaverPref.set(v)

    private val wifiOnlyPref = bool("wifi_only_downloads", true)
    var wifiOnlyDownloads: Boolean get() = wifiOnlyPref.state; set(v) = wifiOnlyPref.set(v)

    private val backForwardCachePref = bool("flag_back_forward_cache", true)
    /** Flag: WebView's back/forward cache, and swipe previews with it (off: a title card, and back reloads). */
    var backForwardCache: Boolean get() = backForwardCachePref.state; set(v) = backForwardCachePref.set(v)

    private val searchPref = string("search_engine", SearchEngine.DUCKDUCKGO.name)
    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(searchPref.state) }.getOrDefault(SearchEngine.DUCKDUCKGO)
        set(v) = searchPref.set(v.name)

    private val themePref = string("theme", ThemeMode.SYSTEM.name)
    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(themePref.state) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = themePref.set(v.name)

    private val darkSitesPref = bool("dark_sites", true)
    /** In the dark theme, darken sites that have no dark theme of their own. */
    var darkSites: Boolean get() = darkSitesPref.state; set(v) = darkSitesPref.set(v)

    private val disabledSitesPref = Pref("disabled_sites", prefs.getStringSet("disabled_sites", emptySet())!!.toSet()) { putStringSet("disabled_sites", it) }
    /** Sites (eTLD+1) where the user switched Serene off. */
    val disabledSites: Set<String> get() = disabledSitesPref.state

    fun setSiteEnabled(site: String, enabled: Boolean) =
        disabledSitesPref.set(if (enabled) disabledSites - site else disabledSites + site)

    fun isSiteEnabled(site: String) = engineEnabled && site !in disabledSites

    /** Open tabs, restored on the next launch. */
    var savedTabs: List<String>
        get() = prefs.getString("tabs", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit { putString("tabs", v.joinToString("\n")) }
    var savedTabIndex: Int
        get() = prefs.getInt("tab_index", 0)
        set(v) = prefs.edit { putInt("tab_index", v) }

    /** Asked for the notification permission once (Android 13+), at the first download. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }

    var modelDownloadId: Long
        get() = prefs.getLong("model_download_id", -1)
        set(v) = prefs.edit { putLong("model_download_id", v) }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
}
