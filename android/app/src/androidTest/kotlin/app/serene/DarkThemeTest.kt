package app.serene

import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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

/**
 * The bottom bar takes the theme and its icons stand out from it, in both themes and when the
 * system theme changes while the app is open (uiMode is handled in place). The icons used to
 * default to black, invisible on the dark bar; a first fix turned the bar itself black.
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
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        if (crop.right > screen.width || crop.bottom > screen.height) return null
        return Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
    }

    private fun barIsDark(b: Bitmap): Boolean? = b.getPixel(1, 1).takeIf { Color.alpha(it) == 255 }?.let { luminance(it) < 0.5 }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255
}
