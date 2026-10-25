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
                    SystemClock.sleep(1500) // config change, recomposition, a few frames
                    val theme = if (dark) "dark" else "light"
                    var lightBars: Boolean? = null
                    scenario.onActivity { a -> lightBars = WindowInsetsControllerCompat(a.window, a.window.decorView).isAppearanceLightStatusBars }
                    assertEquals("status bar icons in the $theme theme", !dark, lightBars)

                    val menu = menuButton(scenario)
                    val bg = luminance(menu.getPixel(1, 1))
                    assertEquals("bottom bar is ${if (dark) "dark" else "light"}", dark, bg < 0.5)
                    var contrast = 0.0
                    for (x in 0 until menu.width) for (y in 0 until menu.height) contrast = maxOf(contrast, Math.abs(luminance(menu.getPixel(x, y)) - bg))
                    assertTrue("menu icon blends into the bar in the $theme theme (contrast $contrast)", contrast > 0.4)
                }
            } finally {
                uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            }
        }
    }

    /** The right quarter of the bottom bar, where the ⋮ menu button is. The bar sits under the WebView. */
    private fun menuButton(scenario: ActivityScenario<MainActivity>): Bitmap {
        var shot: Bitmap? = null
        val latch = CountDownLatch(1)
        scenario.onActivity { a ->
            val v = a.tabs.current!!.webView
            val xy = IntArray(2).also { v.getLocationInWindow(it) }
            val dp = a.resources.displayMetrics.density
            val top = xy[1] + v.height + (4 * dp).toInt() // past the divider
            val width = a.window.decorView.width
            val rect = Rect(width * 3 / 4, top, width, top + (46 * dp).toInt())
            val bmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) shot = bmp
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy timed out", latch.await(10, TimeUnit.SECONDS))
        assertNotNull("PixelCopy failed", shot)
        return shot!!
    }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255
}
