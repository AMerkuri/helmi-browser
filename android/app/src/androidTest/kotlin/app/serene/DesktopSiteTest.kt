package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.serene.browser.Tab
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ⋮ › Desktop site: the site's pages come with a desktop user agent (in the request and in
 * navigator.userAgent) and a desktop-wide viewport, a link to another site goes out with that
 * site's (phone) user agent, and back to the desktop site gets the desktop one again. Settings ›
 * Accessibility: "Override sites' zoom block" lifts user-scalable=no, Text size sets textZoom.
 * 127.0.0.1 and localhost are two sites.
 */
@RunWith(AndroidJUnit4::class)
class DesktopSiteTest {
    private lateinit var server: ServerSocket
    private val app get() = ApplicationProvider.getApplicationContext<SereneApp>()
    private var served = 0

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
        app.settings.savedTabs = emptyList()
    }

    @After
    fun tearDown() {
        server.close()
        app.settings.setDesktopSite("127.0.0.1", false)
        app.settings.forceZoom = false
        app.settings.textScale = 100
    }

    // The page says what it was asked with: the request's User-Agent, navigator's, its width and viewport.
    private fun serve(s: Socket): Unit = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return
        var ua = ""
        while (true) {
            val line = r.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("User-Agent:", ignoreCase = true)) ua = line.substringAfter(':').trim()
        }
        val n = synchronized(this) { ++served }
        val html = """<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
            <p>Desktop site test</p>
            <script>addEventListener('load', () => setTimeout(() => { document.title = JSON.stringify({
                n: $n, path: ${JSONObject.quote(path)}, header: ${JSONObject.quote(ua)}, ua: navigator.userAgent,
                width: document.documentElement.clientWidth, viewport: document.querySelector('meta[name=viewport]').content,
                mobile: navigator.userAgentData ? navigator.userAgentData.mobile : null,
            }); }, 100));</script>""".toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(html)
            flush()
        }
    }

    private fun waitForPage(t: Tab, path: String, after: Int): JSONObject {
        val end = SystemClock.uptimeMillis() + 20_000
        var last = ""
        while (SystemClock.uptimeMillis() < end) {
            last = t.title
            val page = runCatching { JSONObject(last) }.getOrNull()
            if (page != null && page.getString("path") == path && page.getInt("n") > after) return page
            SystemClock.sleep(100)
        }
        throw AssertionError("page $path (after load $after) did not come; title: $last")
    }

    private fun assertMobile(page: JSONObject, where: String) {
        assertTrue("$where: phone user agent in the request, got ${page.getString("header")}", page.getString("header").contains("Mobile"))
        assertTrue("$where: phone width, got ${page.getInt("width")}", page.getInt("width") < 980)
    }

    private fun assertDesktop(page: JSONObject, where: String) {
        val header = page.getString("header")
        for (token in listOf("Android", "Mobile", "; wv")) assertFalse("$where: '$token' in the request's user agent $header", header.contains(token))
        assertTrue("$where: desktop user agent $header", header.contains("X11; Linux x86_64"))
        assertEquals("$where: navigator.userAgent", header, page.getString("ua"))
        assertEquals("$where: desktop width", 980, page.getInt("width"))
        if (!page.isNull("mobile")) assertFalse("$where: navigator.userAgentData.mobile", page.getBoolean("mobile"))
    }

    @Test
    fun desktopSiteFollowsTheSite() {
        val a = "http://127.0.0.1:${server.localPort}"
        val b = "http://localhost:${server.localPort}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { tab = it.tabs.open("$a/first") }
            val t = tab!!
            var page = waitForPage(t, "/first", 0)
            assertMobile(page, "before")

            scenario.onActivity { t.setDesktopSite(true) }
            page = waitForPage(t, "/first", page.getInt("n"))
            assertDesktop(page, "switched on")
            assertTrue("remembered for the site", app.settings.isDesktopSite("127.0.0.1"))

            // A link on the page to another site: that site's own (phone) user agent.
            scenario.onActivity { t.webView.evaluateJavascript("location.href = '$b/other'", null) }
            page = waitForPage(t, "/other", page.getInt("n"))
            assertMobile(page, "link to another site")

            // Back: the desktop site's pages again (no-store: loaded again, not from the cache).
            scenario.onActivity { t.go(-1) }
            page = waitForPage(t, "/first", page.getInt("n"))
            assertDesktop(page, "back")

            // Another page of the site, typed in.
            scenario.onActivity { t.load("$a/second") }
            page = waitForPage(t, "/second", page.getInt("n"))
            assertDesktop(page, "same site")

            scenario.onActivity { t.setDesktopSite(false) }
            page = waitForPage(t, "/second", page.getInt("n"))
            assertMobile(page, "switched off")
            assertFalse("forgotten for the site", app.settings.isDesktopSite("127.0.0.1"))
        }
    }

    @Test
    fun zoomBlockOverrideAndTextSize() {
        val a = "http://127.0.0.1:${server.localPort}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            scenario.onActivity { tab = it.tabs.open("$a/zoom") }
            val t = tab!!
            var page = waitForPage(t, "/zoom", 0)
            assertTrue("the page's own viewport: ${page.getString("viewport")}", page.getString("viewport").contains("user-scalable=no"))

            scenario.onActivity { app.settings.forceZoom = true }
            scenario.onActivity { t.reload() }
            page = waitForPage(t, "/zoom", page.getInt("n"))
            val viewport = page.getString("viewport")
            assertTrue("zoom allowed: $viewport", viewport.contains("user-scalable=yes") && viewport.contains("maximum-scale=5"))
            assertTrue("width kept: $viewport", viewport.contains("width=device-width"))

            var zoom = 0
            var expected = 0
            scenario.onActivity {
                app.settings.textScale = 200
                expected = Math.round(200 * t.webView.resources.configuration.fontScale)
            }
            val end = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < end) {
                scenario.onActivity { zoom = t.webView.settings.textZoom }
                if (zoom == expected) break
                SystemClock.sleep(100)
            }
            assertEquals("textZoom at 200%", expected, zoom)
        }
    }
}
