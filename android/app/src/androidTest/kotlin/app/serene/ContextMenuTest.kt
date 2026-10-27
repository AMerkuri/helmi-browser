package app.serene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.PageImages
import app.serene.browser.Pressed
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A long press on a link or a picture opens the context menu with what was pressed, and does
 * not follow the link; plain text stays WebView's. The picture can be fetched to copy or share.
 */
@RunWith(AndroidJUnit4::class)
class ContextMenuTest {
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
            "/photo.svg" -> "image/svg+xml" to "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10' fill='#f80'/></svg>"
            // Four bands down the page: a text link, a picture link, a picture, plain text.
            "/page" -> "text/html" to "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>page</title>" +
                "<body style='margin:0;font-size:28px'>" +
                "<a href=/story style='display:block;height:20vh'>Read  the\n whole story</a>" +
                "<a href=/gallery style='display:block;height:20vh'><img src=/pic.svg alt='Blue  square' style='width:100%;height:100%;display:block'></a>" +
                "<img src=/photo.svg alt='Orange square' style='width:100%;height:20vh;display:block'>" +
                "<p style='height:20vh;margin:0'>Plain text, no link</p>"
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
    fun longPressOnLinksAndPictures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { a ->
                tab = a.tabs.open("$base/page")
                a.currentFocus?.clearFocus()
                WindowCompat.getInsetsController(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            val t = tab!!
            waitFor("the page") { t.title == "page" && t.progress == 100 }
            SystemClock.sleep(800)

            longPress(t, 0.1f)
            waitFor("the menu for the text link") { t.contextMenu != null }
            assertEquals(Pressed("$base/story", "Read the whole story", null), menu(t))
            // The finger lifting off did not follow the link.
            SystemClock.sleep(1000)
            assertEquals("$base/page", t.url)
            close(t)

            longPress(t, 0.3f)
            waitFor("the menu for the picture link, with the alt text") { t.contextMenu?.alt?.isNotEmpty() == true }
            val pictureLink = menu(t)!!
            assertEquals("$base/gallery", pictureLink.link)
            assertEquals("$base/pic.svg", pictureLink.image)
            assertEquals("Blue square", pictureLink.alt)
            close(t)

            longPress(t, 0.5f)
            waitFor("the menu for the picture, with the alt text") { t.contextMenu?.alt?.isNotEmpty() == true }
            assertEquals(Pressed(null, "", "$base/photo.svg", "Orange square"), menu(t))
            close(t)

            longPress(t, 0.7f)
            SystemClock.sleep(1500)
            assertNull("plain text opened the context menu", menu(t))
            assertEquals("$base/page", t.url)

            // What Copy image and Share image hand on.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var ua = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ua = t.userAgent }
            val img = PageImages.fetch(context, "$base/photo.svg", ua, base)
            assertNotNull("the picture wasn't fetched", img)
            assertEquals("image/svg+xml", img!!.mimeType)
            assertTrue(img.file.readText().contains("#f80"))
            assertEquals("image/png", PageImages.fetch(context, "data:image/png;base64,iVBORw0KGgo=", ua, null)?.mimeType)
            assertNull("a page is not a picture", PageImages.fetch(context, "$base/story", ua, null))
        }
    }

    private fun menu(t: Tab): Pressed? {
        var p: Pressed? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { p = t.contextMenu }
        return p
    }

    private fun close(t: Tab) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { t.contextMenu = null }
        SystemClock.sleep(500)
    }

    /** A finger held still for a second at a quarter of the WebView's width, at [y] of its height. */
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
