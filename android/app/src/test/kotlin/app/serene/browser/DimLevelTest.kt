package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class DimLevelTest {
    @Test fun fixedLevel() {
        assertEquals(0.7, dimLevel(true, 70, followsScreen = false, screen = 1f), 0.0)
        assertEquals(1.0, dimLevel(false, 70, followsScreen = false, screen = 0f), 0.0)
        // A phone that doesn't say its brightness: the level as set.
        assertEquals(0.7, dimLevel(true, 70, followsScreen = true, screen = null), 0.0)
    }

    @Test fun followsTheScreen() {
        // The slider in its lowest third: the level as set; in its top third: not dimmed.
        assertEquals(0.5, dimLevel(true, 50, true, screen = 0f), 0.0)
        assertEquals(0.5, dimLevel(true, 50, true, screen = 0.06f), 0.0) // slider ~28 %
        assertEquals(1.0, dimLevel(true, 50, true, screen = 0.5f), 0.0) // slider ~73 %
        assertEquals(1.0, dimLevel(true, 50, true, screen = 1f), 0.0)
        // Halfway on the slider (value ~0.22): halfway between, in 5 % steps.
        assertEquals(0.75, dimLevel(true, 50, true, screen = 0.5f.toDouble().pow(2.2).toFloat()), 0.0)
    }

    private fun Double.pow(x: Double) = Math.pow(this, x)
}
