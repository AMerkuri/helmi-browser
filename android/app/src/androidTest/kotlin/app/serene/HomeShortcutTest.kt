package app.serene

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.HomeShortcut
import app.serene.browser.HomeShortcutData.Source
import app.serene.browser.Tab
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * ⋮ › Add to home screen: the shortcut is drawn from the site's manifest (its maskable icon
 * first) or, with no icon to be had, the site's letter on its theme colour; and launching it
 * shows the tab already on the page instead of opening another.
 */
@RunWith(AndroidJUnit4::class)
class HomeShortcutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theManifestsMaskableIconAndShortName() {
        val server = serve()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val tab = currentTab(scenario)
                load(tab, "http://127.0.0.1:${server.localPort}/", "Harbour Ferry: today's sailings")
                val p = prepare(tab)
                assertEquals(Source.MANIFEST, p.source)
                assertEquals("Ferry", p.name)
                assertEquals("the maskable (red) icon, not the any (blue) one", Color.RED, p.icon.getPixel(p.icon.width / 2, p.icon.height / 2))
                // Its padding, past the shown part, is the icon's edge.
                assertEquals(Color.RED, p.icon.getPixel(1, 1))
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun noIconMeansTheSitesLetterOnItsThemeColour() {
        val server = serve()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val tab = currentTab(scenario)
                load(tab, "http://127.0.0.1:${server.localPort}/plain", "Ferry notes")
                val p = prepare(tab)
                assertNull(p.source)
                assertEquals("Ferry notes", p.name)
                assertEquals(Color.rgb(0x00, 0x44, 0x66), p.icon.getPixel(2, 2))
                // The letter (white on the dark colour) is in the middle somewhere.
                val px = IntArray(p.icon.width * p.icon.height).also { p.icon.getPixels(it, 0, p.icon.width, 0, 0, p.icon.width, p.icon.height) }
                assertTrue("no letter drawn", px.any { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 })
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun theShortcutShowsTheTabOnItsPage() {
        val server = serve()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val tab = currentTab(scenario)
                val url = "http://127.0.0.1:${server.localPort}/plain"
                load(tab, url, "Ferry notes")
                var count = 0
                scenario.onActivity { a ->
                    a.tabs.open() // another tab in front
                    count = a.tabs.tabs.size
                }
                launch(HomeShortcut.intent(inst.targetContext, url))
                waitFor("the shortcut's tab in front") { a -> a.tabs.current === tab }
                scenario.onActivity { a -> assertEquals("a tab was opened", count, a.tabs.tabs.size) }

                // A page no tab is on: a new tab.
                launch(HomeShortcut.intent(inst.targetContext, "http://127.0.0.1:${server.localPort}/"))
                waitFor("a new tab for the other page") { a -> a.tabs.tabs.size == count + 1 && a.tabs.current !== tab }
            } finally {
                server.close()
            }
        }
    }

    private lateinit var scenarioRef: ActivityScenario<MainActivity>

    private fun currentTab(scenario: ActivityScenario<MainActivity>): Tab {
        scenarioRef = scenario
        var tab: Tab? = null
        scenario.onActivity { tab = it.tabs.current }
        return tab!!
    }

    private fun launch(intent: Intent) {
        inst.targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun prepare(tab: Tab): HomeShortcut.Prepared = runBlocking {
        val density = inst.targetContext.resources.displayMetrics.density
        withContext(Dispatchers.Main) { HomeShortcut.prepare(tab, density) }
    }

    private fun waitFor(what: String, check: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            scenarioRef.onActivity { ok = check(it) }
            if (ok) return
            SystemClock.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun load(tab: Tab, url: String, title: String) {
        inst.runOnMainSync { tab.load(url) }
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end && !(tab.title == title && tab.progress == 100)) SystemClock.sleep(100)
        assertEquals("page did not load", title, tab.title)
    }

    private fun png(color: Int): ByteArray {
        val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * "/": a front page with a manifest naming an any (blue) and a maskable (red) icon, and an
     * apple-touch-icon (green). "/plain": a theme colour and nothing else; /favicon.ico is a 404.
     */
    private fun serve(): ServerSocket {
        val routes = mapOf(
            "/" to ("text/html" to (
                "<!doctype html><title>Harbour Ferry: today's sailings</title><link rel=manifest href='/app.webmanifest'>" +
                    "<link rel=apple-touch-icon href='/touch.png'><h1>Sailings</h1>"
                ).toByteArray()),
            "/app.webmanifest" to ("application/manifest+json" to (
                """{"name":"Harbour Ferry","short_name":"Ferry","icons":[""" +
                    """{"src":"/blue.png","sizes":"192x192","type":"image/png"},""" +
                    """{"src":"/red.png","sizes":"192x192","type":"image/png","purpose":"maskable"}]}"""
                ).toByteArray()),
            "/blue.png" to ("image/png" to png(Color.BLUE)),
            "/red.png" to ("image/png" to png(Color.RED)),
            "/touch.png" to ("image/png" to png(Color.GREEN)),
            "/plain" to ("text/html" to "<!doctype html><title>Ferry notes</title><meta name=theme-color content='#004466'><p>Notes</p>".toByteArray()),
        )
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val path = r.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringBefore('?')
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val route = routes[path]
                    s.getOutputStream().apply {
                        if (route == null) {
                            write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            write("HTTP/1.1 200 OK\r\nContent-Type: ${route.first}\r\nContent-Length: ${route.second.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(route.second)
                        }
                        flush()
                    }
                }
            }
        }
        return server
    }
}
