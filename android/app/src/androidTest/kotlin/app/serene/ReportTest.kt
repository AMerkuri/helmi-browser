package app.serene

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.serene.engine.EngineJson
import app.serene.report.Reporter
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** "Report missed popup" on a real WebView: the screenshot must show the page, not blank white. */
@RunWith(AndroidJUnit4::class)
class ReportTest {
    @Test
    fun screenshotShowsThePage() {
        val html = "<!doctype html><body style='margin:0;background:#c00'><h1 style='background:#fff'>Serene report test</h1><p style='height:2000px'>text</p></body>".toByteArray()
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
            scenario.onActivity { it.tabs.open("http://127.0.0.1:${server.localPort}/report.html") }
            SystemClock.sleep(4000)
            val latch = CountDownLatch(1)
            var dir: File? = null
            scenario.onActivity { a ->
                val app = a.application as SereneApp
                Reporter.capture(a, app, a.tabs.current!!) { dir = it; latch.countDown() }
            }
            assertTrue("report did not finish", latch.await(15, TimeUnit.SECONDS))
            val report = EngineJson.parseToJsonElement(File(dir, "report.json").readText()).jsonObject
            assertEquals("pixelcopy", report["screenshot"]?.jsonPrimitive?.content)
            val shot = BitmapFactory.decodeFile(File(dir, "screenshot.png").path)
            assertNotNull("no screenshot.png", shot)
            assertFalse("screenshot is a single colour", Reporter.isUniform(shot))
        }
        server.close()
    }
}
