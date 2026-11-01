package app.serene

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PdfWriter
import android.print.PrintManager
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.serene.browser.PagePrint
import app.serene.browser.Tab
import app.serene.reader.ReaderHtml
import app.serene.reader.ReaderPage
import app.serene.reader.ReaderStyle
import app.serene.reader.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ⋮ › Print…: the system print dialog opens on the tab's page with the job named after its
 * title, and the PDF it saves is black on white paper: a page the dark theme darkens, and the
 * reader's Night theme, both print light.
 */
@RunWith(AndroidJUnit4::class)
class PrintTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as SereneApp

    @Test
    fun menuOpensThePrintDialogNamedAfterThePage() {
        val server = serve()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                var tab: Tab? = null
                scenario.onActivity { tab = it.tabs.current }
                load(tab!!, "http://127.0.0.1:${server.localPort}/ferry.html")
                var printer: PrintManager? = null
                scenario.onActivity { a ->
                    printer = a.getSystemService(PrintManager::class.java)
                    PagePrint.print(a, tab!!.webView, tab!!.title, tab!!.url) { tab!!.printing = it }
                }
                waitFor("a print job named after the page") { printer!!.printJobs.any { it.info.label == TITLE } }
                waitFor("the dialog to start on the page") { tab!!.printing }
                // Back closes the dialog, which is then done with the page.
                inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor("the dialog to be done with the page") { !tab!!.printing }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun aDarkenedPagePrintsOnWhitePaper() {
        assumeTrue("the app's night mode needs API 31", Build.VERSION.SDK_INT >= 31)
        assumeTrue("WebView without algorithmic darkening", WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
        val server = serve()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                inst.runOnMainSync {
                    app.settings.theme = ThemeMode.DARK
                    app.settings.darkSites = true
                }
                var tab: Tab? = null
                scenario.onActivity { tab = it.tabs.current }
                val t = tab!!
                load(t, "http://127.0.0.1:${server.localPort}/ferry.html")
                waitFor("the page to be darkened") { darkening(t) }

                // As WebView prints a darkened page by itself: logged, to see whether it still needs the help.
                val plain = print(t.webView) { t.webView.createPrintDocumentAdapter(TITLE) }
                Log.i("PrintTest", "darkened page printed as is: %.0f%% light paper".format(lightShare(plain) * 100))

                var during = true
                val printed = print(t.webView) { PagePrint.adapter(t.webView, TITLE) { t.printing = it; if (it) during = darkening(t) } }
                assertFalse("the page was still darkened while it printed", during)
                assertTrue("the printed page is dark: %.0f%% light paper".format(lightShare(printed) * 100), lightShare(printed) > 0.9)
                assertTrue("the text didn't print dark", darkest(printed) < 0.3)
                waitFor("the page to be darkened again") { darkening(t) }
            } finally {
                inst.runOnMainSync { app.settings.theme = ThemeMode.SYSTEM }
                server.close()
            }
        }
    }

    @Test
    fun theReadersNightThemePrintsBlackOnWhite() {
        val page = ReaderPage(
            url = "https://news.example/ferry", article = true, title = TITLE, byline = "By Mara Lind",
            siteName = "The Coastal Gazette", lang = "en", html = PARAGRAPHS, words = 690,
        )
        lateinit var web: WebView
        val loaded = CountDownLatch(1)
        inst.runOnMainSync {
            web = WebView(inst.targetContext).apply {
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                }
                loadDataWithBaseURL(page.url, ReaderHtml.build(page, ReaderStyle(theme = ReaderTheme.NIGHT)), "text/html", "utf-8", null)
            }
        }
        assertTrue("reader page did not load", loaded.await(20, TimeUnit.SECONDS))
        val printed = print(web) { PagePrint.adapter(web, TITLE) {} }
        assertTrue("the reader printed dark: %.0f%% light paper".format(lightShare(printed) * 100), lightShare(printed) > 0.9)
        assertTrue("the reader's text didn't print dark (darkest %.2f)".format(darkest(printed)), darkest(printed) < 0.3)
        inst.runOnMainSync { web.destroy() }
    }

    /** On the main thread: whether the tab's WebView may darken its page. */
    private fun darkening(t: Tab): Boolean = WebSettingsCompat.isAlgorithmicDarkeningAllowed(t.webView.settings)

    /** The first printed page, on white paper (what the PDF leaves blank stays white). */
    private fun print(web: WebView, adapter: () -> android.print.PrintDocumentAdapter): Bitmap {
        val file = File(inst.targetContext.cacheDir, "print-test.pdf")
        val done = CountDownLatch(1)
        var ok = false
        inst.runOnMainSync { PdfWriter.write(adapter(), file) { ok = it; done.countDown() } }
        assertTrue("printing took too long", done.await(30, TimeUnit.SECONDS))
        assertTrue("printing failed", ok)
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            assertTrue("the PDF has no pages", pdf.pageCount > 0)
            pdf.openPage(0).use { p ->
                val bitmap = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                return bitmap
            }
        }
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun lightShare(b: Bitmap): Double = pixels(b).count { luminance(it) > 0.8 }.toDouble() / (b.width * b.height)

    private fun darkest(b: Bitmap): Double = pixels(b).minOf { luminance(it) }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            inst.runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun load(tab: Tab, url: String) {
        inst.runOnMainSync { tab.load(url) }
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end && !(tab.title == TITLE && tab.progress == 100)) SystemClock.sleep(100)
        assertEquals("page did not load", TITLE, tab.title)
    }

    /** A light page with plenty of black text and no dark theme of its own. */
    private fun serve(): ServerSocket {
        val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>$TITLE</title>" +
            "<body style='margin:0;padding:16px;background:#fff;color:#000;font-size:20px'><h1>$TITLE</h1>$PARAGRAPHS</body>"
        val body = html.toByteArray()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return server
    }

    companion object {
        private const val TITLE = "Harbour ferry: winter timetable"
        private val PARAGRAPHS = (1..8).joinToString("") {
            "<p>Paragraph $it. The harbour ferry between Northpoint and the island of Vell resumed its timetable on Monday, six weeks after a winter storm tore the loading ramp from its hinges.</p>"
        }
    }
}
