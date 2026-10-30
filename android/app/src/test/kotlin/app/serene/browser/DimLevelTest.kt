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

    @Test fun followsTheScreenOnly() {
        // 30 % at the lowest screen brightness, 90 % at the highest, whatever the level set.
        assertEquals(0.3, dimLevel(true, 70, true, screen = 0f), 0.0)
        assertEquals(0.3, dimLevel(true, 50, true, screen = 0f), 0.0)
        assertEquals(0.9, dimLevel(true, 50, true, screen = 1f), 0.0)
        // Halfway on the brightness slider (value ~0.22): halfway between.
        assertEquals(0.6, dimLevel(true, 50, true, screen = Math.pow(0.5, 2.2).toFloat()), 0.0)
        assertEquals(1.0, dimLevel(false, 50, true, screen = 0f), 0.0)
    }
}
