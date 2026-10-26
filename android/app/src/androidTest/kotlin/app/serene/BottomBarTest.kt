package app.serene

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The bottom bar: back, forward, new tab, tabs, menu. The ⋮ menu stands on the bar, with no page
 * showing between them. The page is one colour, so a screenshot tells it from the menu.
 */
@RunWith(AndroidJUnit4::class)
class BottomBarTest {
    private val red = "#e53935"

    @Test
    fun menuStandsOnTheBarAndNewTabOpensATab() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val html = "<!doctype html><title>red</title><body style='margin:0;height:300vh;background:$red'></body>".toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        ApplicationProvider.getApplicationContext<SereneApp>().settings.savedTabs = emptyList()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var tab: Tab? = null
                scenario.onActivity { a -> tab = a.tabs.current!!.apply { load("http://127.0.0.1:${server.localPort}/red") } }
                waitFor("the page loads") { tab?.title == "red" && tab?.progress == 100 }
                SystemClock.sleep(1500) // drawn

                val bar = bar(scenario)
                // Just above the bar at the menu's end, and at the other end, where the menu isn't.
                val underMenu = bar.width - bar.dp * 24
                val clear = bar.width * 0.1f
                val above = bar.top - bar.dp * 3
                assertTrue("page should show above the bar", near(shot("menu closed").at(underMenu, above), red))

                tap(underMenu, bar.top + bar.dp * 26)
                SystemClock.sleep(800) // opened
                val open = shot("menu open")
                val menu = open.at(underMenu, above)
                assertTrue("gap between the menu and the bar: %08x is the page".format(menu), !near(menu, red))
                assertTrue("the menu should only cover its end of the page", near(open.at(clear, above), red))

                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(800) // closed
                assertTrue("back should close the menu", near(shot("menu closed by back").at(underMenu, above), red))
                scenario.onActivity { a -> assertEquals("back should only close the menu", 1, a.tabs.tabs.size) }

                // New tab is the middle of five.
                tap(bar.width * 0.5f, bar.top + bar.dp * 26)
                waitFor("a new tab opens") { var n = 0; scenario.onActivity { n = it.tabs.tabs.size }; n == 2 }
                scenario.onActivity { a -> assertEquals(1, a.tabs.currentIndex) }
            }
        } finally {
            server.close()
        }
    }

    private class Bar(val top: Float, val width: Float, val dp: Float)

    /** Where the bar starts on screen: the page's bottom and the divider under it. */
    private fun bar(scenario: ActivityScenario<MainActivity>): Bar {
        var b: Bar? = null
        scenario.onActivity { a ->
            val o = IntArray(2)
            a.window.decorView.getLocationOnScreen(o)
            val dp = a.resources.displayMetrics.density
            b = Bar(o[1] + a.pageBounds.bottom + dp, o[0] + a.window.decorView.width.toFloat(), dp)
        }
        return b!!
    }

    private class Shot(val bitmap: Bitmap) {
        fun at(x: Float, y: Float) = bitmap.getPixel(x.toInt(), y.toInt())
    }

    /** A screenshot of the display, also logged as base64 PNG (the e2e-output artifact) to look at. */
    private fun shot(label: String): Shot {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
        assertNotNull("no screenshot", bitmap)
        val png = ByteArrayOutputStream().also { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("BottomBarShot", "$label #$i $chunk") }
        return Shot(bitmap!!)
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun near(pixel: Int, colour: String): Boolean {
        val c = Color.parseColor(colour)
        return Math.abs(Color.red(pixel) - Color.red(c)) < 40 && Math.abs(Color.green(pixel) - Color.green(c)) < 40 && Math.abs(Color.blue(pixel) - Color.blue(c)) < 40
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !ok()) SystemClock.sleep(100)
        assertTrue("timed out: $what", ok())
    }
}
