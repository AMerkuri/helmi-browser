package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.FindInPage
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * ⋮ › Find in page on the system WebView: the match count as the text is typed, next and
 * previous going round, no matches (a buzz when typing, not when deleting), and an emptied field
 * clearing it.
 */
@RunWith(AndroidJUnit4::class)
class FindInPageTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private val page = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width"><title>ferry</title></head><body>
        <p>The harbour ferry left at nine.</p>
        <p style="margin-top:150vh">A second Ferry came at noon.</p>
        <p style="margin-top:150vh">The last ferry of the day was full.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun countsAndStepsThroughMatches() {
        // A tab's own WebView, on screen: find needs a laid-out page.
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val html = page.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        ApplicationProvider.getApplicationContext<SereneApp>().settings.savedTabs = emptyList()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var tab: Tab? = null
                scenario.onActivity { a -> tab = a.tabs.current!!.apply { load("http://127.0.0.1:${server.localPort}/ferry") } }
                waitFor("the page loads", 20_000) { var done = false; inst.runOnMainSync { done = tab?.title == "ferry" && tab?.progress == 100 }; done }

                lateinit var find: FindInPage
                var buzzes = 0
                inst.runOnMainSync { find = FindInPage(tab!!.webView, onNoMatch = { buzzes++ }) }
                fun buzzed(): Int { var n = 0; inst.runOnMainSync { n = buzzes }; return n }
                fun count(): String { var c = ""; inst.runOnMainSync { c = find.count }; return c }

                // Case-insensitive, as in Chrome.
                inst.runOnMainSync { find.search("ferry") }
                waitFor("3 matches") { count() == "1/3" }
                inst.runOnMainSync { find.next(true) }
                waitFor("the second match") { count() == "2/3" }
                inst.runOnMainSync { find.next(true) }
                waitFor("the third match") { count() == "3/3" }
                inst.runOnMainSync { find.next(true) }
                waitFor("round to the first") { count() == "1/3" }
                inst.runOnMainSync { find.next(false) }
                waitFor("back round to the last") { count() == "3/3" }

                assertEquals("matches found: no buzz", 0, buzzed())

                // Typing on into nothing found buzzes, as in Chrome; deleting back does not.
                inst.runOnMainSync { find.search("ferryb") }
                waitFor("no match") { count() == "0/0" }
                waitFor("a buzz for no match") { buzzed() == 1 }
                // Each letter typed into nothing found buzzes again.
                inst.runOnMainSync { find.search("ferryboat") }
                waitFor("a buzz for the next letter") { buzzed() == 2 }
                inst.runOnMainSync { find.search("ferryboa") }
                SystemClock.sleep(1500)
                assertEquals("deleting is not a miss", 2, buzzed())
                inst.runOnMainSync { find.search("ferry") }
                waitFor("matches again") { count() == "1/3" }
                assertEquals("more than one buzz per search", 2, buzzed())

                inst.runOnMainSync { find.search("") }
                assertEquals("", count())
                var total = -1
                inst.runOnMainSync { total = find.total }
                assertEquals(0, total)
                inst.runOnMainSync { find.close() }
            }
        } finally {
            server.close()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !ok()) SystemClock.sleep(100)
        assertTrue("timed out: $what", ok())
    }
}
