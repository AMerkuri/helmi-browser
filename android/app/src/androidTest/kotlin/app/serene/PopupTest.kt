package app.serene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A window a page opens is a tab of its own that keeps the page as its opener: a sign-in popup
 * from another site answers the page and closes itself, and the page comes back (LinkedIn's
 * "Continue with Google" was left blank when the popup replaced the page). Back on a
 * target=_blank link's tab returns to the page with the link.
 */
@RunWith(AndroidJUnit4::class)
class PopupTest {
    private lateinit var server: ServerSocket
    private val port get() = server.localPort

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun serve(s: Socket): Unit = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!r.readLine().isNullOrEmpty()) Unit
        val head = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
        val body = when (path) {
            // The site (localhost) opens the sign-in popup from another origin (127.0.0.1).
            "/site" -> "$head<title>site</title><body style='margin:0'>" +
                "<button style='width:100vw;height:100vh' onclick=\"window.open('http://127.0.0.1:$port/signin', 'signin', 'width=400,height=600')\">Sign in</button>" +
                "<script>addEventListener('message', e => { if (e.origin === 'http://127.0.0.1:$port') document.title = 'got ' + e.data })</script>"
            // The popup answers its opener, as Google's does, and closes itself.
            "/signin" -> "$head<title>signin</title><script>setTimeout(() => { opener.postMessage('token', 'http://localhost:$port'); window.close() }, 1500)</script>"
            "/links" -> "$head<title>links</title><body style='margin:0'><a href=/story target=_blank style='display:block;height:100vh;font-size:40px'>Story</a>"
            else -> "$head<title>${path.trimStart('/')}</title>"
        }
        val bytes = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    @Test
    fun signInPopupAnswersItsOpenerAndCloses() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var site: Tab
            scenario.onActivity { a -> main = a; site = a.tabs.open("http://localhost:$port/site") }
            waitFor("the site") { site.title == "site" && site.progress == 100 }
            val count = tabCount(main)
            SystemClock.sleep(500)

            tap(site)
            waitFor("the popup in a tab of its own, on screen") { main.tabs.tabs.size == count + 1 && main.tabs.current?.title == "signin" }
            var popup: Tab? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { popup = main.tabs.current }
            assertSame(site, popup!!.opener)
            assertEquals("http://localhost:$port/site", site.url)

            waitFor("the popup to close and the site to come back") { main.tabs.tabs.size == count && main.tabs.current === site }
            waitFor("the site to get the popup's answer") { site.title == "got token" }
        }
    }

    @Test
    fun backFromANewTabLinkReturnsToThePage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/links") }
            waitFor("the page") { page.title == "links" && page.progress == 100 }
            val count = tabCount(main)
            SystemClock.sleep(500)

            tap(page)
            waitFor("the story in a new tab") { main.tabs.tabs.size == count + 1 && main.tabs.current?.title == "story" }
            assertEquals("http://localhost:$port/links", page.url)

            scenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            waitFor("back to the page, the story's tab closed") { main.tabs.tabs.size == count && main.tabs.current === page }
        }
    }

    private fun tabCount(a: MainActivity): Int {
        var n = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { n = a.tabs.tabs.size }
        return n
    }

    /** A tap in the middle of the WebView. */
    private fun tap(tab: Tab) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val xy = IntArray(2)
        var w = 0
        var h = 0
        inst.runOnMainSync {
            tab.webView.getLocationOnScreen(xy)
            w = tab.webView.width
            h = tab.webView.height
        }
        val x = xy[0] + w / 2f
        val y = xy[1] + h / 2f
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        inst.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
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
