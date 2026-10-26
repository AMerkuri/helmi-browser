package app.serene.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageImagesTest {
    @Test fun dataUrls() {
        val (png, pngType) = PageImages.decodeData("data:image/png;base64,iVBORw0KGgo=")
        assertEquals("image/png", pngType)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10), png)

        // Percent-encoded, with a + that stays a +.
        val (svg, svgType) = PageImages.decodeData("data:image/svg+xml;charset=utf-8,%3Csvg%20a='1+2'%2F%3E")
        assertEquals("image/svg+xml", svgType)
        assertEquals("<svg a='1+2'/>", String(svg))

        assertEquals("text/plain", PageImages.decodeData("data:,hello").second)
    }

    @Test fun whatTheMenuTakes() {
        assertTrue(isWebLink("HTTPS://example.com/a"))
        assertFalse(isWebLink("javascript:void(0)"))
        assertFalse(isWebLink("mailto:a@example.com"))
        assertTrue(isImageSrc("data:image/png;base64,AAAA"))
        assertFalse(isImageSrc("blob:https://example.com/1234"))
        assertFalse(isImageSrc("data:text/html,<b>hi</b>"))
    }
}
