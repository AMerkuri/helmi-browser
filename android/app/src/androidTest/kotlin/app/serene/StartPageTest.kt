package app.serene

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The totals on the start page: two fixture pages with a cookie banner each, one cleared by the
 * detector and one hidden by the lists, count as two cookie banners on two pages, and a new tab
 * shows them where the empty start page had only a line of text.
 */
@RunWith(AndroidJUnit4::class)
class StartPageTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as SereneApp

    @Test
    fun totalsCountAndShow() {
        val assets = inst.context.assets
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val name = r.readLine()?.split(' ')?.getOrNull(1)?.trim('/') ?: ""
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val body = runCatching { assets.open(name).readBytes() }.getOrDefault(ByteArray(0))
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        // The list-hidden banner needs the lists and autoconsent (it is parked for autoconsent).
        waitFor("filter lists: ${app.rulesStatus}", 120_000) { app.engine.rules != null && app.engine.autoconsent != null }
        app.engine.cache.clear()
        app.stats.reset()
        app.settings.savedTabs = emptyList()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val base = "http://127.0.0.1:${server.localPort}"
                // Before anything is counted, the start page has only a line of text under its header.
                hideKeyboard(scenario)
                val empty = shot("empty")

                scenario.onActivity { it.tabs.current!!.load("$base/cookie-custom.html") }
                waitFor("the detector's cookie banner") { app.stats.totals.cleared["cookie_consent"] == 1L }
                scenario.onActivity { it.tabs.current!!.load("$base/cookie-in-html.html") }
                waitFor("the lists' cookie banner, ${app.stats.totals}") { app.stats.totals.cleared["cookie_consent"] == 2L }
                assertEquals(2L, app.stats.totals.pages)
                assertEquals(listOf("127.0.0.1" to 2L), app.stats.totals.busiestSites(3))

                scenario.onActivity { it.tabs.open() }
                hideKeyboard(scenario)
                val totals = shot("totals")
                // Where the line was, the tiles are now: much of that part of the screen changed.
                var changed = 0
                var all = 0
                for (y in (empty.height * 45 / 100) until (empty.height * 80 / 100) step 4) {
                    for (x in 0 until empty.width step 4) {
                        all++
                        val a = empty.getPixel(x, y)
                        val b = totals.getPixel(x, y)
                        if (Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)) > 30) changed++
                    }
                }
                assertTrue("the start page should show the totals: $changed of $all pixels changed", changed > all / 10)
            }
        } finally {
            server.close()
            app.stats.reset()
        }
    }

    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        SystemClock.sleep(500) // a blank tab focuses the address bar, and the keyboard comes up
        scenario.onActivity { a -> WindowCompat.getInsetsController(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        waitFor("the keyboard closes") {
            var ime = true
            scenario.onActivity { a -> ime = ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) ?: true }
            !ime
        }
        SystemClock.sleep(1500) // drawn
    }

    /** The display, also as a base64 PNG in logcat ('StartPageShot' lines in the e2e-output artifact). */
    private fun shot(label: String): Bitmap {
        val bmp = inst.uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
        assertNotNull("no screenshot", bmp)
        val png = ByteArrayOutputStream().also { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("StartPageShot", "$label #$i $chunk") }
        return bmp!!
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !ok()) SystemClock.sleep(100)
        assertTrue("timed out: $what", ok())
    }
}
