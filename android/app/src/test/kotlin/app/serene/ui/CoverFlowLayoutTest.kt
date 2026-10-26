package app.serene.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowLayoutTest {
    // The Galaxy Z Fold 7's screens in px, with the page's share of the height in each.
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
            val l = CoverFlowLayout.of(w, h, aspect)
            assertEquals(name, aspect, l.cardHeight / l.cardWidth, 1e-4f)
            assertTrue("$name: card goes off the top", l.centerY - l.cardHeight / 2 > h * 0.05f)
            // Card, reflection and the title under it, above the bottom buttons (about 10% of the height).
            assertTrue("$name: too tall", l.centerY + l.cardHeight * 0.85f < h * 0.9f)
            assertTrue("$name: too wide", l.cardWidth <= w * 0.6f)
        }
    }

    @Test
    fun cardsLeaveTheMiddleCardClearAndStackOutwards() {
        val l = CoverFlowLayout.of(1080f, 2520f, 2040f / 1080f)
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
}
