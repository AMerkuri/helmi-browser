package app.serene.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import app.serene.SereneApp

/** Open tabs. Lives as long as the activity (config changes are handled in place, see the manifest). */
class TabManager(private val context: Context, private val app: SereneApp) {
    val tabs = mutableStateListOf<Tab>()
    var currentIndex by mutableIntStateOf(0)
        private set
    private var nextId = 1L

    val current: Tab? get() = tabs.getOrNull(currentIndex)

    fun open(url: String? = null, select: Boolean = true): Tab {
        val tab = Tab(context, app, nextId++)
        tabs += tab
        if (select) {
            leave()
            currentIndex = tabs.lastIndex
        }
        if (!url.isNullOrBlank()) tab.load(url)
        return tab
    }

    fun select(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0 || i == currentIndex) return
        leave()
        currentIndex = i
    }

    // The tab being left, as it was last on screen, for its card in the tab switcher.
    private fun leave() { current?.let { TabPreviews.capture(it) } }

    fun close(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        TabPreviews.remove(tab)
        tab.destroy()
        if (tabs.isEmpty()) open()
        currentIndex = (if (i < currentIndex) currentIndex - 1 else currentIndex.coerceAtMost(tabs.lastIndex)).coerceAtLeast(0)
    }

    fun save() {
        // A tab opened for a download would download the file again on restore.
        app.settings.savedTabs = tabs.filterNot { it.downloadPage }.map { it.url }.filter { it.startsWith("http") }
        app.settings.savedTabIndex = currentIndex
    }

    fun restore() {
        val urls = app.settings.savedTabs
        urls.forEach { open(it, select = false) }
        if (tabs.isEmpty()) open()
        currentIndex = app.settings.savedTabIndex.coerceIn(0, tabs.lastIndex)
    }
}
