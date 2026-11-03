package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBackgroundTest {
    @Test
    fun onlyYouTubesPhoneSite() {
        assertEquals(setOf("https://m.youtube.com"), YOUTUBE_BACKGROUND_ORIGINS)
        // The script checks the origin itself too, for a WebView that runs it more widely.
        YOUTUBE_BACKGROUND_ORIGINS.forEach { assertTrue(YOUTUBE_BACKGROUND_JS.contains("location.origin !== '$it'")) }
    }

    @Test
    fun marksThePlayerResponseTheWayThePlayerReadsIt() {
        // YouTube's player: playabilityStatus.backgroundability.backgroundabilityRenderer.backgroundable
        assertTrue(YOUTUBE_BACKGROUND_JS.contains("s.backgroundability = { backgroundabilityRenderer: { backgroundable: true } }"))
        assertTrue(YOUTUBE_BACKGROUND_JS.contains("'ytInitialPlayerResponse'"))
        assertTrue(YOUTUBE_BACKGROUND_JS.contains("'/youtubei/v1/'"))
    }
}
