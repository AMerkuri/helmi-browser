package app.serene

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
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
 * A sideways drag on the bottom bar turns a cube to the tab beside the current one: left to the
 * next tab, right to the one before. Half way, the page turned away and the tab coming in share
 * the screen, each turned away from you about the edge between them. A short drag turns back, and
 * a tap on the bar is still a tap. Three pages of one colour each, to tell them apart.
 */
@RunWith(AndroidJUnit4::class)
class TabCubeTest {
    private val colours = mapOf("red" to "#e53935", "green" to "#43a047", "blue" to "#1e88e5")

    @Test
    fun turnBetweenTabs() {
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
                // Red, green and blue; each on screen before the next opens, so leaving it takes its picture.
                for (name in colours.keys) {
                    var tab: Tab? = null
                    scenario.onActivity { a ->
                        val url = "http://127.0.0.1:${server.localPort}/$name"
                        tab = if (name == "red") a.tabs.current!!.apply { load(url) } else a.tabs.open(url)
                    }
                    waitFor("$name loads") { tab?.title == name && tab?.progress == 100 }
                    SystemClock.sleep(1500) // drawn
                }
                // Green, in the middle, so there is a tab either side.
                scenario.onActivity { a -> a.tabs.select(a.tabs.tabs[1]) }
                SystemClock.sleep(1500)
                assertEquals("green", current(scenario))

                // Half way to the left: green turns away on the left, blue comes in on the right.
                // The drag starts on the tabs button, which must not open the switcher.
                drag(scenario, from = 0.68f, by = -0.5f) { shot, page ->
                    val y = page.center.y
                    assertTrue("left half should be green, is %08x".format(shot.at(page.left + page.width * 0.25f, y)), shot.at(page.left + page.width * 0.25f, y).isMostly("green"))
                    assertTrue("right half should be blue, is %08x".format(shot.at(page.left + page.width * 0.75f, y)), shot.at(page.left + page.width * 0.75f, y).isMostly("blue"))
                    // Turned away from you: the face's far side is shorter than the page, with black
                    // above it; turned towards you it would fill the page's height there.
                    val top = shot.at(page.left + page.width * 0.25f, page.top + page.height * 0.02f)
                    assertTrue("above the far side of the face should be black, is %08x".format(top), top.isDark())
                }
                waitFor("the cube turns to blue") { current(scenario) == "blue" }
                assertEquals(Screen.Browser, screen(scenario))
                SystemClock.sleep(1000)
                assertPage(scenario, "after the turn to blue", "blue")

                // Blue is the last tab: a drag left gives a little and turns back.
                drag(scenario, from = 0.5f, by = -0.5f)
                SystemClock.sleep(1000)
                assertEquals("blue", current(scenario))

                // Right, to the tab before it.
                drag(scenario, from = 0.3f, by = 0.5f)
                waitFor("the cube turns back to green") { current(scenario) == "green" }
                SystemClock.sleep(1000)
                assertPage(scenario, "after the turn back to green", "green")

                // A short drag turns back.
                drag(scenario, from = 0.5f, by = 0.15f)
                SystemClock.sleep(1000)
                assertEquals("green", current(scenario))
                assertPage(scenario, "after a short drag", "green")

                // A tap on the bar is a tap: the tabs button opens the switcher.
                tapBar(scenario, 0.68f)
                waitFor("the switcher opens") { screen(scenario) == Screen.Tabs }
            }
        } finally {
            server.close()
        }
    }

    private fun current(scenario: ActivityScenario<MainActivity>): String {
        var t = ""
        scenario.onActivity { t = it.tabs.current!!.title }
        return t
    }

    private fun screen(scenario: ActivityScenario<MainActivity>): Screen {
        var s = Screen.Browser
        scenario.onActivity { s = it.screen }
        return s
    }

    /** The page's place on screen, and the bottom bar's middle under it. */
    private fun page(scenario: ActivityScenario<MainActivity>): Pair<Rect, Float> {
        var page = Rect.Zero
        var barY = 0f
        scenario.onActivity { a ->
            val o = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
            page = a.pageBounds.translate(o[0].toFloat(), o[1].toFloat())
            barY = page.bottom + 27f * a.resources.displayMetrics.density
        }
        return page to barY
    }

    /**
     * Drags along the bottom bar from [from] of its width by [by] of it (to the right is positive),
     * slowly, and holds still before letting go: no fling. [held] gets a screenshot while the
     * finger is still down.
     */
    private fun drag(scenario: ActivityScenario<MainActivity>, from: Float, by: Float, held: ((Bitmap, Rect) -> Unit)? = null) {
        val (page, y) = page(scenario)
        val x0 = page.left + page.width * from
        val dx = page.width * by
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (step in 1..30) inst.sendPointerSync(MotionEvent.obtain(t, t + step * 20L, MotionEvent.ACTION_MOVE, x0 + dx * step / 30f, y, 0))
        for (step in 31..40) inst.sendPointerSync(MotionEvent.obtain(t, t + step * 20L, MotionEvent.ACTION_MOVE, x0 + dx, y, 0))
        if (held != null) {
            SystemClock.sleep(300)
            val shot = screenshot("held: from $from by $by")
            held(shot, page)
        }
        val up = SystemClock.uptimeMillis().coerceAtLeast(t + 41 * 20L)
        inst.sendPointerSync(MotionEvent.obtain(t, up, MotionEvent.ACTION_UP, x0 + dx, y, 0))
    }

    private fun tapBar(scenario: ActivityScenario<MainActivity>, at: Float) {
        val (page, y) = page(scenario)
        val x = page.left + page.width * at
        val t = SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** The page shows [colour] all across, with no cube over it. */
    private fun assertPage(scenario: ActivityScenario<MainActivity>, label: String, colour: String) {
        val (page, _) = page(scenario)
        val shot = screenshot(label)
        for (fx in listOf(0.1f, 0.5f, 0.9f)) for (fy in listOf(0.05f, 0.5f, 0.95f)) {
            val p = shot.at(page.left + page.width * fx, page.top + page.height * fy)
            val c = Color.parseColor(colours.getValue(colour))
            val near = Math.abs(Color.red(p) - Color.red(c)) < 40 && Math.abs(Color.green(p) - Color.green(c)) < 40 && Math.abs(Color.blue(p) - Color.blue(c)) < 40
            assertTrue("$label: page at $fx, $fy is %08x, not $colour".format(p), near)
        }
    }

    private fun Bitmap.at(x: Float, y: Float) = getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))

    /** The page's colour, maybe darkened: its own channel is the strongest. */
    private fun Int.isMostly(colour: String): Boolean {
        val r = Color.red(this)
        val g = Color.green(this)
        val b = Color.blue(this)
        return when (colour) {
            "red" -> r > g + 30 && r > b + 30
            "green" -> g > r + 30 && g > b + 30
            else -> b > r + 30 && b > g + 30
        }
    }

    private fun Int.isDark() = Color.red(this) < 30 && Color.green(this) < 30 && Color.blue(this) < 30

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !ok()) SystemClock.sleep(100)
        assertTrue("timed out: $what", ok())
    }

    /** The display, also as a base64 PNG in logcat (the e2e-output artifact). */
    private fun screenshot(label: String): Bitmap {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
        assertNotNull("no screenshot", shot)
        val png = ByteArrayOutputStream().also { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("TabCubeShot", "$label #$i $chunk") }
        return shot!!
    }
}
