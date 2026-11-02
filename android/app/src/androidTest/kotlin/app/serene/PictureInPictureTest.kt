package app.serene

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A fullscreen video that plays goes along into a small window when the user goes home, and is
 * in fullscreen again back in Helmi; a paused one stays behind. A page's own
 * requestPictureInPicture() takes its video into the window at once, and back in Helmi the video
 * is in the page again. The video plays a canvas (see FullscreenTest).
 */
@RunWith(AndroidJUnit4::class)
class PictureInPictureTest {
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
        // /full: a tap puts the video in fullscreen. /pip: a tap asks for picture-in-picture.
        // The title says where the video is.
        val script = if (target.startsWith("/pip")) {
            "document.body.onclick = () => { v.requestPictureInPicture().catch(e => { document.title = 'refused ' + e.name; }); };" +
                "v.addEventListener('enterpictureinpicture', () => { if (document.pictureInPictureElement === v) document.title = 'pip'; });" +
                "v.addEventListener('leavepictureinpicture', () => { document.title = 'left'; });"
        } else {
            "document.body.onclick = () => { v.requestFullscreen(); };" +
                "document.addEventListener('fullscreenchange', () => { document.title = document.fullscreenElement ? 'full' : 'page'; });"
        }
        val body = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>page</title>" +
            "<body style='margin:0'>" +
            "<video id=v muted autoplay playsinline style='width:100vw;height:100vh;background:#000'></video>" +
            "<script>const c = document.createElement('canvas'); c.width = 320; c.height = 180; const g = c.getContext('2d');" +
            "let n = 0; (function draw() { g.fillStyle = n++ % 2 ? '#c33' : '#3c3'; g.fillRect(0, 0, c.width, c.height); requestAnimationFrame(draw); })();" +
            "const v = document.getElementById('v'); v.srcObject = c.captureStream(10); v.play().catch(() => {});" +
            "$script</script>"
        val bytes = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    @Test
    fun aPlayingFullscreenVideoGoesAlongWhenLeaving() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/full") }
            waitFor("the page") { page.title == "page" && page.progress == 100 }
            SystemClock.sleep(500)

            tap(page)
            waitFor("the video in fullscreen, playing") { main.fullscreen.isShown && page.fullscreenMedia?.playing == true }

            home()
            waitFor("the video in a small window") { main.isInPictureInPictureMode && main.fullscreen.isShown }

            // The window's pause and play buttons reach the video.
            scenario.onActivity { page.playFullscreenVideo(false) }
            waitFor("the video paused") { page.fullscreenMedia?.playing == false }
            scenario.onActivity { page.playFullscreenVideo(true) }
            waitFor("the video playing again") { page.fullscreenMedia?.playing == true }

            backToHelmi()
            waitFor("the window opened out, the video still in fullscreen") {
                !main.isInPictureInPictureMode && main.fullscreen.isShown && page.title == "full"
            }
        }
    }

    @Test
    fun aPausedVideoStaysBehind() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/full") }
            waitFor("the page") { page.title == "page" && page.progress == 100 }
            SystemClock.sleep(500)

            tap(page)
            waitFor("the video in fullscreen, playing") { main.fullscreen.isShown && page.fullscreenMedia?.playing == true }
            scenario.onActivity { page.playFullscreenVideo(false) }
            waitFor("the video paused") { page.fullscreenMedia?.playing == false }

            home()
            SystemClock.sleep(3_000)
            var inWindow = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync { inWindow = main.isInPictureInPictureMode }
            assertFalse("a paused video went into a small window", inWindow)
            backToHelmi()
        }
    }

    @Test
    fun aPagesOwnRequestTakesItsVideoIntoTheWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var page: Tab
            scenario.onActivity { a -> main = a; page = a.tabs.open("http://localhost:$port/pip") }
            waitFor("the page") { page.title == "page" && page.progress == 100 }
            waitFor("the page sees picture-in-picture") { evaluate(page, "document.pictureInPictureEnabled") == "true" }
            SystemClock.sleep(500)

            tap(page)
            waitFor("the video in a small window") { main.isInPictureInPictureMode && main.fullscreen.isShown && page.title == "pip" }

            backToHelmi()
            waitFor("the video back in the page") {
                !main.isInPictureInPictureMode && !main.fullscreen.isShown && page.fullscreen == null && page.title == "left"
            }
        }
    }

    private fun home() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** As the launcher icon or the window's tap does: the activity to the front. */
    private fun backToHelmi() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun evaluate(tab: Tab, js: String): String? {
        var result: String? = null
        val done = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { tab.webView.evaluateJavascript(js) { result = it; done.countDown() } }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return result
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
