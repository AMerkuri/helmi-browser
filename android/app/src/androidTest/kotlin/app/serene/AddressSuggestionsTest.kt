package app.serene

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
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                inst.runOnMainSync {
                    app.history.clear()
                    app.history.visit(page, "Harbour page")
                    app.history.visit(page, "Harbour page")
                }
                // The blank tab the test starts on has the address bar focused.
                SystemClock.sleep(2000)
                inst.sendStringSync("127")
                waitFor("the site completed in the field") { find(site) != null }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                waitFor("Go opens the completed site, over http") { url(scenario).startsWith("http://$site") }

                scenario.onActivity { it.tabs.open() }
                SystemClock.sleep(2000)
                inst.sendStringSync("harbour")
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

    private fun find(text: String): AccessibilityNodeInfo? =
        inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            SystemClock.sleep(200)
        }
        assertTrue("timed out: $what", check())
    }
}
