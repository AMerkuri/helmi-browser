package app.serene.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTextTest {
    @Test
    fun theTitleAndTheSite() =
        assertEquals(MediaText("A film", "m.youtube.com"), MediaText.of("  A film ", "https://m.youtube.com/watch?v=x", private = false))

    @Test
    fun noTitleSaysTheSite() =
        assertEquals(MediaText("err.ee", "err.ee"), MediaText.of("", "https://www.err.ee/123", private = false))

    @Test
    fun nothingKnownSaysHelmi() =
        assertEquals(MediaText("Helmi", "Helmi"), MediaText.of("", "about:blank", private = false))

    @Test
    fun aPrivateTabsPageStaysHidden() =
        assertEquals(MediaText("Playing in a private tab", "Helmi"), MediaText.of("Secret", "https://example.com/", private = true))
}
