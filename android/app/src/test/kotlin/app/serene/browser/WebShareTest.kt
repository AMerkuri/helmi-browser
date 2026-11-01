package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebShareTest {
    @Test
    fun textThenLink() =
        assertEquals("Look at this https://example.com/a", shareBody(SharedText("Title", "Look at this", "https://example.com/a")))

    @Test
    fun aLinkAlreadyInTheTextIsNotRepeated() =
        assertEquals("Read https://example.com/a today", shareBody(SharedText("", "Read https://example.com/a today", "https://example.com/a")))

    @Test
    fun onlyALinkOrOnlyText() {
        assertEquals("https://example.com/", shareBody(SharedText("Example", "", "https://example.com/")))
        assertEquals("hello", shareBody(SharedText("", "  hello ", "")))
    }

    @Test
    fun aTitleAloneSharesNothing() = assertEquals("", shareBody(SharedText("Only a title", "", "")))

    @Test
    fun hugeTextIsCut() = assertEquals(MAX_SHARE, shareBody(SharedText("", "x".repeat(MAX_SHARE + 10), "")).length)

    @Test
    fun aFrameOfThePagesOriginMayShare() {
        assertTrue(sameOrigin("https://example.com", "https://example.com/post/1?x=2"))
        assertTrue(sameOrigin("https://Example.com:443", "https://example.com/"))
        assertTrue(sameOrigin("http://localhost:8080", "http://localhost:8080/page"))
    }

    @Test
    fun otherFramesMayNot() {
        assertFalse(sameOrigin("https://ads.example.net", "https://example.com/"))
        assertFalse(sameOrigin("https://sub.example.com", "https://example.com/"))
        assertFalse(sameOrigin("http://example.com", "https://example.com/"))
        assertFalse(sameOrigin("https://example.com:8443", "https://example.com/"))
        assertFalse(sameOrigin("null", "https://example.com/"))
        assertFalse(sameOrigin("https://example.com", ""))
    }
}
