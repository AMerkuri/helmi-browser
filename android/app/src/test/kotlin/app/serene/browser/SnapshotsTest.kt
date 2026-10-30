package app.serene.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotsTest {
    @Test
    fun aPageThatDrewOnlyItsBackground() {
        val dark = 0xFF111111.toInt()
        assertTrue(oneColour(IntArray(144) { dark }))
        // Dithering and video compression: a step or two off.
        assertTrue(oneColour(IntArray(144) { if (it % 7 == 0) 0xFF131012.toInt() else dark }))
        // A line of text, a picture.
        assertFalse(oneColour(IntArray(144) { if (it == 80) 0xFFEDE7D6.toInt() else dark }))
        assertFalse(oneColour(IntArray(0)))
    }
}
