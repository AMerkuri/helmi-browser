package app.serene

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.Tab
import app.serene.browser.TabPreviews
import app.serene.ui.CoverFlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The tab switcher: each tab's card shows its page, a tap at the side brings that card to the
 * middle, a tap in the middle opens its tab, and pushing the middle card up closes its tab, which
 * the "Closed …" bar's Undo brings back. Three pages of one colour each, so the cards can be told apart on a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class CoverFlowTest {
    private val colours = mapOf("red" to "#e53935", "green" to "#43a047", "blue" to "#1e88e5")

    @Test
    fun flipOpenAndClose() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val name = r.readLine()?.split(' ')?.getOrNull(1)?.trim('/') ?: ""
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val html = "<!doctype html><title>$name</title><body style='margin:0;background:${colours[name] ?: "#fff"}'></body>".toByteArray()
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
                // Three tabs, red, green and blue; each is on screen before the next opens, so
                // leaving it takes its picture. Green has a page before it, to go back to.
                var green: Tab? = null
                for (name in colours.keys) {
                    var tab: Tab? = null
                    val url = "http://127.0.0.1:${server.localPort}/$name"
                    if (name == "green") {
                        scenario.onActivity { a -> tab = a.tabs.open("http://127.0.0.1:${server.localPort}/start") }
                        waitFor("green's first page loads") { tab?.title == "start" && tab?.progress == 100 }
                        scenario.onActivity { tab!!.load(url) }
                        green = tab
                    } else {
                        scenario.onActivity { a -> tab = if (name == "red") a.tabs.current!!.apply { load(url) } else a.tabs.open(url) }
                    }
                    waitFor("$name loads") { tab?.title == name && tab?.progress == 100 }
                    SystemClock.sleep(1500) // drawn
                }
                scenario.onActivity { a -> assertEquals(3, a.tabs.tabs.size) }

                openSwitcher(scenario)
                assertCards(scenario, "switcher open on blue", middle = "blue", left = "green")

                // A tap on the card to the left brings it to the middle.
                tap(scenario) { it.centerX - it.centerGap }
                SystemClock.sleep(1000)
                assertCards(scenario, "after a tap on green", middle = "green", left = "red")

                // A slow drag over most of a card and the cards settle on the next one, not half way.
                drag(scenario, cards = 0.7f)
                SystemClock.sleep(1000)
                assertCards(scenario, "after a drag to red", middle = "red", left = null)
                drag(scenario, cards = -0.7f)
                SystemClock.sleep(1000)
                assertCards(scenario, "after a drag back to green", middle = "green", left = "red")

                // A tap on the middle card opens its tab.
                tap(scenario) { it.centerX }
                waitFor("the switcher closes") { screen(scenario) == Screen.Browser }
                scenario.onActivity { a -> assertEquals("green", a.tabs.current!!.title) }

                // Pushing the middle card up closes its tab; blue comes to the middle.
                openSwitcher(scenario)
                swipeUp(scenario)
                waitFor("green closes") { var n = 0; scenario.onActivity { n = it.tabs.tabs.size }; n == 2 }
                SystemClock.sleep(1000)
                scenario.onActivity { a -> assertEquals(listOf("red", "blue"), a.tabs.tabs.map { it.title }) }
                assertCards(scenario, "after closing green", middle = "blue", left = "red")

                // "Closed green" with Undo: green comes back in its place, with its history.
                waitFor("the Closed green bar") { closedBar(scenario) == "Closed green" }
                shot("Closed bar")
                scenario.onActivity { a -> a.snackbar.currentSnackbarData!!.performAction() }
                waitFor("green comes back") { var n = 0; scenario.onActivity { n = it.tabs.tabs.size }; n == 3 }
                SystemClock.sleep(1000)
                scenario.onActivity { a ->
                    assertEquals(listOf("red", "green", "blue"), a.tabs.tabs.map { it.title })
                    assertTrue("the same tab, not a new one", a.tabs.tabs[1] === green)
                    assertTrue("green keeps its history", green!!.webView.canGoBack())
                    assertEquals(green, a.tabs.current)
                }
                assertCards(scenario, "after undo", middle = "green", left = "red")

                // Closed again, and the bar left to time out: then it is gone for good.
                swipeUp(scenario)
                waitFor("green closes again") { var n = 0; scenario.onActivity { n = it.tabs.tabs.size }; n == 2 }
                waitFor("the bar times out", timeoutMs = 15_000) { var gone = false; scenario.onActivity { gone = it.tabs.closed == null }; gone }
                assertEquals(null, closedBar(scenario))

                // Back returns to the tab the switcher was opened from, or the middle one if it is gone.
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitFor("back closes the switcher") { screen(scenario) == Screen.Browser }
                scenario.onActivity { a -> assertEquals("blue", a.tabs.current!!.title) }
            }
        } finally {
            server.close()
        }
    }

    private fun closedBar(scenario: ActivityScenario<MainActivity>): String? {
        var m: String? = null
        scenario.onActivity { m = it.snackbar.currentSnackbarData?.visuals?.message }
        return m
    }

    private fun shot(label: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
        assertNotNull("no screenshot", shot)
        logScreenshot(shot!!, label)
    }

    private fun screen(scenario: ActivityScenario<MainActivity>): Screen {
        var s = Screen.Browser
        scenario.onActivity { s = it.screen }
        return s
    }

    private fun openSwitcher(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.open(Screen.Tabs) }
        waitFor("the switcher opens") { screen(scenario) == Screen.Tabs }
        SystemClock.sleep(1500) // the page has shrunk into its card
    }

    /** The cards' places, in screen coordinates. */
    private fun layout(scenario: ActivityScenario<MainActivity>): Pair<CoverFlowLayout, IntArray> {
        var l: CoverFlowLayout? = null
        val origin = IntArray(2)
        scenario.onActivity { a ->
            val decor = a.window.decorView
            decor.getLocationOnScreen(origin)
            val page = a.pageBounds
            l = CoverFlowLayout.of(decor.width.toFloat(), decor.height.toFloat(), page.height / page.width)
        }
        return l!! to origin
    }

    private fun tap(scenario: ActivityScenario<MainActivity>, x: (CoverFlowLayout) -> Float) {
        val (l, o) = layout(scenario)
        val px = o[0] + x(l)
        val py = o[1] + l.centerY
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, px, py, 0))
    }

    /** Drags sideways by [cards] (to the right is positive), slowly, then holds still before letting go: no fling. */
    private fun drag(scenario: ActivityScenario<MainActivity>, cards: Float) {
        val (l, o) = layout(scenario)
        val dx = cards * l.perCard
        val x0 = o[0] + l.centerX - dx / 2
        val py = o[1] + l.centerY + l.cardHeight * 0.4f
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, py, 0))
        for (step in 1..30) inst.sendPointerSync(MotionEvent.obtain(t, t + step * 20L, MotionEvent.ACTION_MOVE, x0 + dx * step / 30f, py, 0))
        for (step in 31..40) inst.sendPointerSync(MotionEvent.obtain(t, t + step * 20L, MotionEvent.ACTION_MOVE, x0 + dx, py, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 41 * 20L, MotionEvent.ACTION_UP, x0 + dx, py, 0))
    }

    private fun swipeUp(scenario: ActivityScenario<MainActivity>) {
        val (l, o) = layout(scenario)
        val px = o[0] + l.centerX
        val y0 = o[1] + l.centerY
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, y0, 0))
        for (step in 1..12) inst.sendPointerSync(MotionEvent.obtain(t, t + step * 16L, MotionEvent.ACTION_MOVE, px, y0 - step * l.cardHeight / 12f * 0.8f, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 13 * 16L, MotionEvent.ACTION_UP, px, y0 - l.cardHeight * 0.8f, 0))
    }

    /**
     * The middle card shows [middle]'s page and the one to its left [left]'s (if any), with a reflection
     * under the middle one. The screenshot goes to logcat either way, to look at.
     */
    private fun assertCards(scenario: ActivityScenario<MainActivity>, label: String, middle: String, left: String?) {
        val (l, o) = layout(scenario)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
        assertNotNull("no screenshot", shot)
        logScreenshot(shot!!, label)
        fun at(x: Float, y: Float) = shot.getPixel((o[0] + x).toInt(), (o[1] + y).toInt())
        val mid = at(l.centerX, l.centerY)
        assertTrue("$label: middle card %08x is not $middle".format(mid), near(mid, colours.getValue(middle)))
        if (left != null) {
            val side = at(l.centerX - l.centerGap, l.centerY)
            assertTrue("$label: left card %08x is not $left".format(side), near(side, colours.getValue(left)))
        }
        // The reflection: the middle card's colour, darker, just under it.
        val refl = at(l.centerX, l.centerY + l.cardHeight / 2 + l.cardHeight * 0.03f)
        val c = Color.parseColor(colours.getValue(middle))
        assertTrue(
            "$label: no reflection under the middle card (%08x)".format(refl),
            refl != Color.BLACK && Color.red(refl) <= Color.red(c) && Color.green(refl) <= Color.green(c) && Color.blue(refl) <= Color.blue(c),
        )
        assertTrue("$label: switcher should hide pictures of itself", TabPreviews.covered)
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

    /** The display as a base64 PNG in logcat (the e2e-output artifact). */
    private fun logScreenshot(shot: Bitmap, label: String) {
        val png = ByteArrayOutputStream().also { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("CoverFlowShot", "$label #$i $chunk") }
    }
}
