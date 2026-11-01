package app.serene

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.serene.engine.FilterLists

/** App settings in SharedPreferences, exposed as Compose state so screens update on change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("serene", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = Pref(key, prefs.getBoolean(key, default)) { putBoolean(key, it) }
    private fun string(key: String, default: String) = Pref(key, prefs.getString(key, default) ?: default) { putString(key, it) }
    private fun stringSet(key: String) = Pref(key, prefs.getStringSet(key, emptySet())!!.toSet()) { putStringSet(key, it) }

    private inner class Pref<T>(val key: String, initial: T, val write: android.content.SharedPreferences.Editor.(T) -> Unit) {
        var state by mutableStateOf(initial)
        fun set(v: T) { state = v; prefs.edit { write(v) } }
    }

    private val engineEnabledPref = bool("engine_enabled", true)
    var engineEnabled: Boolean get() = engineEnabledPref.state; set(v) = engineEnabledPref.set(v)

    private val modelEnabledPref = bool("model_enabled", true)
    /** Use the model for uncertain overlays once it is downloaded. */
    var modelEnabled: Boolean get() = modelEnabledPref.state; set(v) = modelEnabledPref.set(v)

    private val modelIdPref = string("model_id", ModelCatalog.default.id) // the NPU model on a phone that has one
    var modelId: String get() = modelIdPref.state; set(v) = modelIdPref.set(v)

    private val skipOnSaverPref = bool("skip_model_battery_saver", true)
    var skipModelOnBatterySaver: Boolean get() = skipOnSaverPref.state; set(v) = skipOnSaverPref.set(v)

    private val wifiOnlyPref = bool("wifi_only_downloads", true)
    var wifiOnlyDownloads: Boolean get() = wifiOnlyPref.state; set(v) = wifiOnlyPref.set(v)

    private val backForwardCachePref = bool("flag_back_forward_cache", true)
    /** Flag: WebView's back/forward cache, and swipe previews with it (off: a title card, and back reloads). */
    var backForwardCache: Boolean get() = backForwardCachePref.state; set(v) = backForwardCachePref.set(v)

    private val modelNotesPref = bool("flag_model_notes", false)
    /** Flag: a note at the bottom each time the model decides about an overlay, with Undo or Hide. */
    var showModelDecisions: Boolean get() = modelNotesPref.state; set(v) = modelNotesPref.set(v)

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

    // ⋮ › Dark theme: sites (eTLD+1) that differ from darkSites. A site switched back to what
    // darkSites says loses its entry, so it follows the setting again.
    private val darkSitesOnPref = stringSet("dark_sites_on")
    private val darkSitesOffPref = stringSet("dark_sites_off")
    /** Sites with their own dark theme choice: site to on. */
    val darkSiteChoices: Map<String, Boolean>
        get() = darkSitesOffPref.state.associateWith { false } + darkSitesOnPref.state.associateWith { true }

    /** Whether [site] is darkened in the dark theme: its own choice, else [darkSites]. */
    fun darkSite(site: String) = when (site) {
        in darkSitesOnPref.state -> true
        in darkSitesOffPref.state -> false
        else -> darkSites
    }

    fun setDarkSite(site: String, on: Boolean) {
        darkSitesOnPref.set(darkSitesOnPref.state - site + listOfNotNull(site.takeIf { on && !darkSites }))
        darkSitesOffPref.set(darkSitesOffPref.state - site + listOfNotNull(site.takeIf { !on && darkSites }))
    }

    /** Drops [site]'s own choice: it follows [darkSites]. */
    fun resetDarkSite(site: String) = setDarkSite(site, darkSites)

    private val dimPicturesPref = bool("dim_pictures", true)
    /** In the dark theme, dim the pictures of the sites it darkens ([darkSite]). */
    var dimPictures: Boolean get() = dimPicturesPref.state; set(v) = dimPicturesPref.set(v)

    private val dimStrengthPref = string("dim_pictures_strength", DimStrength.LIGHT.name)
    /** How much [dimPictures] dims. */
    var dimStrength: DimStrength
        get() = runCatching { DimStrength.valueOf(dimStrengthPref.state) }.getOrDefault(DimStrength.LIGHT)
        set(v) = dimStrengthPref.set(v.name)

    // Settings › Filter lists. Before the user changes anything, the catalog's defaults (so
    // lists added to the defaults later reach them too).
    private val listsEnabledPref = Pref("lists_enabled", prefs.getStringSet("lists_enabled", null)?.toSet() ?: FilterLists.DEFAULT_ENABLED) { putStringSet("lists_enabled", it) }
    /** Catalog entries ([FilterLists.CATALOG] ids) that are switched on. */
    var listsEnabled: Set<String> get() = listsEnabledPref.state; set(v) = listsEnabledPref.set(v)

    private val customListsPref = string("custom_lists", "")
    /** Filter lists the user added by URL, in the order added. */
    var customLists: List<String>
        get() = customListsPref.state.split('\n').filter { it.isNotBlank() }
        set(v) = customListsPref.set(v.joinToString("\n"))

    private val myFiltersPref = string("my_filters", "")
    /** The user's own rules, in uBO syntax. */
    var myFilters: String get() = myFiltersPref.state; set(v) = myFiltersPref.set(v)

    val listSelection get() = FilterLists.Selection(listsEnabled, customLists, myFilters)

    private val disabledSitesPref = stringSet("disabled_sites")
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

    /** Launches counted for the default browser question (DefaultBrowser.isNewLaunch), until it is asked. */
    var launches: Int
        get() = prefs.getInt("launches", 0)
        set(v) = prefs.edit { putInt("launches", v) }

    /** When Helmi last went off screen (onStop), in ms since the epoch; 0 before the first time. */
    var lastSeenAt: Long
        get() = prefs.getLong("last_seen_at", 0L)
        set(v) = prefs.edit { putLong("last_seen_at", v) }

    /** Asked once to make Helmi the default browser (or it already was): never asked again. */
    var askedDefaultBrowser: Boolean
        get() = prefs.getBoolean("asked_default_browser", false)
        set(v) = prefs.edit { putBoolean("asked_default_browser", v) }

    /**
     * Android permissions Helmi has asked for for a site (camera, microphone, location). Once asked,
     * no rationale means Android blocks it for good and shows no dialog (MainActivity).
     */
    var askedAndroidPermissions: Set<String>
        get() = prefs.getStringSet("asked_android_permissions", emptySet())!!.toSet()
        set(v) = prefs.edit { putStringSet("asked_android_permissions", v) }

    var modelDownloadId: Long
        get() = prefs.getLong("model_download_id", -1)
        set(v) = prefs.edit { putLong("model_download_id", v) }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Settings › Theme › "Dim pictures": how bright a picture stays, in percent (CSS brightness()). */
enum class DimStrength(val label: String, val percent: Int) {
    LIGHT("Light", 90),
    MEDIUM("Medium", 80),
    STRONG("Strong", 70),
}

enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
}
