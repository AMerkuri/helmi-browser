package app.serene

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.browser.SitePermission
import app.serene.browser.Tab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Camera, microphone and location, Chrome's way: the site asks, the answer is remembered for its
 * origin (no prompt on the next visit), a prompt left unanswered blocks only that once, and a reset
 * has the site ask again. localhost is a secure context, so its pages may ask.
 */
@RunWith(AndroidJUnit4::class)
class SitePermissionsTest {
    private lateinit var server: ServerSocket
    private val port get() = server.localPort
    private val origin get() = "http://localhost:$port"

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().let { s -> thread(isDaemon = true) { runCatching { serve(s) } } } }
        }
    }

    @After
    fun tearDown() {
        server.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app().sitePermissions.resetAll() }
    }

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SereneApp

    private fun serve(s: Socket): Unit = s.use {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!r.readLine().isNullOrEmpty()) Unit
        // Each load signs its answer, so a reload's answer is told from the last one's.
        val head = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>asking</title><script>const load = ' #' + Math.random()</script>"
        val body = when (path) {
            // The page's title says how the request ended: "geo ok", or the error code (1 = denied).
            "/geo" -> "$head<script>navigator.geolocation.getCurrentPosition(() => document.title = 'geo ok' + load, e => document.title = 'geo ' + e.code + load, { timeout: 3000 })</script>"
            "/mic" -> "$head<script>navigator.mediaDevices.getUserMedia({ audio: true })" +
                ".then(s => { s.getTracks().forEach(t => t.stop()); document.title = 'mic ok' + load }, e => document.title = 'mic ' + e.name + load)</script>"
            else -> head
        }
        val bytes = body.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    @Test
    fun blockIsRememberedAndResetAsksAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var tab: Tab
            scenario.onActivity { a -> a.app().sitePermissions.resetAll(); tab = a.tabs.open("$origin/mic") }
            waitFor("the microphone prompt") { tab.permissions.current?.kinds == setOf(SitePermission.Microphone) }
            assertEquals(origin, onMain { tab.permissions.current!!.origin })
            onMain { tab.permissions.answer(tab.permissions.current!!, false) }
            waitFor("the page to be refused") { tab.title.startsWith("mic NotAllowedError") }
            assertEquals(mapOf(SitePermission.Microphone to false), onMain { app().sitePermissions.choices(origin) })

            // Remembered: the next visit is refused without a prompt.
            val first = tab.title
            onMain { tab.reload() }
            waitFor("the page to be refused again") { tab.title.startsWith("mic NotAllowedError") && tab.title != first }
            assertNull(onMain { tab.permissions.current })

            // Reset (the site sheet): asked again. Back on the prompt refuses once, remembering nothing.
            onMain { app().sitePermissions.resetSite("localhost"); tab.reload() }
            waitFor("the prompt again") { tab.permissions.current != null }
            val second = tab.title
            onMain { tab.permissions.dismiss(tab.permissions.current!!) }
            waitFor("the page to be refused") { tab.title.startsWith("mic NotAllowedError") && tab.title != second }
            assertTrue(onMain { app().sitePermissions.choices(origin).isEmpty() })
        }
    }

    @Test
    fun allowedLocationReachesThePage() {
        // Android's own permission, as if the user had given it in the system dialog.
        val inst = InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, it)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var tab: Tab
            scenario.onActivity { a -> a.app().sitePermissions.resetAll(); tab = a.tabs.open("$origin/geo") }
            waitFor("the location prompt") { tab.permissions.current?.kinds == setOf(SitePermission.Location) }
            onMain { tab.permissions.answer(tab.permissions.current!!, true) }
            // The emulator may have no fix (2, 3), but the page is not refused (1).
            waitFor("the page's answer") { tab.title.startsWith("geo ") }
            assertTrue("refused: ${tab.title}", !tab.title.startsWith("geo 1 "))
            assertEquals(mapOf(SitePermission.Location to true), onMain { app().sitePermissions.choices(origin) })

            val first = tab.title
            onMain { tab.reload() }
            waitFor("the page's answer, without a prompt") { tab.title.startsWith("geo ") && tab.title != first }
            assertTrue("refused: ${tab.title}", !tab.title.startsWith("geo 1 "))
            assertNull(onMain { tab.permissions.current })
        }
    }

    private fun MainActivity.app() = application as SereneApp

    private fun <T> onMain(block: () -> T): T {
        var v: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { v = runCatching(block) }
        return v!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
