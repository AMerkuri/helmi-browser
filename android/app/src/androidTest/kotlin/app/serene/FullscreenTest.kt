package app.serene

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A video's fullscreen button shows the video over everything with the system bars hidden, turns
 * a phone to the video's orientation, and back (or the page) leaves it with everything as it was.
 * The video plays a canvas, so its size is the canvas's: /video?w=320&h=180.
 */
@RunWith(AndroidJUnit4::class)
class FullscreenTest {
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
        val target = r.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!r.readLine().isNullOrEmpty()) Unit
        val q = target.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val w = q["w"] ?: "320"
        val h = q["h"] ?: "180"
        // A tap anywhere puts the video in fullscreen; the title says where the page is.
        val body = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>page</title>" +
            "<body style='margin:0' onclick='document.getElementById(\"v\").requestFullscreen()'>" +
            "<video id=v muted autoplay playsinline style='width:100vw;height:100vh;background:#000'></video>" +
            "<script>const c = document.createElement('canvas'); c.width = $w; c.height = $h; const g = c.getContext('2d');" +
            "let n = 0; (function draw() { g.fillStyle = n++ % 2 ? '#c33' : '#3c3'; g.fillRect(0, 0, c.width, c.height); requestAnimationFrame(draw); })();" +
            "const v = document.getElementById('v'); v.srcObject = c.captureStream(10); v.play().catch(() => {});" +
            "document.addEventListener('fullscreenchange', () => { document.title = document.fullscreenElement ? 'full' : 'page'; });</script>"
        val bytes = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    @Test
    fun aLandscapeVideoGoesFullscreenAndBackLeavesIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/video?w=320&h=180") }
            waitFor("the page") { page.title == "page" && page.progress == 100 }
            SystemClock.sleep(500)
            val before = orientation(main)

            tap(page)
            waitFor("the video in fullscreen") { page.title == "full" && page.fullscreen != null && main.fullscreen.isShown }
            waitFor("the video's size from the page") { page.fullscreenVideo?.width == 320 && page.fullscreenVideo?.height == 180 }
            waitFor("the phone turned to landscape") { main.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
            waitFor("the system bars hidden") { !barsVisible(main) }
            waitFor("the video on screen") { page.fullscreen?.view?.isAttachedToWindow == true }

            scenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            waitFor("fullscreen left") { page.fullscreen == null && !main.fullscreen.isShown && page.title == "page" }
            waitFor("the orientation as it was") { main.requestedOrientation == before }
            waitFor("the system bars back") { barsVisible(main) }
        }
    }

    @Test
    fun aPortraitVideoKeepsPortraitAndThePageCanLeave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/video?w=180&h=320") }
            waitFor("the page") { page.title == "page" && page.progress == 100 }
            SystemClock.sleep(500)
            val before = orientation(main)

            tap(page)
            waitFor("the video in fullscreen") { page.title == "full" && main.fullscreen.isShown }
            waitFor("the phone kept in portrait") { main.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT }

            scenario.onActivity { page.webView.evaluateJavascript("document.exitFullscreen()", null) }
            waitFor("fullscreen left") { page.fullscreen == null && !main.fullscreen.isShown && page.title == "page" }
            waitFor("the orientation as it was") { main.requestedOrientation == before }
            waitFor("the system bars back") { barsVisible(main) }
        }
    }

    private fun orientation(a: MainActivity): Int {
        var o = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { o = a.requestedOrientation }
        return o
    }

    private fun barsVisible(a: MainActivity): Boolean =
        ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true

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
