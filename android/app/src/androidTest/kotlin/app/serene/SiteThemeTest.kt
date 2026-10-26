package app.serene

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Settings › Theme on pages. In the dark theme with "Apply dark theme to sites" on, a light page
 * with no dark theme is darkened and its pictures keep their colors, a drawing included (WebView
 * would invert that one); a page with its own dark theme shows it as is. With the setting off, or
 * in the light theme, the light page stays light. The theme changes while the page is open, as
 * when the phone switches to dark.
 */
@RunWith(AndroidJUnit4::class)
class SiteThemeTest {
    private val failures = mutableListOf<String>()

    @Test
    fun darkThemeForSites() {
        assumeTrue("the app's night mode needs API 31", Build.VERSION.SDK_INT >= 31)
        assumeTrue("WebView without algorithmic darkening", WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
        val server = serve(
            "/light.html" to page("Serene light page", "", PICTURES),
            // A page with a dark theme of its own. Its dark background is light on purpose: darkening
            // the page again would turn it dark, so the color shows the page's theme is left alone.
            "/native.html" to page("Serene native dark page", "<meta name=color-scheme content='light dark'><style>@media (prefers-color-scheme: dark) { body { background: #ffee88 !important } }</style>", ""),
            "/photo.png" to png(photo),
            "/drawing.png" to png(drawing),
        )
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SereneApp
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                var tab: Tab? = null
                scenario.onActivity { tab = it.tabs.current }
                load(tab!!, "http://127.0.0.1:${server.localPort}/light.html", "Serene light page")

                setTheme(app, ThemeMode.LIGHT, darkSites = true)
                expect(tab!!, "light theme", prefersDark = false, background = WHITE, pictures = true)
                setTheme(app, ThemeMode.DARK, darkSites = true)
                expect(tab!!, "dark theme, dark sites on", prefersDark = true, background = DARK, pictures = true)
                setTheme(app, ThemeMode.DARK, darkSites = false)
                expect(tab!!, "dark theme, dark sites off", prefersDark = true, background = WHITE, pictures = true)

                setTheme(app, ThemeMode.DARK, darkSites = true)
                load(tab!!, "http://127.0.0.1:${server.localPort}/native.html", "Serene native dark page")
                expect(tab!!, "dark theme, page with its own dark theme", prefersDark = true, background = Color.rgb(0xff, 0xee, 0x88), pictures = false)
                setTheme(app, ThemeMode.LIGHT, darkSites = true)
                expect(tab!!, "light theme, page with its own dark theme", prefersDark = false, background = WHITE, pictures = false)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    app.settings.theme = ThemeMode.SYSTEM
                    app.settings.darkSites = true
                }
                server.close()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun setTheme(app: SereneApp, theme: ThemeMode, darkSites: Boolean) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.settings.theme = theme
            app.settings.darkSites = darkSites
        }

