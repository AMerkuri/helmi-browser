package app.serene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Link
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A long press on a link opens the link menu with the link's address and text, and does not
 * follow the link; a picture link gives its link's address; plain text stays WebView's.
 */
@RunWith(AndroidJUnit4::class)
class LinkMenuTest {
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"

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
        val (type, body) = when (path) {
            "/pic.svg" -> "image/svg+xml" to "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10' fill='#08f'/></svg>"
            // Three bands down the page: a text link, a picture link, plain text.
            "/links" -> "text/html" to "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>links</title>" +
                "<body style='margin:0;font-size:28px'>" +
                "<a href=/story style='display:block;height:30vh'>Read  the\n whole story</a>" +
                "<a href=/gallery style='display:block;height:30vh'><img src=/pic.svg style='width:100%;height:100%'></a>" +
                "<p style='height:30vh;margin:0'>Plain text, no link</p>"
            else -> "text/html" to "<!doctype html><title>${path.trimStart('/')}</title>"
        }
        val bytes = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    @Test
    fun longPressOnLinks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { a ->
                tab = a.tabs.open("$base/links")
                a.currentFocus?.clearFocus()
                WindowCompat.getInsetsController(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            val t = tab!!
            waitFor("the links page") { t.title == "links" && t.progress == 100 }
            SystemClock.sleep(800)

            longPress(t, 0.15f)
            waitFor("the menu for the text link") { t.linkMenu != null }
            assertEquals(Link("$base/story", "Read the whole story"), menu(t))
            // The finger lifting off did not follow the link.
            SystemClock.sleep(1000)
            assertEquals("$base/links", t.url)
            close(t)

            longPress(t, 0.45f)
            waitFor("the menu for the picture link") { t.linkMenu != null }
            assertEquals("$base/gallery", menu(t)!!.url)
            close(t)

            longPress(t, 0.75f)
            SystemClock.sleep(1500)
            assertNull("plain text opened the link menu", menu(t))
            assertEquals("$base/links", t.url)
        }
    }

    private fun menu(t: Tab): Link? {
        var link: Link? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { link = t.linkMenu }
        return link
    }

    private fun close(t: Tab) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { t.linkMenu = null }
        SystemClock.sleep(500)
    }

    /** A finger held still for a second in the middle of the WebView, at [y] of its height. */
    private fun longPress(tab: Tab, y: Float) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val xy = IntArray(2)
        var w = 0
        var h = 0
        inst.runOnMainSync {
            tab.webView.getLocationOnScreen(xy)
            w = tab.webView.width
            h = tab.webView.height
        }
        val x = xy[0] + w / 4f
        val yy = xy[1] + h * y
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, yy, 0))
        SystemClock.sleep(1000)
        inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, yy, 0))
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
