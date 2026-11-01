package app.serene.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBarsTest {
    @Test
    fun theNewestScreenThatAsksDecides() {
        // Nothing on top: the app's theme.
        assertEquals(null, newestAsk(emptyList()))
        // The reader on the night page.
        assertEquals(true, newestAsk(listOf(true)))
        // The reader on paper, under the tab switcher's black.
        assertEquals(true, newestAsk(listOf(false, true)))
        // The switcher growing a card back into the page (no ask): the reader's paper again.
        assertEquals(false, newestAsk(listOf(false, null)))
        // The night reader gone while the switcher was over it, the switcher gone too: the theme's.
        assertEquals(null, newestAsk(listOf(null)))
    }
}
