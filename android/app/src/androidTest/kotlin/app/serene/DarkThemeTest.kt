package app.serene

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system bar icons follow the theme when it changes while the app is open (uiMode is handled
 * in place, so nothing recreates the activity). They used to keep the launch theme: dark icons on
 * a dark bar after switching to dark.
 *
 * The bottom bar's own icons are not checked here: on the CI emulator the app chrome around the
 * WebView reads as black in both a PixelCopy and a display screenshot, whatever the theme.
 */
@RunWith(AndroidJUnit4::class)
class DarkThemeTest {
    private val uiMode get() = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UiModeManager::class.java)

    @Test
    fun systemBarIconsFollowTheTheme() {
        assumeTrue("setApplicationNightMode needs API 31", Build.VERSION.SDK_INT >= 31)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for (dark in listOf(false, true, false)) {
                    uiMode.setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                    val end = SystemClock.uptimeMillis() + 10_000
                    var night: Boolean? = null
                    var lightBars: Boolean? = null
                    while (SystemClock.uptimeMillis() < end) {
                        scenario.onActivity { a ->
                            night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            lightBars = WindowInsetsControllerCompat(a.window, a.window.decorView).isAppearanceLightStatusBars
                        }
                        if (night == dark && lightBars == !dark) break
                        SystemClock.sleep(250)
                    }
                    val theme = if (dark) "dark" else "light"
                    assertEquals("activity in the $theme theme", dark, night)
                    assertEquals("status bar icons in the $theme theme", !dark, lightBars)
                }
            } finally {
                uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            }
        }
    }
}
