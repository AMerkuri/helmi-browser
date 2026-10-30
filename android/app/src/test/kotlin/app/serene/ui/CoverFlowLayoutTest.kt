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
            assertEquals(name, 68f * density, l.titleRoom, 1e-3f)
            assertTrue("$name: title goes under the tab count", l.centerY - l.cardHeight / 2 - l.titleRoom > 75f * density)
            // Card and reflection above the bottom buttons (about 10% of the height).
            assertTrue("$name: too tall", l.centerY + l.cardHeight * 0.8f < h * 0.9f)
            assertTrue("$name: too wide", l.cardWidth <= w * 0.6f)
            // The scrub handle: under the card, on screen above the buttons (56 dp over a 24 dp
            // navigation bar), with room to pull.
            val nav = 24f * density
            val withNav = CoverFlowLayout.of(w, h, aspect, density, nav)
            assertTrue("$name: handle over the card", withNav.handleY - 24f * density > l.centerY + l.cardHeight / 2)
            assertTrue("$name: handle over the buttons", withNav.handleY + 24f * density <= h - nav - 56f * density + 1e-3f)
            assertTrue("$name: handle too short", withNav.maxPull >= 60f * density)
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
        assertEquals("an ordinary quick swipe: still one", 3, settleOn(2f, 7f))
        assertEquals("hard flick", 4, settleOn(2f, 9f))
        assertEquals("harder flick", 5, settleOn(2f, 16f))
        assertEquals("very hard flick: 3 extra at most", 6, settleOn(2f, 100f))
        assertEquals(-4, settleOn(0f, -100f)) // the caller keeps it within the tabs
    }

    @Test
    fun theHandlePulledFurtherMovesFaster() {
        assertEquals("barely pulled: still", 0f, scrubRate(0.05f), 0f)
        assertEquals(0f, scrubRate(-0.1f), 0f)
        assertTrue("right moves on", scrubRate(0.3f) > 0f)
        assertTrue("left moves back", scrubRate(-0.3f) < 0f)
        assertEquals("all the way", 12f, scrubRate(1f), 1e-4f)
        assertEquals("further than the track is all the way", 12f, scrubRate(3f), 1e-4f)
        assertEquals(-12f, scrubRate(-1f), 1e-4f)
        var last = 0f
        var p = 0.13f
        while (p <= 1f) {
            val r = scrubRate(p)
            assertTrue("faster at $p", r > last)
            assertEquals("same both ways at $p", r, -scrubRate(-p), 1e-5f)
            last = r
            p += 0.01f
        }
        assertTrue("a short pull goes card by card", scrubRate(0.2f) < 2f)
    }

    @Test
    fun theHandleSettlesOnACardOn() {
        fun letGo(vararg moves: Pair<Float, Float>, at: Float) = ScrubTarget().apply { for ((a, r) in moves) moving(a, r) }.settle(at)
        assertEquals("never pulled far enough", 2, letGo(2f to 0f, at = 2f))
        assertEquals("a short tug: one card on", 3, letGo(2f to 1.5f, at = 2.1f))
        assertEquals("one card back", 1, letGo(2f to -1.5f, at = 1.9f))
        assertEquals("held: the card ahead", 6, letGo(2f to 5f, 5.5f to 5f, at = 5.5f))
        assertEquals("one only just come to the middle", 5, letGo(2f to 12f, at = 5.1f))
        assertEquals("back the other way", 5, letGo(2f to 5f, 5.5f to -2f, at = 5.4f))
        assertEquals("let go after easing off", 4, letGo(2f to 3f, 3.5f to 0f, at = 3.5f))
    }
}
