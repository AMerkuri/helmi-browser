package app.serene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The page gestures with real touches: a swipe to the right goes back, to the left forward,
 * a pull down at the top reloads, and a swipe on a carousel stays the carousel's.
 */
@RunWith(AndroidJUnit4::class)
class PageGesturesTest {
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val requests = Collections.synchronizedList(mutableListOf<String>())

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
        requests += path
        val name = path.trimStart('/')
        val body = when (name) {
            // A carousel as wide as the page: its own horizontal scroller.
            "carousel" -> "<div id=c style='overflow-x:auto;white-space:nowrap;height:80vh'>" +
                (1..5).joinToString("") { "<div style='display:inline-block;width:100vw;height:100%;background:hsl(${it * 60},70%,60%)'>$it</div>" } + "</div>"
            else -> "<p style='height:300vh;margin:0;background:linear-gradient(#fff,#08f)'>$name</p>"
        }
        val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>$name</title><body style='margin:0'>$body".toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(html)
            flush()
        }
    }

    @Test
    fun swipeAndPull() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { a ->
                tab = a.tabs.open("$base/one")
                // Keep the keyboard out of the way of the touches.
                a.currentFocus?.clearFocus()
                WindowCompat.getInsetsController(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            val t = tab!!
            waitFor("page one") { t.title == "one" && t.progress == 100 }
            scenario.onActivity { t.load("$base/two") }
            waitFor("page two") { t.title == "two" && t.progress == 100 && t.canGoBack }
            SystemClock.sleep(500)

            swipe(t, from = 0.25f, to = 0.85f)
            waitFor("a swipe to the right to go back") { t.url.endsWith("/one") && !t.swipe.active }
            SystemClock.sleep(500)

            swipe(t, from = 0.75f, to = 0.15f)
            waitFor("a swipe to the left to go forward") { t.url.endsWith("/two") && !t.swipe.active }
            SystemClock.sleep(500)

            val before = requests.count { it == "/two" }
            pull(t)
            waitFor("a pull down to reload") { requests.count { it == "/two" } > before }
            waitFor("the indicator to go away") { !t.pull.active }

            // A carousel keeps its swipes, with history to go forward to.
            scenario.onActivity { t.load("$base/carousel") }
            waitFor("the carousel") { t.title == "carousel" && t.progress == 100 }
            scenario.onActivity { t.load("$base/three") }
            waitFor("page three") { t.title == "three" && t.progress == 100 }
            scenario.onActivity { t.goBack() }
            waitFor("back on the carousel") { t.url.endsWith("/carousel") && t.progress == 100 && t.canGoForward }
            SystemClock.sleep(500)
            swipe(t, from = 0.75f, to = 0.15f, y = 0.3f)
            SystemClock.sleep(1500)
            var url = ""
            scenario.onActivity { url = t.url }
            assertTrue("a swipe on the carousel went forward to $url", url.endsWith("/carousel"))
            var scrolled = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                t.webView.evaluateJavascript("document.getElementById('c').scrollLeft") { scrolled = it }
            }
            waitFor("the carousel's scroll position") { scrolled.isNotEmpty() }
            assertTrue("the carousel did not scroll ($scrolled)", (scrolled.toDoubleOrNull() ?: 0.0) > 0)

            // A pull with the page scrolled down is the page's scroll, not a reload.
            scenario.onActivity { t.load("$base/four") }
            waitFor("page four") { t.title == "four" && t.progress == 100 }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { t.webView.scrollTo(0, 600) }
            SystemClock.sleep(300)
            val fours = requests.count { it == "/four" }
            pull(t)
            SystemClock.sleep(1500)
            assertEquals("a pull mid-page reloaded", fours, requests.count { it == "/four" })
            var pulling = true
            scenario.onActivity { pulling = t.pull.active }
            assertFalse(pulling)
        }
    }

    /** A finger across the WebView, from and to fractions of its width, at [y] of its height. */
    private fun swipe(tab: Tab, from: Float, to: Float, y: Float = 0.5f) {
        val (x0, y0, w, h) = bounds(tab)
        drag(x0 + w * from, y0 + h * y, x0 + w * to, y0 + h * y)
    }

    private fun pull(tab: Tab) {
        val (x0, y0, w, h) = bounds(tab)
        drag(x0 + w / 2, y0 + h * 0.2f, x0 + w / 2, y0 + h * 0.75f)
    }

    private fun bounds(tab: Tab): List<Float> {
        var b = listOf(0f, 0f, 0f, 0f)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val xy = IntArray(2).also { tab.webView.getLocationOnScreen(it) }
            b = listOf(xy[0].toFloat(), xy[1].toFloat(), tab.webView.width.toFloat(), tab.webView.height.toFloat())
        }
        return b
    }

    /** A finger's drag through the whole input pipeline, at about 1000 px/s. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
        val steps = 30
        var t = down
        for (i in 1..steps) {
            SystemClock.sleep(16)
            t = SystemClock.uptimeMillis()
            val f = i / steps.toFloat()
            inst.sendPointerSync(MotionEvent.obtain(down, t, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0))
        }
        inst.sendPointerSync(MotionEvent.obtain(down, t + 16, MotionEvent.ACTION_UP, x1, y1, 0))
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
