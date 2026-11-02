package app.serene.browser

import app.serene.browser.HomeShortcutData.Icon
import app.serene.browser.HomeShortcutData.Manifest
import app.serene.browser.HomeShortcutData.Page
import app.serene.browser.HomeShortcutData.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShortcutDataTest {
    /** As evaluateJavascript hands back PAGE_INFO_JS's string: quoted once more. */
    private fun answer(json: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(json))

    @Test
    fun readsWhatThePageSays() {
        val page = HomeShortcutData.parsePage(
            answer(
                """{"title":" Harbour  ferry\n timetable ","manifest":"https://ferry.example/app.webmanifest","appName":"Ferry","themeColor":"#0a5",
                "icons":[
                  {"rel":"icon","href":"https://ferry.example/favicon-32.png","sizes":"32x32","type":"image/png"},
                  {"rel":"apple-touch-icon","href":"https://ferry.example/touch.png","sizes":"180x180","type":""},
                  {"rel":"icon","href":"https://ferry.example/logo.svg","sizes":"any","type":"image/svg+xml"},
                  {"rel":"icon","href":"data:image/png;base64,iVBORw0KGgo=","sizes":"","type":""},
                  {"rel":"icon","href":"javascript:alert(1)","sizes":"","type":""}
                ]}""",
            ),
        )
        assertNotNull(page)
        page!!
        assertEquals("Harbour ferry timetable", page.title)
        assertEquals("https://ferry.example/app.webmanifest", page.manifestUrl)
        assertEquals("Ferry", page.appName)
        assertEquals("#0a5", page.themeColor)
        assertEquals(
            listOf(
                Icon("https://ferry.example/favicon-32.png", 32, false, Source.LINK),
                Icon("https://ferry.example/touch.png", 180, false, Source.TOUCH),
                Icon("data:image/png;base64,iVBORw0KGgo=", 0, false, Source.LINK),
            ),
            page.icons,
        )
    }

    @Test
    fun aPageThatAnsweredNothingIsNull() {
        assertNull(HomeShortcutData.parsePage(null))
        assertNull(HomeShortcutData.parsePage("null"))
        assertNull(HomeShortcutData.parsePage(answer("not json")))
    }

    @Test
    fun manifestIconsAreRelativeToTheManifest() {
        val m = HomeShortcutData.parseManifest(
            """{"name":"Harbour Ferry Timetables","short_name":"Ferry","theme_color":"#004466",
            "icons":[
              {"src":"icons/192.png","sizes":"192x192","type":"image/png"},
              {"src":"/icons/mask-512.png","sizes":"512x512","purpose":"maskable"},
              {"src":"icons/both.png","sizes":"256x256","purpose":"any maskable"},
              {"src":"icons/mono.png","sizes":"512x512","purpose":"monochrome"},
              {"src":"icons/logo.svg","sizes":"any","type":"image/svg+xml"}
            ]}""",
            "https://ferry.example/static/app.webmanifest",
        )!!
        assertEquals("Ferry", m.shortName)
        assertEquals("Harbour Ferry Timetables", m.name)
        assertEquals("#004466", m.themeColor)
        assertEquals(
            listOf(
                Icon("https://ferry.example/static/icons/192.png", 192, false, Source.MANIFEST),
                Icon("https://ferry.example/icons/mask-512.png", 512, true, Source.MANIFEST),
                Icon("https://ferry.example/static/icons/both.png", 256, false, Source.MANIFEST),
            ),
            m.icons,
        )
        assertNull(HomeShortcutData.parseManifest("<html>", "https://ferry.example/m.json"))
    }

    @Test
    fun theManifestsMaskableIconComesFirstThenTheTouchIconThenFavicons() {
        val page = Page(
            icons = listOf(
                Icon("https://ferry.example/16.png", 16, false, Source.LINK),
                Icon("https://ferry.example/touch.png", 180, false, Source.TOUCH),
                Icon("https://ferry.example/96.png", 96, false, Source.LINK),
            ),
        )
        val manifest = Manifest(
            icons = listOf(
                Icon("https://ferry.example/any-512.png", 512, false, Source.MANIFEST),
                Icon("https://ferry.example/any-192.png", 192, false, Source.MANIFEST),
                Icon("https://ferry.example/mask-512.png", 512, true, Source.MANIFEST),
                Icon("https://ferry.example/mask-1024.png", 1024, true, Source.MANIFEST),
            ),
        )
        val order = HomeShortcutData.candidates("https://ferry.example/today?x=1", page, manifest, idealPx = 190).map { it.url.substringAfterLast('/') }
        assertEquals(listOf("mask-512.png", "mask-1024.png", "any-192.png", "any-512.png", "touch.png", "96.png", "16.png", "favicon.ico"), order)
    }

    @Test
    fun belowTheIdealSizeTheLargestIsTriedFirstAndUnknownSizesBeforeIt() {
        val page = Page(
            icons = listOf(
                Icon("https://ferry.example/16.png", 16, false, Source.LINK),
                Icon("https://ferry.example/unknown.png", 0, false, Source.LINK),
                Icon("https://ferry.example/48.png", 48, false, Source.LINK),
            ),
        )
        val order = HomeShortcutData.candidates("https://ferry.example/", page, null, idealPx = 190).map { it.url.substringAfterLast('/') }
        assertEquals(listOf("unknown.png", "48.png", "16.png", "favicon.ico"), order)
    }

    @Test
    fun theFrontPageIsNamedAfterTheAppAndOtherPagesAfterTheirTitle() {
        val page = Page(title = "Winter timetable | Harbour Ferry", appName = "Ferry app")
        val manifest = Manifest(name = "Harbour Ferry", shortName = "Ferry")
        assertEquals("Ferry", HomeShortcutData.name("https://ferry.example/", "", page, manifest))
        assertEquals("Ferry app", HomeShortcutData.name("https://ferry.example", "", page, null))
        assertEquals("Winter timetable | Harbour Ferry", HomeShortcutData.name("https://ferry.example/winter", "", page, manifest))
        assertEquals("Winter timetable | Harbour Ferry", HomeShortcutData.name("https://ferry.example/?lang=et", "", page, manifest))
    }

    @Test
    fun aPageWithNoNameIsNamedAfterItsSite() {
        assertEquals("Tab title", HomeShortcutData.name("https://ferry.example/a", "Tab title", null, null))
        assertEquals("ferry.example", HomeShortcutData.name("https://www.ferry.example/a", " ", Page(), Manifest()))
        // WebView titles a page with no <title> by its address.
        assertEquals("ferry.example", HomeShortcutData.name("https://ferry.example/a", "https://ferry.example/a", null, null))
        assertEquals("tõnu.ee", HomeShortcutData.site("https://xn--tnu-ona.ee/"))
        assertTrue(HomeShortcutData.name("https://ferry.example/a", "x".repeat(500), null, null).length <= HomeShortcutData.MAX_NAME)
    }

    @Test
    fun theLetterIsTheSitesFirst() {
        assertEquals("F", HomeShortcutData.monogram("https://www.ferry.example/x"))
        assertEquals("Õ", HomeShortcutData.monogram("https://xn--un-bka.ee/"))
        assertEquals("1", HomeShortcutData.monogram("http://127.0.0.1:8080/"))
    }

    @Test
    fun cssColours() {
        assertEquals(0xFF00AA55.toInt(), HomeShortcutData.parseColor("#0a5"))
        assertEquals(0xFF004466.toInt(), HomeShortcutData.parseColor(" #004466 "))
        assertEquals(0x80112233.toInt(), HomeShortcutData.parseColor("#11223380"))
        assertEquals(0xFF0A141E.toInt(), HomeShortcutData.parseColor("rgb(10, 20, 30)"))
        assertEquals(0x7F0A141E, HomeShortcutData.parseColor("rgba(10,20,30,0.5)"))
        assertEquals(0xFF0A141E.toInt(), HomeShortcutData.parseColor("rgb(10 20 30)"))
        assertEquals(0xFFFFFFFF.toInt(), HomeShortcutData.parseColor("White"))
        assertNull(HomeShortcutData.parseColor("hsl(10, 20%, 30%)"))
        assertNull(HomeShortcutData.parseColor("#12"))
        assertNull(HomeShortcutData.parseColor("#ggg"))
        assertNull(HomeShortcutData.parseColor(""))
    }

    @Test
    fun theFallbackColourStaysTheSameForASite() {
        assertEquals(HomeShortcutData.fallbackColor("https://ferry.example/a"), HomeShortcutData.fallbackColor("https://www.ferry.example/b"))
        assertTrue(HomeShortcutData.luminance(HomeShortcutData.fallbackColor("https://ferry.example/")) < 0.5)
        assertEquals(1.0, HomeShortcutData.luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, HomeShortcutData.luminance(0xFF000000.toInt()), 1e-9)
    }

    @Test
    fun sizes() {
        assertEquals(32, HomeShortcutData.largestSize("16x16 32x32"))
        assertEquals(144, HomeShortcutData.largestSize("144X144"))
        assertEquals(0, HomeShortcutData.largestSize("any"))
        assertEquals(0, HomeShortcutData.largestSize(""))
        assertEquals(100, HomeShortcutData.largestSize("200x100"))
    }

    @Test
    fun aTabOnTheShortcutsPageIsReused() {
        assertTrue(HomeShortcutData.samePage("https://ferry.example/", "https://www.ferry.example"))
        assertTrue(HomeShortcutData.samePage("http://ferry.example/winter", "https://ferry.example/winter/#today"))
        assertFalse(HomeShortcutData.samePage("https://ferry.example/winter", "https://ferry.example/summer"))
        assertFalse(HomeShortcutData.samePage("https://ferry.example/?a=1", "https://ferry.example/?a=2"))
        assertFalse(HomeShortcutData.samePage("https://ferry.example/", ""))
    }

    @Test
    fun theFrontPage() {
        assertTrue(HomeShortcutData.isFrontPage("https://ferry.example"))
        assertTrue(HomeShortcutData.isFrontPage("https://ferry.example/#top"))
        assertFalse(HomeShortcutData.isFrontPage("https://ferry.example/?q=1"))
        assertFalse(HomeShortcutData.isFrontPage("https://ferry.example/winter"))
    }
}
