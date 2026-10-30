package app.serene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTest {
    private val buzzes = mutableListOf<Boolean>()
    private val threshold = Threshold { buzzes += it }

    @Test
    fun buzzesOncePerCrossing() {
        listOf(false, false, true, true, true, false, false, true).forEach(threshold::update)
        assertEquals(listOf(true, false, true), buzzes)
    }

    @Test
    fun staysQuietUnderThePoint() {
        repeat(5) { threshold.update(false) }
        assertTrue(buzzes.isEmpty())
    }

    @Test
    fun setIsSilent() {
        threshold.set(true)
        threshold.update(true)
        assertTrue(buzzes.isEmpty())
        threshold.update(false)
        assertEquals(listOf(false), buzzes)
        // A drag ended past the point; the next one starts under it again.
        threshold.update(true)
        threshold.set()
        threshold.update(false)
        assertEquals(listOf(false, true), buzzes)
    }
}
