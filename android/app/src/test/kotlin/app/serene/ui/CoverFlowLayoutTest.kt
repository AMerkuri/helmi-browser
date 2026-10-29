package app.serene.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowLayoutTest {
    // The Galaxy Z Fold 7's screens in px, with the page's share of the height in each.
    private val density = 2.625f
    private val screens = mapOf(
        "cover screen" to Triple(1080f, 2520f, 2040f / 1080f),
        "inner screen" to Triple(2184f, 1968f, 1500f / 2184f),
        "inner screen, landscape" to Triple(1968f, 2184f, 1716f / 1968f),
        "cover screen, landscape" to Triple(2520f, 1080f, 640f / 2520f),
    )

    @Test
    fun cardAndReflectionFitAboveTheButtons() {
        for ((name, s) in screens) {
            val (w, h, aspect) = s
            val l = CoverFlowLayout.of(w, h, aspect, density)
            assertEquals(name, aspect, l.cardHeight / l.cardWidth, 1e-4f)
            // The title above the card, under the tab count (status bar and about 60 dp).
            assertEquals(name, 56f * density, l.titleRoom, 1e-3f)
            assertTrue("$name: title goes under the tab count", l.centerY - l.cardHeight / 2 - l.titleRoom > 75f * density)
            // Card and reflection above the bottom buttons (about 10% of the height).
            assertTrue("$name: too tall", l.centerY + l.cardHeight * 0.8f < h * 0.9f)
            assertTrue("$name: too wide", l.cardWidth <= w * 0.6f)
        }
    }

    @Test
    fun cardsLeaveTheMiddleCardClearAndStackOutwards() {
        val l = CoverFlowLayout.of(1080f, 2520f, 2040f / 1080f, density)
        assertEquals(0f, l.x(0f), 0f)
        assertEquals(l.centerGap, l.x(1f), 1e-3f)
        assertEquals(-l.centerGap, l.x(-1f), 1e-3f)
        // Moves the same way all along, without a jump where the stack starts.
        var last = l.x(-6f)
        var d = -6f
        while (d <= 6f) {
            val x = l.x(d)
            assertTrue("x($d)", x >= last)
            assertTrue("jump at $d", x - last < l.centerGap * 0.02f + 1e-3f)
            last = x
            d += 0.01f
        }
        // The middle card's centre is clear of its neighbours'.
        assertTrue(l.centerGap > l.cardWidth / 2)
    }

    @Test
    fun aFlickMovesOneCardOnlyAFastOneMore() {
        assertEquals("let go still: nearest card", 2, settleOn(2.4f, 0f))
        assertEquals(3, settleOn(2.6f, 0.5f))
        assertEquals("slight flick on", 3, settleOn(2.1f, 2f))
        assertEquals("slight flick back", 1, settleOn(1.9f, -2f))
        assertEquals("from a card exactly", 3, settleOn(2f, 4f))
        assertEquals("an ordinary quick swipe: still one", 3, settleOn(2f, 8f))
        assertEquals("hard flick", 4, settleOn(2f, 12f))
        assertEquals("very hard flick: 3 extra at most", 6, settleOn(2f, 100f))
        assertEquals(-4, settleOn(0f, -100f)) // the caller keeps it within the tabs
    }
}
