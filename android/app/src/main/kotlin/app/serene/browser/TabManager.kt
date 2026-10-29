package app.serene.browser

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.SereneApp
import java.io.File

/** Open tabs. Lives as long as the activity (config changes are handled in place, see the manifest). */
class TabManager(private val context: Context, private val app: SereneApp) {
    val tabs = mutableStateListOf<Tab>()
    var currentIndex by mutableIntStateOf(0)
        private set
    private var nextId = 1L

    val current: Tab? get() = tabs.getOrNull(currentIndex)

    fun open(url: String? = null, select: Boolean = true): Tab {
        val tab = Tab(context, app, nextId++).also { it.manager = this }
        tabs += tab
        if (select) {
            leave()
            currentIndex = tabs.lastIndex
        }
        if (!url.isNullOrBlank()) tab.load(url)
        return tab
    }

    /**
     * A page in [from] opened a window (window.open, a target=_blank link): a new tab right after
     * it, on screen, with [from] as its opener. The page goes in from WebView (Tab.onCreateWindow).
     */
    fun openWindow(from: Tab): Tab {
        val tab = Tab(context, app, nextId++, openedByPage = true).also { it.manager = this; it.opener = from }
        val at = tabs.indexOf(from).let { if (it < 0) tabs.size else it + 1 }
        leave()
        tabs.add(at, tab)
        currentIndex = at
        return tab
    }

    /** The tab that opened [tab], if it is still open. */
    fun openerOf(tab: Tab): Tab? = tab.opener?.takeIf { it in tabs }

    /**
     * [tab]'s page closed its window (a sign-in popup that has answered), or back left its first
     * page: it closes for good, and if it was on screen its opener comes back.
     */
    fun closeWindow(tab: Tab) {
        if (tab !in tabs) return
        val opener = openerOf(tab)
        if (opener != null && tab === current) select(opener)
        close(tab, undo = false)
    }

    fun select(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0 || i == currentIndex) return
        leave()
        currentIndex = i
    }

    /**
     * Gives the WebViews of tabs not on screen the page's size, so their pages are laid out for it
     * before they are shown: a WebView never on screen (tabs restored after a restart) is 0 x 0,
     * and laying its page out the first time it was shown stuttered the tab switcher's zoom.
     */
    fun sizeHidden(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        for (t in tabs) {
            val v = t.webView
            if (v.parent != null || (v.width == width && v.height == height)) continue
            v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, width, height)
        }
    }

    // The tab being left, as it was last on screen, for its card in the tab switcher.
    private fun leave() { current?.let { TabPreviews.capture(it) } }

    /** A closed tab that Undo can still bring back: [index] is where it was, [standIn] the blank tab opened for it if it was the last. */
    class Closed(val tab: Tab, val index: Int, val wasCurrent: Boolean, val standIn: Tab?)

    /**
     * The tab closed last, while its "Closed …" bar is up. It is kept whole, WebView and all, so
     * Undo brings it back with its history, scroll position and form fields; only one at a time.
     */
    var closed by mutableStateOf<Closed?>(null)
        private set

    /** Told when Undo has put a closed tab back, so the tab switcher can drop its card back in. */
    var onReopen: ((Tab) -> Unit)? = null

    /** Closes [tab]; with [undo], it stays [closed] until [reopen] or [forget]. */
    fun close(tab: Tab, undo: Boolean = true) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        val wasCurrent = i == currentIndex
        tabs.removeAt(i)
        closed?.let { forget(it) }
        val standIn = if (tabs.isEmpty()) open() else null
        currentIndex = (if (i < currentIndex) currentIndex - 1 else currentIndex.coerceAtMost(tabs.lastIndex)).coerceAtLeast(0)
        if (undo) {
            tab.pause()
            closed = Closed(tab, i, wasCurrent, standIn)
        } else {
            gone(tab)
        }
    }

    private fun gone(tab: Tab) {
        TabPreviews.remove(tab)
        tab.destroy()
        for (t in tabs) if (t.opener === tab) t.opener = null
        tab.opener = null
    }

    /** Undo: puts the [closed] tab back where it was, and back on screen if it was. */
    fun reopen(c: Closed) {
        if (closed !== c) return
        closed = null
        // The blank tab opened when the last one closed goes again, unless it was used.
        val standIn = c.standIn?.takeIf { tabs.size == 1 && tabs[0] === it && it.url.isEmpty() }
        if (standIn != null) {
            tabs.clear()
            TabPreviews.remove(standIn)
            standIn.destroy()
        }
        val at = c.index.coerceIn(0, tabs.size)
        if (c.wasCurrent && standIn == null) leave()
        tabs.add(at, c.tab)
        c.tab.resume()
        if (c.wasCurrent || standIn != null) currentIndex = at
        else if (at <= currentIndex) currentIndex++
        onReopen?.invoke(c.tab)
    }

    /** The [closed] tab's bar went away: it is gone for good. */
    fun forget(c: Closed? = closed) {
        if (c == null || closed !== c) return
        closed = null
        gone(c.tab)
    }

    // Each saved tab's back/forward list, beside the URLs in the settings.
    private val historyFile get() = File(context.filesDir, "tab-history.json")

    fun save() {
        // A tab opened for a download would download the file again on restore.
        val saved = tabs.filterNot { it.downloadPage }.filter { it.url.startsWith("http") }
        app.settings.savedTabs = saved.map { it.url }
        // Where the tab on screen is among those saved (or the next one, if it isn't saved).
        app.settings.savedTabIndex = saved.count { tabs.indexOf(it) < currentIndex }
        TabHistory.save(historyFile, saved.map { TabHistory.Saved.of(it.url, it.history()) })
    }

    /** The tabs open when the app last went away, each with its history (back still goes back after a restart). */
    fun restore() {
        val urls = app.settings.savedTabs
        val history = TabHistory.forTabs(urls, TabHistory.load(historyFile))
        urls.forEachIndexed { i, u -> open(select = false).restore(u, history[i]?.bytes()) }
        if (tabs.isEmpty()) open()
        currentIndex = app.settings.savedTabIndex.coerceIn(0, tabs.lastIndex)
    }
}
