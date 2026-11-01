package app.serene

import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Private tabs: their cookies stay in their own profile (a normal tab doesn't see them), their
 * pages stay out of the history, the saved tabs and the start page totals, the window can't be
 * captured while one is on screen, and closing the last one deletes the profile, so the next
 * private tab starts with no cookies.
 */
@RunWith(AndroidJUnit4::class)
class PrivateTabsTest {
    @Test
    fun privateTabsKeepNothing() {
        val app = ApplicationProvider.getApplicationContext<SereneApp>()
        var supported = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { supported = app.privacy.supported }
        Log.i("PrivateTabsTest", "WebView multi-profile: $supported")
        assumeTrue("WebView has no multi-profile support", supported)

        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val path = r.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    var cookie = "none"
                    while (true) {
                        val line = r.readLine()
                        if (line.isNullOrEmpty()) break
                        if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(':').trim()
                    }
                    // /set sets a cookie; every page's title is the cookie it was sent.
                    val extra = if (path.startsWith("/set")) "Set-Cookie: who=private; Path=/\r\n" else ""
                    val html = "<!doctype html><title>$cookie</title><p>$path".toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n${extra}Content-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        app.settings.savedTabs = emptyList()
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { app.history.clear() }
                var pagesBefore = 0L
                var priv: Tab? = null
                scenario.onActivity { a ->
                    pagesBefore = app.stats.totals.pages
                    priv = a.tabs.open("$base/set", isPrivate = true)
                }
                waitFor("the private page") { priv!!.title == "none" && priv!!.session != null }
                scenario.onActivity { a ->
                    assertTrue(priv!!.isPrivate)
                    assertTrue("FLAG_SECURE on a private tab", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    priv!!.load("$base/read?private")
                }
                waitFor("the private cookie") { priv!!.title == "who=private" }

                // A normal tab has none of the private tab's cookies.
                var normal: Tab? = null
                scenario.onActivity { a -> normal = a.tabs.open("$base/read?normal") }
                waitFor("the normal page") { normal!!.title.isNotEmpty() && normal!!.url.endsWith("?normal") && normal!!.progress == 100 && normal!!.session != null }
                scenario.onActivity { a ->
                    assertEquals("none", normal!!.title)
                    assertFalse("no FLAG_SECURE on a normal tab", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    // Only the normal tab's page is history, saved, and counted.
                    assertEquals(listOf("$base/read?normal"), app.history.entries.map { it.url })
                    assertEquals(pagesBefore + 1, app.stats.totals.pages)
                    a.tabs.save()
                    assertTrue(app.settings.savedTabs.none { "set" in it || "private" in it })
                }

                // The last private tab closes: its profile goes.
                scenario.onActivity { a -> a.tabs.close(priv!!, undo = false) }
                waitFor("the private profile deleted") { ProfileStore.getInstance().allProfileNames.none { it.startsWith("private-") } }

                // A new private tab starts afresh.
                var again: Tab? = null
                scenario.onActivity { a -> again = a.tabs.open("$base/read?again", isPrivate = true) }
                waitFor("the new private page") { again!!.url.endsWith("?again") && again!!.title.isNotEmpty() && again!!.progress == 100 }
                scenario.onActivity { assertEquals("none", again!!.title) }
            }
        } finally {
            server.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.history.clear() }
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
