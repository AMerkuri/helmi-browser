package app.serene.browser

import app.serene.browser.TabDiscard.Candidate
import app.serene.browser.TabDiscard.Pressure
import org.junit.Assert.assertEquals
import org.junit.Test

class TabDiscardTest {
    private val min = 60_000L
    private val now = 1000 * min

    private fun tabs(vararg idleMinutes: Long) = idleMinutes.mapIndexed { i, m -> Candidate(i + 1L, now - m * min) }

    @Test
    fun recentTabsKeepTheirPages() {
        assertEquals(emptyList<Long>(), TabDiscard.pick(tabs(1, 2, 5, 29), now, Pressure.NONE))
        assertEquals(emptyList<Long>(), TabDiscard.pick(emptyList(), now, Pressure.NONE))
    }

    @Test
    fun idleTabsGo() {
        assertEquals(listOf(2L, 4L), TabDiscard.pick(tabs(1, 30, 5, 90), now, Pressure.NONE).sorted())
    }

    @Test
    fun onlyTheFourShownLastStay() {
        // Shown 1..6 minutes ago, in a mixed order: the two shown longest ago go.
        val c = listOf(Candidate(10, now - 3 * min), Candidate(11, now - 6 * min), Candidate(12, now - 1 * min),
            Candidate(13, now - 5 * min), Candidate(14, now - 2 * min), Candidate(15, now - 4 * min))
        assertEquals(listOf(11L, 13L), TabDiscard.pick(c, now, Pressure.NONE).sorted())
    }

    @Test
    fun outOfSightOnlyTheLastShownStays() {
        assertEquals(listOf(2L, 3L), TabDiscard.pick(tabs(1, 2, 3), now, Pressure.BACKGROUND).sorted())
        // ...and only if it was shown lately.
        assertEquals(listOf(1L), TabDiscard.pick(tabs(10), now, Pressure.BACKGROUND))
    }

    @Test
    fun lowOnMemoryEveryBackgroundTabGoes() {
        assertEquals(listOf(1L, 2L, 3L), TabDiscard.pick(tabs(0, 1, 2), now, Pressure.LOW).sorted())
    }
}
