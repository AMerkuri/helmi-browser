package app.serene.browser

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenTest {
    private val phone = 411 // dp, the Fold's cover screen and most phones
    private val foldInner = 841

    @Test
    fun aLandscapeVideoTurnsAPhoneToLandscape() =
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, fullscreenOrientation(IntSize(1920, 1080), phone))

    @Test
    fun aPortraitVideoTurnsAPhoneToPortrait() =
        assertEquals(SCREEN_ORIENTATION_SENSOR_PORTRAIT, fullscreenOrientation(IntSize(1080, 1920), phone))

    @Test
    fun aBigScreenStaysAsItIs() {
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, fullscreenOrientation(IntSize(1920, 1080), foldInner))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, fullscreenOrientation(IntSize(1080, 1920), 600))
    }

    @Test
    fun noVideoOrASquareOneStaysAsItIs() {
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, fullscreenOrientation(null, phone))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, fullscreenOrientation(IntSize(720, 720), phone))
    }
}