    private fun load(tab: Tab, url: String, title: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { tab.load(url) }
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end && !(tab.title == title && tab.progress == 100)) SystemClock.sleep(100)
        assertEquals("page did not load", title, tab.title)
    }

    /**
     * Waits up to 15 s for the page to look as expected (the theme change and the repaint take a
     * moment), then records what is still wrong, with a screenshot in logcat.
     */
    private fun expect(tab: Tab, where: String, prefersDark: Boolean, background: Int, pictures: Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        var wrong: List<String>
        do {
            SystemClock.sleep(250)
            wrong = check(tab, prefersDark, background, pictures)
        } while (wrong.isNotEmpty() && SystemClock.uptimeMillis() < end)
        if (wrong.isNotEmpty()) {
            failures += "$where: ${wrong.joinToString("; ")}"
            logScreenshot(where)
        }
    }

    private fun check(tab: Tab, prefersDark: Boolean, background: Int, pictures: Boolean): List<String> {
        val wrong = mutableListOf<String>()
        val media = evaluate(tab, "matchMedia('(prefers-color-scheme: dark)').matches")
        if (media != "$prefersDark") wrong += "prefers-color-scheme dark is $media"
        val screen = screenshot() ?: return wrong + "no screenshot"
        val origin = IntArray(2)
        var density = 1f
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tab.webView.getLocationOnScreen(origin)
            density = tab.webView.resources.displayMetrics.density
        }
        // A point of the page, in CSS px (the pages are device-width, so a CSS px is a dp).
        fun at(x: Int, y: Int) = screen.getPixel(origin[0] + (x * density).toInt(), origin[1] + (y * density).toInt())

        val bg = at(10, 220)
        val bgOk = if (background == DARK) luminance(bg) < 0.3 else close(bg, background)
        if (!bgOk) wrong += "page background %06x, expected %s".format(bg and 0xffffff, if (background == DARK) "dark" else "%06x".format(background and 0xffffff))
        if (pictures) {
            // The middle of each picture, and the drawing's white corner and black middle.
            val photoMid = at(20 + 50, 60 + 50)
            if (!close(photoMid, photo.getPixel(50, 50))) wrong += "photo %06x, expected %06x".format(photoMid and 0xffffff, photo.getPixel(50, 50) and 0xffffff)
            val paper = at(140 + 10, 60 + 10)
            if (luminance(paper) < 0.9) wrong += "drawing's white is %06x".format(paper and 0xffffff)
            val ink = at(140 + 50, 60 + 50)
            if (luminance(ink) > 0.1) wrong += "drawing's black is %06x".format(ink and 0xffffff)
        }
        return wrong
    }

    private fun evaluate(tab: Tab, js: String): String? {
        var result: String? = null
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tab.webView.evaluateJavascript(js) { result = it; done.countDown() }
        }
        done.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun serve(vararg files: Pair<String, ByteArray>): ServerSocket {
        val byPath = files.toMap()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    val path = r.readLine()?.split(' ')?.getOrNull(1) ?: ""
                    while (!r.readLine().isNullOrEmpty()) Unit
                    val body = byPath[path]
                    val head = if (body == null) "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Type: ${if (path.endsWith(".png")) "image/png" else "text/html"}\r\nContent-Length: ${body.size}\r\n"
                    s.getOutputStream().apply {
                        write("${head}Connection: close\r\n\r\n".toByteArray())
                        if (body != null) write(body)
                        flush()
                    }
                }
            }
        }
        return server
    }

    private fun page(title: String, head: String, body: String) =
        "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>$title</title>$head<body style='margin:0;background:#fff;color:#000'><p style='margin:0;height:40px'>Some text</p>$body<div style='height:2000px'></div>".toByteArray()

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun screenshot(): Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)

    /** The whole display as a base64 PNG in logcat (the e2e-output artifact), to see a failure. */
    private fun logScreenshot(label: String) {
        val png = ByteArrayOutputStream().also { screenshot()?.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        Base64.encodeToString(png, Base64.NO_WRAP).chunked(3000).forEachIndexed { i, chunk -> Log.i("SiteThemeShot", "$label #$i $chunk") }
    }

    private fun close(a: Int, b: Int) =
        Math.abs(Color.red(a) - Color.red(b)) < 24 && Math.abs(Color.green(a) - Color.green(b)) < 24 && Math.abs(Color.blue(a) - Color.blue(b)) < 24

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255

    companion object {
        private const val WHITE = Color.WHITE
        private const val DARK = 1 // any dark background: WebView picks the shade

        private const val PICTURES =
            "<img src=photo.png style='position:absolute;left:20px;top:60px;width:100px;height:100px'>" +
                "<img src=drawing.png style='position:absolute;left:140px;top:60px;width:100px;height:100px'>"

        /** A colorful picture: hue across, saturation down. */
        private val photo: Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 100) for (y in 0 until 100) setPixel(x, y, Color.HSVToColor(floatArrayOf(x * 3.6f, 0.3f + 0.7f * y / 100, 0.9f)))
        }

        /** A black square on white, which WebView's darkening takes for a drawing and inverts. */
        private val drawing: Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 25 until 75) for (y in 25 until 75) setPixel(x, y, Color.BLACK)
        }
    }
}
