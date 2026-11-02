package app.serene.browser

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun onlyAPlayingVideoGoesAlongWhenLeaving() {
        val film = FullscreenMedia(IntSize(1920, 1080), playing = true, pipAllowed = true)
        assertTrue(autoPictureInPicture(film))
        assertFalse(autoPictureInPicture(film.copy(playing = false)))
        assertFalse(autoPictureInPicture(film.copy(pipAllowed = false))) // disablePictureInPicture
        assertFalse(autoPictureInPicture(null)) // a game, or a video that said nothing yet
    }

    @Test
    fun theWindowTakesTheVideosShape() {
        assertEquals(1920 to 1080, pipAspect(IntSize(1920, 1080)))
        assertEquals(1080 to 1920, pipAspect(IntSize(1080, 1920)))
        assertEquals(16 to 9, pipAspect(null))
        assertEquals(16 to 9, pipAspect(IntSize(0, 0)))
    }

    @Test
    fun aVeryWideOrTallVideoGetsAndroidsLimit() {
        // Android throws for anything past 2.39:1 either way.
        val (w, h) = pipAspect(IntSize(3000, 1000))
        assertTrue(w.toDouble() / h <= 2.39)
        assertTrue(w.toDouble() / h > 2.3)
        val (tw, th) = pipAspect(IntSize(1000, 3000))
        assertTrue(th.toDouble() / tw <= 2.39)
        assertEquals(2560 to 1080, pipAspect(IntSize(2560, 1080))) // 21:9 cinema fits
    }

    @Test
    fun theVideoSitsInTheMiddleOfTheBox() {
        // A landscape video on a portrait phone: bars above and below.
        assertEquals(IntRect(0, 840, 1080, 1447), letterbox(IntRect(0, 0, 1080, 2287), IntSize(1920, 1080)))
        // A portrait video on a landscape screen, the cutout's 100 px left black on the left.
        assertEquals(IntRect(840, 0, 1447, 1080), letterbox(IntRect(100, 0, 2187, 1080), IntSize(1080, 1920)))
        // Unknown size: the whole box.
        assertEquals(IntRect(0, 0, 10, 10), letterbox(IntRect(0, 0, 10, 10), IntSize(0, 0)))
    }
}
