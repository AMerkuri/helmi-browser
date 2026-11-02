package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebSettingsCompat
import app.serene.browser.Tab
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Settings › Flags › Payment Request: off, pages have no PaymentRequest (sites use their own
 * checkout); on, the next page load has it. Paying needs a payment app, which the emulator has
 * none of, so this only checks that the API reaches the page or not.
 */
@RunWith(AndroidJUnit4::class)
class PaymentRequestTest {
    private val app get() = ApplicationProvider.getApplicationContext<SereneApp>()

    @Test
    fun flagGivesPagesPaymentRequest() {
        assumeTrue("this WebView has no PAYMENT_REQUEST feature", Tab.paymentRequestSupported)
        val html = "<!doctype html><title>Helmi payment test</title><body>pay</body>".toByteArray()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        val before = app.settings.paymentRequest
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var tab: Tab? = null
                fun loaded() {
                    val end = SystemClock.uptimeMillis() + 20_000
                    while (SystemClock.uptimeMillis() < end && !(tab?.title == "Helmi payment test" && tab?.progress == 100)) SystemClock.sleep(100)
                    assertEquals("page did not load", "Helmi payment test", tab?.title)
                }
                fun api(): Pair<Boolean, String> {
                    var on = false
                    var r = ""
                    val latch = CountDownLatch(1)
                    scenario.onActivity {
                        val v = it.tabs.current!!.webView
                        on = WebSettingsCompat.getPaymentRequestEnabled(v.settings)
                        v.evaluateJavascript("typeof PaymentRequest") { s -> r = s; latch.countDown() }
                    }
                    latch.await(10, TimeUnit.SECONDS)
                    return on to r
                }
                scenario.onActivity { app.settings.paymentRequest = false }
                // 127.0.0.1 is a secure context, which PaymentRequest requires.
                scenario.onActivity { tab = it.tabs.open("http://127.0.0.1:${server.localPort}/pay.html") }
                loaded()
                assertEquals(false to "\"undefined\"", api())

                scenario.onActivity { app.settings.paymentRequest = true }
                SystemClock.sleep(300) // the setting reaches the tab on the main thread
                scenario.onActivity { tab!!.webView.reload() }
                SystemClock.sleep(500)
                loaded()
                assertEquals(true to "\"function\"", api())
            }
        } finally {
            app.settings.paymentRequest = before
            server.close()
        }
    }
}
