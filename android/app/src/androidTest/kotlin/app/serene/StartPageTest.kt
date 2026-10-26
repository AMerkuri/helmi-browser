package app.serene

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
 * shows them. The start page goes to logcat as base64 'StartPageShot' lines.
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
                scenario.onActivity { it.tabs.current!!.load("$base/cookie-custom.html") }
                waitFor("the detector's cookie banner") { app.stats.totals.cleared["cookie_consent"] == 1L }
                scenario.onActivity { it.tabs.current!!.load("$base/cookie-in-html.html") }
                waitFor("the lists' cookie banner, ${app.stats.totals}") { app.stats.totals.cleared["cookie_consent"] == 2L }
                assertEquals(2L, app.stats.totals.pages)
                assertEquals(listOf("127.0.0.1" to 2L), app.stats.totals.busiestSites(3))

                // A blank tab focuses the address bar; the keyboard would cover the totals.
                scenario.onActivity { it.tabs.open() }
                SystemClock.sleep(1000)
                scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
                SystemClock.sleep(1000)
                // Compose builds its accessibility tree once something asks for it, so ask until it answers.
                var found = false
                val end = SystemClock.uptimeMillis() + 10_000
                while (!found && SystemClock.uptimeMillis() < end) {
                    found = inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("annoyances cleared")?.isNotEmpty() == true
                    if (!found) SystemClock.sleep(250)
                }
                val shot = inst.uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
                assertNotNull(shot)
                val png = ByteArrayOutputStream().also { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("StartPageShot", "start #$i $chunk") }
                assertTrue("the start page shows the totals", found)
            }
        } finally {
            server.close()
            app.stats.reset()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !ok()) SystemClock.sleep(100)
        assertTrue("timed out: $what", ok())
    }
}
