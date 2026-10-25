package app.serene

import android.app.UiModeManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bottom bar's icons stand out from the bar in both themes, also when the system theme
 * changes while the app is open (uiMode is handled in place). They used to default to black,
 * so in the dark theme they disappeared into the bar.
 */
@RunWith(AndroidJUnit4::class)
class DarkThemeTest {
    private val uiMode get() = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UiModeManager::class.java)

    @Test
    fun barIconsStandOutInBothThemes() {
        assumeTrue("setApplicationNightMode needs API 31", Build.VERSION.SDK_INT >= 31)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for (dark in listOf(false, true, false)) {
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
                    assertNotNull("no frame of the bottom bar at $rect", menu)
                    val bg = menu!!.getPixel(1, 1)
                    assertEquals("bottom bar background %08x at $rect in the $theme theme".format(bg), dark, barIsDark(menu))

                    var lightBars: Boolean? = null
                    scenario.onActivity { a -> lightBars = WindowInsetsControllerCompat(a.window, a.window.decorView).isAppearanceLightStatusBars }
                    assertEquals("status bar icons in the $theme theme", !dark, lightBars)

                    var contrast = 0.0
                    for (x in 0 until menu.width) for (y in 0 until menu.height) contrast = maxOf(contrast, Math.abs(luminance(menu.getPixel(x, y)) - luminance(bg)))
                    assertTrue("menu icon blends into the bar in the $theme theme (contrast $contrast)", contrast > 0.4)
                }
            } finally {
                uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            }
        }
    }

    private var rect = ""

    /**
     * The right quarter of the bottom bar, where the ⋮ menu button is: the bar is the bottom 52 dp
     * above the navigation bar. Null until the window is laid out and drawn.
     */
    private fun menuButton(scenario: ActivityScenario<MainActivity>): Bitmap? {
        var shot: Bitmap? = null
        val latch = CountDownLatch(1)
        scenario.onActivity { a ->
            // The empty start tab focuses the address bar; keep the keyboard out of the way.
            a.currentFocus?.clearFocus()
            val decor = a.window.decorView
            WindowCompat.getInsetsController(a.window, decor).hide(WindowInsetsCompat.Type.ime())
            val insets = ViewCompat.getRootWindowInsets(decor)
            if (decor.width == 0 || insets == null) return@onActivity latch.countDown()
            val bottom = decor.height - insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            val dp = a.resources.displayMetrics.density
            val r = Rect(decor.width * 3 / 4, bottom - (50 * dp).toInt(), decor.width, bottom - (2 * dp).toInt())
            val wv = a.tabs.current!!.webView.let { v -> IntArray(2).also { v.getLocationInWindow(it) }[1].let { "$it+${v.height}" } }
            rect = "$r (window ${decor.width}x${decor.height}, WebView y $wv)"
            val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window, r, bmp, { result ->
                if (result == PixelCopy.SUCCESS) shot = bmp
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy timed out", latch.await(10, TimeUnit.SECONDS))
        return shot
    }

    // An undrawn window copies as transparent black, so that is not a dark bar yet.
    private fun barIsDark(b: Bitmap): Boolean? = b.getPixel(1, 1).takeIf { Color.alpha(it) == 255 }?.let { luminance(it) < 0.5 }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255
}
