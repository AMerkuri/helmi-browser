package app.serene

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The address bar's suggestions, typed with key events: the start of a site visited twice completes
 * in the field and Go opens it (an http site stays http), and a word of a page's title lists the
 * page under the bar, where a tap opens it.
 */
@RunWith(AndroidJUnit4::class)
class AddressSuggestionsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test
    fun completesAndSuggestsFromTheHistory() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val html = "<!doctype html><title>Harbour page</title><p>ferries".toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
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
                val row = find("Harbour page")!!
                // The row is the clickable node; its text is merged into it.
                var node: AccessibilityNodeInfo? = row
                while (node != null && !node.isClickable) node = node.parent
                assertTrue("no clickable suggestion", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                waitFor("the tap opens the page") { url(scenario) == page }
            }
        } finally {
            app.settings.searchSuggestions = true
            server.close()
        }
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
     * The node showing [text]. A walk of the tree: Compose doesn't answer
     * findAccessibilityNodeInfosByText.
     */
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 40) return null
            if (n.text?.toString() == text) return n
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
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots().forEach { walk(it, 0) }
        return out
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            SystemClock.sleep(200)
        }
        assertTrue("timed out: $what; on screen: ${texts()}", check())
    }
}
