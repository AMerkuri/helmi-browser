package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class FindInPageTest {
    @Test fun countLabel() {
        assertEquals("", countLabel("", 0, 0))
        assertEquals("", countLabel("", 0, 4))
        assertEquals("0/0", countLabel("ferry", 0, 0))
        assertEquals("1/3", countLabel("ferry", 0, 3))
        assertEquals("3/3", countLabel("ferry", 2, 3))
        // WebView's ordinal before it has picked a match, or one left from a longer list.
        assertEquals("1/3", countLabel("ferry", -1, 3))
        assertEquals("3/3", countLabel("ferry", 7, 3))
    }
}
