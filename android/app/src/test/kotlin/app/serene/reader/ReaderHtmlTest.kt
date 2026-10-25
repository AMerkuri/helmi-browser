package app.serene.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlTest {
    private val page = ReaderPage(
        url = "https://news.example/ferry",
        article = true,
        title = "Ferry <returns> & \"more\"",
        byline = "By Mara Lind",
        siteName = "The Coastal Gazette",
        lang = "en",
        html = "<p>The harbour ferry resumed its timetable.</p>",
        text = "The harbour ferry resumed its timetable.",
        words = 690,
    )

    @Test
    fun theDocumentLocksOutScriptsAndEscapesTheTitle() {
        val html = ReaderHtml.build(page, ReaderStyle())
        // The policy is the first thing in <head>, before anything from the page.
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<title>"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("form-action 'none'"))
        assertFalse(html.contains("script-src"))
        assertTrue(html.contains("<h1 class=\"title\">Ferry &lt;returns&gt; &amp; &quot;more&quot;</h1>"))
        assertTrue(html.contains("The Coastal Gazette · By Mara Lind · 3 min read"))
        assertTrue(html.contains("<html lang=\"en\" dir=\"auto\">"))
        assertTrue(html.contains("<p>The harbour ferry resumed its timetable.</p>"))
    }

    @Test
    fun styleReachesTheCss() {
        val night = ReaderHtml.build(page, ReaderStyle(theme = ReaderTheme.NIGHT, textSize = 24, serif = false, justify = false))
        assertTrue(night.contains("background: #141414"))
        assertTrue(night.contains("font-size: 24px"))
        assertTrue(night.contains("sans-serif"))
        assertTrue(night.contains("text-align: start; hyphens: manual"))
        assertTrue(night.contains("content=\"dark\""))
        val paper = ReaderHtml.build(page, ReaderStyle())
        assertTrue(paper.contains("text-align: justify; hyphens: auto"))
    }

    @Test
    fun aLanguageThatIsNotATagIsDropped() {
        val html = ReaderHtml.build(page.copy(lang = "en\" onload=\"x", dir = "sideways"), ReaderStyle())
        assertTrue(html.contains("<html dir=\"auto\">"))
    }

    @Test
    fun parsesWhatTheExtractorReturns() {
        val p = ReaderPage.parse("""{"url":"https://a.example/","article":true,"title":"T","html":"<p>x</p>","text":"x y z","words":3,"extra":1}""")!!
        assertEquals("T", p.title)
        assertEquals(1, p.minutes)
        assertNull(ReaderPage.parse("null"))
        assertNull(ReaderPage.parse("""{"error":"boom"}"""))
        assertNull(ReaderPage.parse("""{"text":"  "}"""))
    }
}
