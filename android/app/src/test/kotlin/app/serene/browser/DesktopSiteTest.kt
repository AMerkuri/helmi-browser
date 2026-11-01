package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopSiteTest {
    private val foldUa = "Mozilla/5.0 (Linux; Android 16; SM-F966B Build/BP2A.250605.031.A2; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"

    @Test
    fun desktopUserAgentIsChromeOnLinuxWithTheWebViewsVersion() {
        val ua = desktopUserAgent(foldUa)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", ua)
        for (token in listOf("Android", "Mobile", "wv", "Version/4.0")) assertFalse(token, ua.contains(token))
    }

    @Test
    fun desktopUserAgentWithoutAChromeVersion() {
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", desktopUserAgent("Mozilla/5.0 (Linux; Android 16)"))
    }

    @Test
    fun desktopWidthReplacesThePhonesWidth() {
        // With zoom: the zoomed-out page has to be zoomed in to be read.
        assertEquals("width=980, user-scalable=yes, maximum-scale=5", rewriteViewport("width=device-width, initial-scale=1", desktop = true, force = false))
        assertEquals(
            "viewport-fit=cover, width=980, user-scalable=yes, maximum-scale=5",
            rewriteViewport("width=device-width,initial-scale=1.0,minimum-scale=1, viewport-fit=cover, user-scalable=no", desktop = true, force = false),
        )
        // No viewport of its own: the desktop width all the same.
        assertEquals("width=980, user-scalable=yes, maximum-scale=5", rewriteViewport("", desktop = true, force = false))
    }

    @Test
    fun zoomBlockIsLifted() {
        assertEquals(
            "width=device-width, initial-scale=1, user-scalable=yes, maximum-scale=5",
            rewriteViewport("width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no", desktop = false, force = true),
        )
        assertEquals("width=device-width, user-scalable=yes, maximum-scale=5", rewriteViewport("width=device-width; user-scalable=0", desktop = false, force = true))
        // A page that already allows more zoom keeps it.
        assertEquals("width=device-width, user-scalable=yes, maximum-scale=10", rewriteViewport("width=device-width, maximum-scale=10", desktop = false, force = true))
        assertEquals("width=device-width, user-scalable=yes, maximum-scale=7.5", rewriteViewport("width=device-width, maximum-scale=7.5", desktop = false, force = true))
    }

    @Test
    fun bothAtOnce() {
        assertEquals(
            "width=980, user-scalable=yes, maximum-scale=5",
            rewriteViewport("Width = device-width, Initial-Scale=1, User-Scalable=NO", desktop = true, force = true),
        )
    }

    @Test
    fun textZoomIsTheScaleOnTopOfTheFontSize() {
        assertEquals(100, TextScale.textZoom(100, 1f))
        assertEquals(115, TextScale.textZoom(100, 1.15f))
        assertEquals(230, TextScale.textZoom(200, 1.15f))
        assertEquals(50, TextScale.textZoom(10, 1f))
        assertEquals(300, TextScale.textZoom(400, 1f))
    }

    @Test
    fun sliderSnapsToSteps() {
        assertEquals(100, TextScale.snap(101.4f))
        assertEquals(105, TextScale.snap(103f))
        assertEquals(50, TextScale.snap(40f))
        assertEquals(300, TextScale.snap(301f))
    }
}
