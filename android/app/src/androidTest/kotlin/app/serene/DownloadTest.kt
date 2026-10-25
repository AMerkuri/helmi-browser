package app.serene

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import app.serene.download.Download
import app.serene.download.DownloadState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Downloads against a local server: a tab opened for a file shows the download page (not a
 * black WebView), the signed-in cookie goes along after a redirect, a link on a page keeps the
 * page, and pause/resume continues the file with a Range request.
 */
@RunWith(AndroidJUnit4::class)
class DownloadTest {
    private val file = ByteArray(2_000_000) { (it % 251).toByte() }
    private val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val started = mutableListOf<Download>()

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        // No permission dialog over the activity (it would also cover later tests' screenshots).
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
    }

    @After
    fun tearDown() {
        server.close()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SereneApp
        started.forEach { d -> d.uri?.let { runCatching { app.contentResolver.delete(it, null, null) } } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { started.forEach(app.downloads::remove) }
    }

    private fun serve(s: Socket) = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return
        val headers = mutableMapOf("path" to path)
        while (true) {
            val line = r.readLine()
            if (line.isNullOrEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        requests += headers
        val out = s.getOutputStream()
        fun head(status: String, vararg h: String) = out.write(("HTTP/1.1 $status\r\n" + h.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        when {
            path.startsWith("/page") -> {
                val html = "<!doctype html><meta name=viewport content='width=device-width'><title>Download page test</title><a id=a href='/file?slow=1' target=_blank style='position:fixed;inset:0;display:block;background:#08f'>get</a>".toByteArray()
                head("200 OK", "Content-Type: text/html", "Content-Length: ${html.size}")
                out.write(html)
            }
            path.startsWith("/redirect") -> head("302 Found", "Location: /file", "Content-Length: 0")
            path.startsWith("/file") -> {
                // Signed-in only, like a GitHub artifact.
                if (headers["cookie"]?.contains("session=serene") != true) {
                    head("403 Forbidden", "Content-Length: 0")
                    return
                }
                val from = headers["range"]?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
                val common = arrayOf("Content-Type: application/octet-stream", "Content-Disposition: attachment; filename=\"serene-test.bin\"", "Accept-Ranges: bytes", "ETag: \"v1\"")
                if (from > 0) head("206 Partial Content", *common, "Content-Range: bytes $from-${file.size - 1}/${file.size}", "Content-Length: ${file.size - from}")
                else head("200 OK", *common, "Content-Length: ${file.size}")
                val slow = path.contains("slow=1")
                var i = from
                while (i < file.size) {
                    val n = minOf(16_384, file.size - i)
                    out.write(file, i, n)
                    i += n
                    if (slow) SystemClock.sleep(15)
                }
            }
            else -> head("404 Not Found", "Content-Length: 0")
        }
        out.flush()
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun bytesOf(app: SereneApp, d: Download): ByteArray =
        app.contentResolver.openInputStream(d.uri!!)!!.use { it.readBytes() }

    @Test
    fun tabOpenedForAFileShowsTheDownloadPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var app: SereneApp
            var tab: Tab? = null
            scenario.onActivity { a ->
                app = a.application as SereneApp
                CookieManager.getInstance().setCookie(base, "session=serene")
                tab = a.tabs.open("$base/redirect") // as when another app opens an artifact link
            }
            var d: Download? = null
            waitFor("the download to start") { d = app.downloads.items.firstOrNull { it.tabId == tab!!.id }; d != null }
            started += d!!
            waitFor("the download to finish, state ${d!!.state} ${d!!.error}") { d!!.state == DownloadState.Done }
            scenario.onActivity { assertTrue("the tab shows the download page", tab!!.downloadPage) }
            assertTrue(d!!.name, d!!.name.startsWith("serene-test"))
            assertEquals(file.size.toLong(), d!!.total)
            assertArrayEquals(file, bytesOf(app, d!!))

            // WebView hands over the URL after its redirects, so follow one directly: /file answers
            // 403 without the cookie, so finishing means the second hop carried it.
            var viaRedirect: Download? = null
            scenario.onActivity { viaRedirect = app.downloads.start("$base/redirect", "test", null, null, null, null) }
            started += viaRedirect!!
            waitFor("the redirected download, state ${viaRedirect!!.state} ${viaRedirect!!.error}") { viaRedirect!!.state == DownloadState.Done }
            assertArrayEquals(file, bytesOf(app, viaRedirect!!))
        }
    }

    @Test
    fun linkOnAPageKeepsThePageAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var app: SereneApp
            var tab: Tab? = null
            scenario.onActivity { a ->
                app = a.application as SereneApp
                CookieManager.getInstance().setCookie(base, "session=serene")
                tab = a.tabs.open("$base/page")
            }
            waitFor("the page to load") { tab!!.title == "Download page test" && tab!!.progress == 100 }
            // A real tap: a script click on a target=_blank link counts as a popup and is blocked.
            val xy = IntArray(2)
            var size = Pair(0, 0)
            scenario.onActivity { tab!!.webView.getLocationOnScreen(xy); size = Pair(tab!!.webView.width, tab!!.webView.height) }
            val x = xy[0] + size.first / 2f
            val y = xy[1] + size.second / 2f
            val down = SystemClock.uptimeMillis()
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            inst.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
            var d: Download? = null
            waitFor("the download to start") { d = app.downloads.items.firstOrNull { it.tabId == tab!!.id }; d != null }
            started += d!!
            scenario.onActivity {
                assertFalse("a link on a page keeps the page", tab!!.downloadPage)
                assertEquals(d, tab!!.downloadBar)
                assertEquals("Download page test", tab!!.title)
            }

            waitFor("some bytes") { d!!.received > 100_000 }
            scenario.onActivity { app.downloads.pause(d!!) }
            assertEquals(DownloadState.Paused, d!!.state)
            SystemClock.sleep(500)
            val atPause = d!!.received
            SystemClock.sleep(500)
            assertEquals("nothing arrives while paused", atPause, d!!.received)
            assertTrue("paused before the end", atPause < file.size)

            scenario.onActivity { app.downloads.resume(d!!) }
            waitFor("the download to finish, state ${d!!.state} ${d!!.error}") { d!!.state == DownloadState.Done }
            val resumed = requests.lastOrNull { it["path"]!!.startsWith("/file") }
            assertNotNull(resumed?.get("range"))
            assertEquals("\"v1\"", resumed!!["if-range"])
            assertArrayEquals(file, bytesOf(app, d!!))
        }
    }
}
