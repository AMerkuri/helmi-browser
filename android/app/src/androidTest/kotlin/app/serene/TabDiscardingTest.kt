package app.serene

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import app.serene.browser.TabDiscard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Background tabs let their page go to save memory (TabDiscard) and get it back, with its
 * history, when shown; a tab with something typed in keeps its page; after a restart only the
 * tab on screen loads.
 */
@RunWith(AndroidJUnit4::class)
class TabDiscardingTest {
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val app get() = ApplicationProvider.getApplicationContext<SereneApp>()

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
        app.settings.savedTabs = emptyList()
        app.settings.discardTabs = true
    }

    @After
    fun tearDown() {
        server.close()
        app.settings.savedTabs = emptyList()
    }

    private fun serve(s: Socket): Unit = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val name = r.readLine()?.split(' ')?.getOrNull(1)?.trimStart('/') ?: return
        while (!r.readLine().isNullOrEmpty()) Unit
        val html = "<!doctype html><title>$name</title><p>$name</p><input id=q>".toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(html)
            flush()
        }
    }

    @Test
    fun backgroundTabComesBackWithItsHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var behind: Tab? = null
            var front: Tab? = null
            scenario.onActivity { a -> behind = a.tabs.open("$base/one") }
            val b = behind!!
            waitFor("page one") { b.title == "one" && b.progress == 100 }
            scenario.onActivity { b.load("$base/two") }
            waitFor("page two") { b.title == "two" && b.progress == 100 && b.canGoBack }
            scenario.onActivity { a -> front = a.tabs.open("$base/front") }
            val f = front!!
            waitFor("the front page") { f.title == "front" && f.progress == 100 }

            var old: WebView? = null
            scenario.onActivity { a -> old = b.webView; a.tabs.discardIdle(TabDiscard.Pressure.LOW) }
            waitFor("the tab behind lets its page go") { b.unloaded != null }
            scenario.onActivity {
                assertEquals(Tab.Why.DISCARD, b.unloaded!!.why)
                assertTrue("a new WebView", b.webView !== old)
                assertEquals("its title stays for its card", "two", b.title)
                assertTrue("history kept for a restart", b.history().isNotEmpty())
                assertNull("not the tab on screen", f.unloaded)
            }
            scenario.onActivity { a -> a.tabs.select(b) }
            waitFor("its page back once shown") { b.unloaded == null && !b.waking && b.title == "two" && b.progress == 100 && b.canGoBack }
            scenario.onActivity { b.goBack() }
            waitFor("back still goes back") { b.url.endsWith("/one") && b.progress == 100 }
        }
    }

    @Test
    fun tabWithTypedTextKeepsItsPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var behind: Tab? = null
            scenario.onActivity { a -> behind = a.tabs.open("$base/form") }
            val b = behind!!
            waitFor("the form") { b.title == "form" && b.progress == 100 }
            scenario.onActivity { b.webView.evaluateJavascript("document.getElementById('q').value = 'half a message'", null) }
            scenario.onActivity { a -> a.tabs.open("$base/front") }
            waitFor("the front page") { b.webView.isAttachedToWindow.not() }
            scenario.onActivity { a -> a.tabs.discardIdle(TabDiscard.Pressure.LOW) }
            SystemClock.sleep(1500)
            scenario.onActivity { assertNull("the typed text would be lost", b.unloaded) }
        }
    }

    @Test
    fun afterARestartOnlyTheTabOnScreenLoads() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var first: Tab? = null
            var second: Tab? = null
            scenario.onActivity { a -> first = a.tabs.open("$base/first") }
            waitFor("page first") { first!!.title == "first" && first!!.progress == 100 }
            scenario.onActivity { a -> second = a.tabs.open("$base/second") }
            waitFor("page second") { second!!.title == "second" && second!!.progress == 100 }
            scenario.recreate() // onStop saves the tabs; onCreate restores them
            var restored: List<Tab> = emptyList()
            scenario.onActivity { a -> restored = a.tabs.tabs.toList() }
            val back = restored.single { it.url.endsWith("/first") }
            val shown = restored.single { it.url.endsWith("/second") }
            waitFor("the tab on screen loads") { shown.title == "second" && shown.progress == 100 }
            scenario.onActivity {
                assertEquals(Tab.Why.DISCARD, back.unloaded?.why)
                assertEquals("its saved title", "first", back.title)
            }
            scenario.onActivity { a -> a.tabs.select(back) }
            waitFor("the other tab loads once shown") { back.unloaded == null && back.title == "first" && back.progress == 100 }
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
