package app.serene

import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The bottom bar takes the theme and its icons stand out from it, in both themes and when the
 * system theme changes while the app is open (uiMode is handled in place), on the start page and
 * on a loaded page. The icons used to default to black, invisible on the dark bar; the start
 * page's empty WebView turned the bars black.
 */
@RunWith(AndroidJUnit4::class)
class DarkThemeTest {
    private val uiMode get() = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UiModeManager::class.java)

    @Test
    fun barIconsStandOutInBothThemes() {
        assumeTrue("setApplicationNightMode needs API 31", Build.VERSION.SDK_INT >= 31)
        val html = "<!doctype html><title>Serene theme test</title><body style='margin:0;background:#fff'><p style='height:2000px'>text</p></body>".toByteArray()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        val failures = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                failures += checkThemes(scenario, "start page")
                var tab: app.serene.browser.Tab? = null
                scenario.onActivity { tab = it.tabs.current!!.apply { load("http://127.0.0.1:${server.localPort}/theme.html") } }
                val end = SystemClock.uptimeMillis() + 20_000
                while (SystemClock.uptimeMillis() < end && !(tab?.title == "Serene theme test" && tab?.progress == 100)) SystemClock.sleep(100)
                assertEquals("page did not load", "Serene theme test", tab?.title)
                failures += checkThemes(scenario, "loaded page")
            } finally {
                uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                server.close()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The tab switcher's light icons on its black, and the theme's icons once it closes, also when
     * the theme changed while it was open. It used to put back the icons it found when it opened:
     * the light theme's dark icons on the dark bar (and the other way round).
     */
    @Test
    fun barIconsFollowTheThemeAfterTheTabSwitcher() {
        assumeTrue("setApplicationNightMode needs API 31", Build.VERSION.SDK_INT >= 31)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for ((from, to) in listOf(false to true, true to false)) {
                    uiMode.setApplicationNightMode(if (from) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                    waitForIcons(scenario, "$from theme before the switcher", light = !from)
                    scenario.onActivity { it.open(Screen.Tabs) }
                    waitForIcons(scenario, "the switcher's black", light = false)
                    uiMode.setApplicationNightMode(if (to) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                    SystemClock.sleep(1000)
                    waitForIcons(scenario, "the switcher's black after the theme change", light = false)
                    scenario.onActivity { it.open(Screen.Browser) }
                    waitForIcons(scenario, "${if (to) "dark" else "light"} theme after the switcher", light = !to)
                }
            } finally {
                uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            }
        }
    }

    private fun waitForIcons(scenario: ActivityScenario<MainActivity>, where: String, light: Boolean) {
        var lightBars: Boolean? = null
        val end = SystemClock.uptimeMillis() + 10_000
        do {
            scenario.onActivity { a ->
                val bars = WindowInsetsControllerCompat(a.window, a.window.decorView)
                lightBars = bars.isAppearanceLightStatusBars.takeIf { it == bars.isAppearanceLightNavigationBars }
            }
            if (lightBars == light) return
            SystemClock.sleep(250)
        } while (SystemClock.uptimeMillis() < end)
        logScreenshot(where)
        assertEquals("light status and navigation bar appearance, $where", light, lightBars)
    }

    /** Light, dark, light again; the failures, if any. */
    private fun checkThemes(scenario: ActivityScenario<MainActivity>, where: String): List<String> = listOf(false, true, false).mapNotNull { dark ->
        uiMode.setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        val theme = if (dark) "dark" else "light"
        // Wait for the first frame (a cold start on the CI emulator takes a few seconds)
        // and for the config change to be redrawn: the bar's background takes the theme.
        val end = SystemClock.uptimeMillis() + 15_000
        var menu = menuButton(scenario)
        while ((menu == null || barIsDark(menu) != dark) && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(250)
            menu = menuButton(scenario)
        }
        try {
            assertNotNull("no frame of the bottom bar at $rect", menu)
            val bg = menu!!.getPixel(1, 1)
            assertEquals("bottom bar background %08x at $rect".format(bg), dark, barIsDark(menu))

            var lightBars: Boolean? = null
            scenario.onActivity { a -> lightBars = WindowInsetsControllerCompat(a.window, a.window.decorView).isAppearanceLightStatusBars }
            assertEquals("status bar icons", !dark, lightBars)

            var contrast = 0.0
            for (x in 0 until menu.width) for (y in 0 until menu.height) contrast = maxOf(contrast, Math.abs(luminance(menu.getPixel(x, y)) - luminance(bg)))
            assertTrue("menu icon blends into the bar (contrast $contrast)", contrast > 0.4)
            null
        } catch (e: AssertionError) {
            logScreenshot("$where, $theme")
            "$where, $theme theme: ${e.message}"
        }
    }

    private var rect = ""

    /**
     * The right quarter of the bottom bar, where the ⋮ menu button is: the bar is the bottom 52 dp
     * above the navigation bar. Null until the window is laid out. Read from a screenshot of
     * the display, which is what the user sees.
     */
    private fun menuButton(scenario: ActivityScenario<MainActivity>): Bitmap? {
        var r: Rect? = null
        scenario.onActivity { a ->
            // The empty start tab focuses the address bar; keep the keyboard out of the way.
            a.currentFocus?.clearFocus()
            val decor = a.window.decorView
            WindowCompat.getInsetsController(a.window, decor).hide(WindowInsetsCompat.Type.ime())
            val insets = ViewCompat.getRootWindowInsets(decor) ?: return@onActivity
            if (decor.width == 0) return@onActivity
            val bottom = decor.height - insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            val dp = a.resources.displayMetrics.density
            val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
            r = Rect(decor.width * 3 / 4, bottom - (50 * dp).toInt(), decor.width, bottom - (2 * dp).toInt()).apply { offset(origin[0], origin[1]) }
            val night = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            rect = "$r (window ${decor.width}x${decor.height}, uiMode night $night)"
        }
        val crop = r ?: return null
        val screen = screenshot() ?: return null
        if (crop.right > screen.width || crop.bottom > screen.height) return null
        return Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
    }

    private fun screenshot(): Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)

    /** The whole display as a base64 PNG in logcat (the e2e-output artifact), to see a failure. */
    private fun logScreenshot(label: String) {
        val png = ByteArrayOutputStream().also { screenshot()?.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("DarkThemeShot", "$label #$i $chunk") }
    }

    private fun barIsDark(b: Bitmap): Boolean? = b.getPixel(1, 1).takeIf { Color.alpha(it) == 255 }?.let { luminance(it) < 0.5 }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255
}
