package app.serene

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.serene.engine.Classifier
import app.serene.engine.ClassifierResult
import app.serene.engine.EngineJson
import app.serene.engine.Features
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The prototype's fixture pages (engine/test/fixtures), run in the real app: system WebView,
 * document-start script, SereneBridge, adblock-rust over JNI, the Kotlin policy. Same pages and
 * same expectations as engine/test/fixtures.test.mjs; the model is stubbed the same way.
 */
@RunWith(Parameterized::class)
class FixturesTest(private val file: String) {

    companion object {
        private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
        private lateinit var server: ServerSocket
        private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SereneApp
        private val llmCalls = mutableListOf<String>()

        private fun expectOf(name: String): JsonObject {
            val html = assets.open(name).bufferedReader().readText()
            val json = Regex("""<script type="application/json" id="expect">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
            return EngineJson.parseToJsonElement(json).jsonObject
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = assets.list("")!!.filter { it.endsWith(".html") }.sorted().filter {
            val e = expectOf(it)
            // frameOnly is the iframe half of cookie-iframe; tapFirst needs a trusted tap.
            e["frameOnly"] == null && e["tapFirst"] == null
        }

        @JvmStatic
        @BeforeClass
        fun setUp() {
            server = ServerSocket(0)
            thread(isDaemon = true) { while (!server.isClosed) runCatching { serve(server.accept()) } }
            // Stub model: answers with the fixture's expected label, and records that it was asked.
            app.classifierOverride = object : Classifier {
                override suspend fun classify(f: Features): ClassifierResult {
                    val name = f.url.substringAfterLast('/').substringBefore('?')
                    synchronized(llmCalls) { llmCalls += name }
                    val llm = expectOf(name)["llm"]?.jsonObject
                    return ClassifierResult(llm?.get("label")?.jsonPrimitive?.contentOrNull ?: "content", llm?.get("target")?.jsonPrimitive?.intOrNull ?: -1, 0)
                }
            }
            // The filter lists download on first start; wait for the rules engine.
            val end = SystemClock.uptimeMillis() + 120_000
            while (app.engine.rules == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(500)
            assertNotNull("filter lists did not load: ${app.rulesStatus}", app.engine.rules)
            while (app.engine.autoconsent == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
            assertNotNull("autoconsent rules did not load", app.engine.autoconsent)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            server.close()
            app.classifierOverride = null
        }

        private fun serve(s: Socket) = thread(isDaemon = true) {
            s.use {
                val reader = it.getInputStream().bufferedReader()
                val reqLine = reader.readLine() ?: return@use
                while (!reader.readLine().isNullOrEmpty()) Unit // headers
                val path = reqLine.split(' ').getOrNull(1) ?: "/"
                val name = path.substringAfterLast('/').substringBefore('?')
                val out = it.getOutputStream()
                val body = runCatching { assets.open(name).readBytes() }.getOrNull()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    private val stateJs = """(() => {
      const shown = (sel) => {
        const el = document.querySelector(sel);
        if (!el) return false;
        const cs = getComputedStyle(el), r = el.getBoundingClientRect();
        return cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.05 && r.height > 0;
      };
      const exp = JSON.parse(document.getElementById('expect').textContent);
      const h = getComputedStyle(document.documentElement), b = getComputedStyle(document.body);
      return JSON.stringify({
        started: !!(window.__serene && window.__serene.state.started),
        gone: exp.gone ? !shown(exp.gone) : null,
        visible: (exp.visible || []).map((s) => [s, shown(s)]),
        clicked: window.__clicked || [],
        scrollable: h.overflow !== 'hidden' && b.overflow !== 'hidden' && b.position !== 'fixed',
        // What the content script saw, for failure messages.
        page: window.__serene ? {
          candidates: [...window.__serene.state.candidates].map(([id, c]) => [id, c.sig, !!c.quarantined, c.el.getAttribute('data-serene')]),
          scans: window.__serene.state.scanMs.length,
          scanHeld: !!window.__serene.state.heldScan,
          goneStyle: exp.gone && document.querySelector(exp.gone) ? (() => { const el = document.querySelector(exp.gone), cs = getComputedStyle(el); return [cs.display, cs.visibility, el.getBoundingClientRect().height, el.getAttribute('data-serene')]; })() : null,
        } : null,
      });
    })()"""

    @Test
    fun fixture() {
        val exp = expectOf(file)
        app.engine.cache.clear()
        app.settings.savedTabs = emptyList() // start each fixture with only its own tab
        val url = "http://127.0.0.1:${server.localPort}/$file"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var tab: app.serene.browser.Tab? = null
            scenario.onActivity { tab = it.tabs.open(url) }
            // Time the page from its first document, not from the tap: a cold WebView on an
            // emulator takes seconds to start, which a phone's warm one does not.
            val end = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < end && tab?.session?.log()?.any { it.contains("hello from top") } != true) SystemClock.sleep(100)
            SystemClock.sleep((exp["waitMs"]?.jsonPrimitive?.intOrNull ?: 2500) + 1500L) // emulators are slower than desktop Chromium

            val latch = CountDownLatch(1)
            var raw: String? = null
            var session: app.serene.engine.PageSession? = null
            scenario.onActivity { a ->
                val tab = a.tabs.current!!
                session = tab.session
                tab.webView.evaluateJavascript(stateJs) { raw = it; latch.countDown() }
            }
            assertTrue("page did not answer", latch.await(10, TimeUnit.SECONDS))
            val state = EngineJson.parseToJsonElement(EngineJson.decodeFromString<String>(raw!!)).jsonObject
            val s = session
            val log = (s?.log()?.joinToString("\n") ?: "(no session)") + "\ncandidates: " +
                s?.candidates()?.joinToString { "#${it.id} ${it.features.sig} ${it.label}/${it.action} via ${it.source} applied=${it.applied}" } + "\nstate: $state"

            assertEquals("content script should start\n$log", true, state["started"]?.jsonPrimitive?.booleanOrNull)
            if (exp["gone"] != null) assertEquals("${exp["gone"]} should be gone\n$log", true, state["gone"]?.jsonPrimitive?.booleanOrNull)
            for (v in state["visible"]!!.jsonArray) {
                assertTrue("${v.jsonArray[0]} should stay visible\n$log", v.jsonArray[1].jsonPrimitive.booleanOrNull == true)
            }
            val clicked = state["clicked"]!!.jsonArray.map { it.jsonPrimitive.content }
            exp["clicked"]?.jsonArray?.forEach { c -> assertTrue("should click $c, clicked $clicked\n$log", c.jsonPrimitive.content in clicked) }
            exp["notClicked"]?.jsonArray?.forEach { c -> assertFalse("must not click $c\n$log", c.jsonPrimitive.content in clicked) }
            if (exp["scroll"]?.jsonPrimitive?.booleanOrNull == true) assertEquals("page scrolling should be restored\n$log", true, state["scrollable"]?.jsonPrimitive?.booleanOrNull)

            val acted = s?.candidates()?.filter { it.action != null && it.action != "ignore" } ?: emptyList()
            when (exp["via"]?.jsonPrimitive?.contentOrNull) {
                "rules" -> assertEquals("rules should handle it before the detector\n$log", 0, acted.size)
                "heuristic" -> assertTrue("heuristics should act\n$log", acted.any { it.source == "heuristic" })
                "llm" -> assertTrue("model should be asked\n$log", synchronized(llmCalls) { file in llmCalls } && acted.any { it.source == "llm" })
                "autoconsent" -> assertTrue("autoconsent should answer it\n$log", s?.consentRejected()?.isNotEmpty() == true && acted.isEmpty())
            }
            if (exp["gone"] == null) assertEquals("nothing should be acted on\n$log", 0, acted.size)
        }
    }
}
