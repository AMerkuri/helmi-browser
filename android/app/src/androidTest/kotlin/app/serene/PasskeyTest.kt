package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
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
 * Passkeys: a tab runs WebView in browser WebAuthn mode, so pages see PublicKeyCredential
 * (GitHub says "Webauthn isn't supported" without it). Signing in needs a passkey provider,
 * which the emulator has none of; this only checks that the API reaches the page.
 */
@RunWith(AndroidJUnit4::class)
class PasskeyTest {
    @Test
    fun pageSeesWebAuthn() {
        assumeTrue("this WebView has no WEB_AUTHENTICATION feature", Tab.webAuthnSupported)
        val html = "<!doctype html><title>Serene passkey test</title><body>passkeys</body>".toByteArray()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(html)
                        flush()
                    }
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: Tab? = null
            // 127.0.0.1 is a secure context, which WebAuthn requires.
            scenario.onActivity { tab = it.tabs.open("http://127.0.0.1:${server.localPort}/passkey.html") }
            val end = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < end && !(tab?.title == "Serene passkey test" && tab?.progress == 100)) SystemClock.sleep(100)
            assertEquals("page did not load", "Serene passkey test", tab?.title)

            var mode = -1
            var api = ""
            val latch = CountDownLatch(1)
            scenario.onActivity {
                val v = it.tabs.current!!.webView
                mode = WebSettingsCompat.getWebAuthenticationSupport(v.settings)
                v.evaluateJavascript("typeof PublicKeyCredential + ' ' + typeof navigator.credentials") { r -> api = r; latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
            assertEquals("WebAuthn mode", WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, mode)
            assertEquals("\"function object\"", api)
        }
        server.close()
    }
}
