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

    private val searchPref = string("search_engine", SearchEngine.DUCKDUCKGO.name)
    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(searchPref.state) }.getOrDefault(SearchEngine.DUCKDUCKGO)
        set(v) = searchPref.set(v.name)

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

    var modelDownloadId: Long
        get() = prefs.getLong("model_download_id", -1)
        set(v) = prefs.edit { putLong("model_download_id", v) }
}

enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
}
