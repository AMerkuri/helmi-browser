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
            // The tab scrollbar: under the card, on screen above the buttons (56 dp over a 24 dp
            // navigation bar), long enough to pick a tab by its place.
            val nav = 24f * density
            val withNav = CoverFlowLayout.of(w, h, aspect, density, nav)
            assertTrue("$name: handle over the card", withNav.handleY - 24f * density > l.centerY + l.cardHeight / 2)
            assertTrue("$name: handle over the buttons", withNav.handleY + 24f * density <= h - nav - 56f * density + 1e-3f)
            assertTrue("$name: scrollbar too short", withNav.handleWidth >= 200f * density)
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
    fun theKnobIsATabsShareOfTheTrack() {
        val track = 280f * density
        assertEquals("few tabs: 64 dp, to move a long way", 64f * density, knobWidth(track, 2, density), 1e-3f)
        assertEquals(64f * density, knobWidth(track, 4, density), 1e-3f)
        assertEquals("a tab's share", track / 7, knobWidth(track, 7, density), 1e-3f)
        assertEquals("many tabs: still a fingertip's width", 24f * density, knobWidth(track, 40, density), 1e-3f)
        assertEquals("never wider than the track", 10f, knobWidth(10f, 2, density), 1e-3f)
    }

    @Test
    fun theKnobShowsWhereTheMiddleCardIs() {
        val track = 700f
        val knob = 100f
        assertEquals("first tab: left end", 50f, knobCenter(0f, 5f, track, knob), 1e-3f)
        assertEquals("last tab: right end", 650f, knobCenter(4f, 5f, track, knob), 1e-3f)
        assertEquals("the middle tab of 5", 350f, knobCenter(2f, 5f, track, knob), 1e-3f)
        assertEquals("between two while the cards move", 200f, knobCenter(1f, 5f, track, knob), 1e-3f)
        assertEquals("past the ends (the flow giving way) stays on the track", 50f, knobCenter(-0.3f, 5f, track, knob), 1e-3f)
        assertEquals(650f, knobCenter(4.3f, 5f, track, knob), 1e-3f)
        assertEquals("one tab: in the middle", 350f, knobCenter(0f, 1f, track, knob), 1e-3f)
    }

    @Test
    fun draggingTheKnobGoesToTheTabAtThatPlace() {
        val track = 700f
        val knob = 100f
        for (count in 2..30) for (i in 0 until count) {
            assertEquals("tab $i of $count there and back", i.toFloat(), scrollAt(knobCenter(i.toFloat(), count.toFloat(), track, knob), count, track, knob), 1e-3f)
        }
        assertEquals("off the left end: the first tab", 0f, scrollAt(-40f, 5, track, knob), 0f)
        assertEquals("off the right end: the last tab", 4f, scrollAt(900f, 5, track, knob), 0f)
        assertEquals("a tab for each step of a knob's width with 7 tabs", 1f, scrollAt(50f + track / 7, 7, track, track / 7), 1e-3f)
        assertEquals("one tab", 0f, scrollAt(350f, 1, track, knob), 0f)
    }
}
