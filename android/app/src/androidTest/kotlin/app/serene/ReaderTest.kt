package app.serene

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.reader.ReaderHtml
import app.serene.reader.ReaderPage
import app.serene.reader.ReaderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reader mode on the system WebView: the packaged reader.js finds the article on a page, its
 * answer survives evaluateJavascript's JSON, and the reader document runs no script even with
 * JavaScript on (its Content-Security-Policy).
 */
@RunWith(AndroidJUnit4::class)
class ReaderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    // A hand-written article page: chrome and a fixed cookie bar around the story. The title is
    // long enough that Readability drops the site name after " | " (it keeps it on titles of four
    // words or fewer).
    private val article = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>Harbour ferry returns after storm repairs | The Coastal Gazette</title>
        <style>.cookie { position: fixed; bottom: 0; left: 0; right: 0; }</style></head>
        <body><nav class="menu"><a href="/news">News</a> <a href="/sport">Sport</a></nav>
        <article><h1>Harbour ferry returns after storm repairs</h1>
        ${(1..6).joinToString("") { "<p>Paragraph $it. The harbour ferry between Northpoint and the island of Vell resumed its timetable on Monday, six weeks after a winter storm tore the loading ramp from its hinges.</p>" }}
        <p><img src="ferry.jpg" onerror="window.ran = 1"></p>
        </article>
        <div class="cookie">We use cookies to show personalised ads. <button>Accept all</button></div>
        </body></html>
    """.trimIndent()

    private fun load(web: WebView, html: String) {
        val done = CountDownLatch(1)
        inst.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { done.countDown() }
            }
            web.loadDataWithBaseURL("https://news.example/ferry", html, "text/html", "utf-8", null)
        }
        assertTrue("page did not load", done.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun extractsTheArticleAndRendersItWithoutScripts() {
        lateinit var web: WebView
        inst.runOnMainSync { web = WebView(inst.targetContext).apply { settings.javaScriptEnabled = true } }
        load(web, article)

        val got = CountDownLatch(1)
        var page: ReaderPage? = null
        inst.runOnMainSync { ReaderPage.extract(web) { page = it; got.countDown() } }
        assertTrue(got.await(20, TimeUnit.SECONDS))
        assertNotNull(page)
        val p = page!!
        assertTrue(p.article)
        assertEquals("Harbour ferry returns after storm repairs", p.title)
        assertEquals("https://news.example/ferry", p.url)
        assertTrue(p.html.contains("Paragraph 6."))
        assertFalse(p.html.contains("We use cookies"))
        assertFalse(p.html.contains("onerror"))

        // Even with scripts on, the reader document's policy stops an inline handler.
        load(web, ReaderHtml.build(p.copy(html = p.html + "<img src=x onerror=\"document.title='ran'\">"), ReaderStyle()))
        val title = CountDownLatch(1)
        var t = ""
        inst.runOnMainSync { web.evaluateJavascript("document.title") { t = it; title.countDown() } }
        assertTrue(title.await(10, TimeUnit.SECONDS))
        assertEquals("\"Harbour ferry returns after storm repairs\"", t)
        inst.runOnMainSync { web.destroy() }
    }
}
