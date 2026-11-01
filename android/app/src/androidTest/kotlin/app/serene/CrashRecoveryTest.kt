package app.serene

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The page's renderer going away (chrome://crash crashes it, chrome://kill kills it as Android
 * does for memory): the app lives on, the tab on screen says the page crashed until Reload, which
 * brings the page back with its history, and the other tabs get their pages back when shown.
 */
@RunWith(AndroidJUnit4::class)
class CrashRecoveryTest {
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
        ApplicationProvider.getApplicationContext<SereneApp>().settings.savedTabs = emptyList()
    }

    @After
    fun tearDown() = server.close()

    private fun serve(s: Socket): Unit = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val name = r.readLine()?.split(' ')?.getOrNull(1)?.trimStart('/') ?: return
        while (!r.readLine().isNullOrEmpty()) Unit
        val html = "<!doctype html><title>$name</title><p>$name</p>".toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(html)
            flush()
        }
    }

    @Test
    fun crashOnScreenWaitsForReloadAndKeepsHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { a -> tab = a.tabs.open("$base/first") }
            val t = tab!!
            waitFor("page first") { t.title == "first" && t.progress == 100 }
            scenario.onActivity { t.load("$base/second") }
            waitFor("page second") { t.title == "second" && t.progress == 100 && t.canGoBack }
            assumeSeparateRenderer(t)

            var dead: WebView? = null
            scenario.onActivity { dead = t.webView; t.webView.loadUrl("chrome://crash") }
            waitFor("the crash") { t.unloaded != null }
            scenario.onActivity {
                val c = t.unloaded!!
                assertTrue("a crash, not a kill", c.crash)
                assertTrue("the tab was on screen", c.onScreen)
                assertTrue("a new WebView", t.webView !== dead)
                assertTrue("the address stays", t.url.endsWith("/second"))
                assertTrue("history kept for a restart", t.history().isNotEmpty())
            }
            // Until Reload: a page that crashes as it loads would crash again and again.
            SystemClock.sleep(1500)
            scenario.onActivity { assertNotNull("still crashed", t.unloaded) }

            scenario.onActivity { t.reload() } // the crashed page's Reload button
            waitFor("the page back") { t.unloaded == null && t.title == "second" && t.progress == 100 && t.canGoBack }
            scenario.onActivity { t.goBack() }
            waitFor("back to the page before the crash") { t.url.endsWith("/first") && t.progress == 100 && t.canGoForward }
        }
    }

    @Test
    fun tabsNotOnScreenComeBackWhenShown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var behind: Tab? = null
            var front: Tab? = null
            scenario.onActivity { a ->
                behind = a.tabs.open("$base/behind")
                front = a.tabs.open("$base/front")
            }
            val b = behind!!
            val f = front!!
            waitFor("both pages") { b.title == "behind" && f.title == "front" && b.progress == 100 && f.progress == 100 }
            assumeSeparateRenderer(f)

            // One renderer serves every tab: killing it takes every page.
            scenario.onActivity { f.webView.loadUrl("chrome://kill") }
            waitFor("both tabs lose their pages") { b.unloaded != null && f.unloaded != null }
            scenario.onActivity {
                assertFalse("killed, not crashed", f.unloaded!!.crash)
                assertTrue("the tab on screen shows it", f.unloaded!!.onScreen)
                assertFalse("the tab behind it was not on screen", b.unloaded!!.onScreen)
            }
            scenario.onActivity { a -> a.tabs.select(b) }
            waitFor("the tab behind gets its page back once shown") { b.unloaded == null && b.title == "behind" && b.progress == 100 }
            scenario.onActivity { assertNotNull("the tab left keeps waiting for Reload", f.unloaded) }
            scenario.onActivity { a -> a.tabs.select(f); f.reload() }
            waitFor("the other page back") { f.unloaded == null && f.title == "front" && f.progress == 100 }
            scenario.onActivity { a -> assertEquals("both tabs still open", 3, a.tabs.tabs.size) }
        }
    }

    // A renderer in the app's own process (some low-memory devices) takes the app down with it.
    private fun assumeSeparateRenderer(t: Tab) {
        var separate = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            separate = WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) && WebViewCompat.getWebViewRenderProcess(t.webView) != null
        }
        assumeTrue("WebView runs its renderer in the app's process", separate)
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
