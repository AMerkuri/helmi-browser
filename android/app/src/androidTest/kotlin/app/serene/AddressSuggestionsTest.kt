package app.serene

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.ui.SWITCH_TO_TAB
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The address bar's suggestions, typed with key events: the start of a site visited twice completes
 * in the field and Go opens it (an http site stays http), and a word of a page's title lists the
 * page under the bar, where a tap opens it and a long press removes it from the history. A page
 * open in another tab is offered as that tab.
 */
@RunWith(AndroidJUnit4::class)
class AddressSuggestionsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    /** A page server: /harbour… is titled "Harbour page", the rest "Front page". */
    private fun serve(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val path = r.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    while (!r.readLine().isNullOrEmpty()) Unit
                    // Only the harbour page is titled so: the site's front page (opened by the
                    // completion) must not be a second "Harbour page" row.
                    val title = if (path.startsWith("/harbour")) "Harbour page" else "Front page"
                    val html = "<!doctype html><title>$title</title><p>ferries".toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        return server
    }

    @Test
    fun completesAndSuggestsFromTheHistory() {
        val server = serve()
        val app = ApplicationProvider.getApplicationContext<SereneApp>()
        app.settings.savedTabs = emptyList()
        app.settings.searchSuggestions = false // the emulator has no search engine to ask
        val site = "127.0.0.1:${server.localPort}"
        val page = "http://$site/harbour"
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                inst.runOnMainSync {
                    app.history.clear()
                    app.history.visit(page, "Harbour page")
                    app.history.visit(page, "Harbour page")
                }
                // The blank tab the test starts on has the address bar focused.
                SystemClock.sleep(2000)
                type("127")
                SystemClock.sleep(1000)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                // Without the completion, Go would search for "127".
                waitFor("Go opens something (did the keys reach the address bar?)") { url(scenario).isNotEmpty() }
                assertTrue("Go should open the completed site over http, opened: ${url(scenario)}", url(scenario).startsWith("http://$site"))

                scenario.onActivity { it.tabs.open() }
                SystemClock.sleep(2000)
                type("harbour")
                waitFor("the page is suggested") { find("Harbour page") != null }
                assertTrue("no clickable suggestion", act(find("Harbour page")!!, AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("the tap opens the page") { url(scenario) == page }

                // A long press offers to remove it from the history, as in Chrome; OK does.
                scenario.onActivity { it.tabs.open() }
                SystemClock.sleep(2000)
                type("harbour")
                waitFor("the page is suggested again") { find("Harbour page") != null }
                assertTrue("no long press on the suggestion", act(find("Harbour page")!!, AccessibilityNodeInfo.ACTION_LONG_CLICK))
                waitFor("the remove dialog") { find("Remove suggestion from history?") != null }
                assertTrue("no OK button", act(find("OK")!!, AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("the page leaves the history") {
                    var left = false
                    inst.runOnMainSync { left = app.history.entries.none { it.url == page } }
                    left
                }
                waitFor("the suggestion goes", detail = {
                    var visits = emptyList<String>()
                    inst.runOnMainSync { visits = app.history.entries.map { it.url } }
                    "history: $visits"
                }) { find("Harbour page") == null }
            }
        } finally {
            app.settings.searchSuggestions = true
            server.close()
        }
    }

    /**
     * A page open in another tab is a "Switch to tab" row: a tap shows that tab (the page isn't
     * loaded again), and the blank tab the address was typed in closes.
     */
    @Test
    fun switchesToATabWithThePage() {
        val server = serve()
        val app = ApplicationProvider.getApplicationContext<SereneApp>()
        app.settings.savedTabs = emptyList()
        app.settings.searchSuggestions = false
        val page = "http://127.0.0.1:${server.localPort}/harbour"
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                inst.runOnMainSync { app.history.clear() }
                var pageTab = 0L
                scenario.onActivity { pageTab = it.tabs.current!!.id; it.tabs.current!!.load(page) }
                waitFor("the page loads with its title") {
                    var title = ""
                    scenario.onActivity { title = it.tabs.current?.title.orEmpty() }
                    title == "Harbour page"
                }
                scenario.onActivity { it.tabs.open() }
                SystemClock.sleep(2000)
                type("harbour")
                waitFor("the tab is suggested") { findStarting(SWITCH_TO_TAB) != null }
                assertTrue("no clickable tab row", act(findStarting(SWITCH_TO_TAB)!!, AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("the tap shows the page's tab and closes the blank one", detail = {
                    var ids = emptyList<Long>()
                    scenario.onActivity { a -> ids = a.tabs.tabs.map { it.id } }
                    "tabs: $ids"
                }) {
                    var on = false
                    scenario.onActivity { on = it.tabs.current?.id == pageTab && it.tabs.tabs.size == 1 }
                    on
                }
                assertTrue("the tab's page should stay", url(scenario) == page)
            }
        } finally {
            app.settings.searchSuggestions = true
            server.close()
        }
    }

    /** [action] on the clickable node that holds [n] (a row's text is a child of the row). */
    private fun act(n: AccessibilityNodeInfo, action: Int): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null && !node.isClickable && !node.isLongClickable) node = node.parent
        return node?.performAction(action) == true
    }

    private fun url(scenario: ActivityScenario<MainActivity>): String {
        var u = ""
        scenario.onActivity { u = it.tabs.current?.url.orEmpty() }
        return u
    }

    /** One key at a time, as a keyboard types: each letter goes through the field's completion. */
    private fun type(text: String) {
        for (c in text) {
            inst.sendStringSync(c.toString())
            inst.waitForIdleSync()
        }
    }

    /** The app's windows (the keyboard's window may be the active one). */
    private fun roots(): List<AccessibilityNodeInfo> =
        inst.uiAutomation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(inst.uiAutomation.rootInActiveWindow) }

    /**
     * The node showing [text], on screen. A walk of the tree: Compose doesn't answer
     * findAccessibilityNodeInfosByText. A list keeps rows it took away for reuse, off screen.
     */
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 40) return null
            if (n.text?.toString() == text && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return roots().firstNotNullOfOrNull { walk(it, 0) }
    }

    /** The node on screen whose text starts with [prefix]. */
    private fun findStarting(prefix: String): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 40) return null
            if (n.text?.toString()?.startsWith(prefix) == true && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return roots().firstNotNullOfOrNull { walk(it, 0) }
    }

    /** The texts on screen, for a failure message. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += if (n.isVisibleToUser) it else "($it, hidden)" }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots().forEach { walk(it, 0) }
        return out
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, detail: () -> String = { "" }, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            SystemClock.sleep(200)
        }
        assertTrue("timed out: $what; on screen: ${texts()} ${detail()}", check())
    }
}
