package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStepTest {
    @Test
    fun stepsThatAddUpStayInTheHistory() {
        assertEquals(-1, historyStep(index = 2, size = 3, dir = -1))
        assertEquals(0, historyStep(index = 2, size = 3, dir = 1)) // already the last page
        assertEquals(-2, historyStep(index = 2, size = 3, dir = -5)) // back, back, back... to the first
        assertEquals(1, historyStep(index = 0, size = 2, dir = 3))
        assertEquals(0, historyStep(index = 0, size = 0, dir = -1)) // nothing loaded yet
    }

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
