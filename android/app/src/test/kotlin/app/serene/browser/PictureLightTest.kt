package app.serene.browser

import app.serene.browser.PictureLight.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureLightTest {
    private val w = 100
    private val h = 100
    private val pic = Box(20, 20, 80, 80)

    // A screen of [page] with [fill] drawn over the picture's box.
    private fun screen(page: Int, fill: (x: Int, y: Int) -> Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (pic.has(x, y)) fill(x - pic.l, y - pic.t) else page
    }

    private fun dims(px: IntArray, seen: Box = pic) =
        PictureLight.dims(px, w, h, seen, pic, PictureLight.pageLuma(px, w, h, listOf(pic)), ring = 4)

    @Test fun whiteChartOnDarkPageIsDimmed() {
        // White paper with a dark line across: a chart.
        assertTrue(dims(screen(DARK) { _, y -> if (y == 30) BLACK else WHITE }))
    }

    @Test fun whiteChartOnLightPageStays() {
        assertFalse(dims(screen(WHITE) { _, y -> if (y == 30) BLACK else WHITE }))
    }

    @Test fun darkOrColorfulPicturesStay() {
        assertFalse(dims(screen(DARK) { _, _ -> 0xff203040.toInt() }))
        // A photo: a light sky over a third of it, the rest mid tones.
        assertFalse(dims(screen(DARK) { _, y -> if (y < 20) 0xffd8e8ff.toInt() else 0xff607050.toInt() }))
    }

    @Test fun brightPhotoIsDimmed() {
        // Mostly light, though little of it is white.
        assertTrue(dims(screen(DARK) { _, _ -> 0xffc0c8d0.toInt() }))
    }

    @Test fun pictureFillingTheScreenUsesThePageElsewhere() {
        // No edge on screen around it: the rest of the screen says what the page is.
        val full = Box(0, 0, 100, 60)
        val px = IntArray(w * h) { i -> if (i / w < 60) WHITE else DARK }
        val page = PictureLight.pageLuma(px, w, h, listOf(full))
        assertEquals(PictureLight.luma(DARK), page)
        assertTrue(PictureLight.dims(px, w, h, full, Box(-10, -50, 110, 60), page, ring = 0))
    }

    @Test fun luma() {
        assertEquals(255, PictureLight.luma(WHITE))
        assertEquals(0, PictureLight.luma(BLACK))
    }

    companion object {
        private const val WHITE = 0xffffffff.toInt()
        private const val BLACK = 0xff000000.toInt()
        private const val DARK = 0xff202328.toInt()
    }
}
