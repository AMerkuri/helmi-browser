package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The tabs feed the history: a page with its title, a same-document step (history.pushState) with
 * the title the script gave it, once a day per page, and a reload is not a visit. Then the History
 * screen opens with them.
 */
@RunWith(AndroidJUnit4::class)
class HistoryTest {
    @Test
    fun visitsAndTitlesAreRecorded() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val path = r.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val html = when {
                        path.startsWith("/first") -> "<!doctype html><title>First page</title><p>one"
                        else -> "<!doctype html><title>Second page</title><p>two"
                    }.toByteArray()
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
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { app.history.clear() }
                var tab: Tab? = null
                scenario.onActivity { a -> tab = a.tabs.current!!.apply { load("$base/first") } }
                waitFor("the first page's title") { app.history.entries.firstOrNull()?.title == "First page" }

                scenario.onActivity { tab!!.load("$base/second") }
                waitFor("the second page's title") { app.history.entries.firstOrNull()?.title == "Second page" }

                // A single-page site's step: no load, the script names the new page.
                scenario.onActivity { tab!!.webView.evaluateJavascript("history.pushState({}, '', '/second/more'); document.title = 'More'", null) }
                waitFor("the pushState step") { app.history.entries.firstOrNull()?.let { it.url == "$base/second/more" && it.title == "More" } == true }

                scenario.onActivity { tab!!.reload() }
                SystemClock.sleep(2000)
                scenario.onActivity { tab!!.load("$base/first") }
                waitFor("the first page again") { app.history.entries.firstOrNull()?.url == "$base/first" }

                var urls = emptyList<String>()
                var firstCount = 0
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    urls = app.history.entries.map { it.url }
                    firstCount = app.history.entries.first().count
                }
                // Once a day per page: the first page moved to the top. The reload added nothing.
                assertEquals(listOf("$base/first", "$base/second/more", "$base/second"), urls)
                assertEquals(2, firstCount)
                var found = emptyList<String>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { found = app.history.search("more").map { it.url } }
                assertEquals(listOf("$base/second/more"), found)
                scenario.onActivity { a -> a.open(Screen.History); assertEquals(Screen.History, a.screen) }
                SystemClock.sleep(500) // drawn with the entries
            }
        } finally {
            server.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.history.clear() }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
