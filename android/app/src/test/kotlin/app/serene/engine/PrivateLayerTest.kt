package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Private tabs' verdict cache: a layer that reads the normal one but writes only to itself, so a
 * site visited privately never reaches site-rules.json; and their pages count in no totals.
 */
class PrivateLayerTest {
    private val top = object : FrameRef {
        override val isTop = true
        override fun send(json: String) {}
    }

    private fun rule(sig: String, source: String = "heuristic", label: String = "newsletter", action: String = "hide") =
        SiteRule(sig = sig, label = label, action = action, source = source)

    @Test
    fun layerReadsBelowAndKeepsItsOwnRulesToItself() {
        val file = Files.createTempFile("site-rules", ".json").toFile().apply { delete() }
        val normal = VerdictCache(file)
        normal.put("https://news.example/a", rule("div.promo"))
        val written = file.readText()
        val layer = VerdictCache(under = normal)

        // Read through: the normal tabs' verdicts apply in private tabs.
        assertEquals(listOf("div.promo"), layer.rulesFor("https://news.example/b").map { it.sig })
        assertTrue(layer.cssFor("https://news.example/").contains("div.promo"))

        // Learned privately: in the layer only, the file untouched.
        layer.put("https://secret.example/", rule("div.box"))
        layer.undo("https://news.example/", "div.promo", "newsletter")
        assertEquals(listOf("div.box"), layer.rulesFor("https://secret.example/x").map { it.sig })
        assertEquals("content", layer.rulesFor("https://news.example/").single { it.sig == "div.promo" }.label)
        assertTrue(layer.userAnswersCookies("https://news.example/").not())
        assertEquals(emptyList<SiteRule>(), normal.rulesFor("https://secret.example/"))
        assertEquals("hide", normal.rulesFor("https://news.example/").single().action)
        assertEquals(written, file.readText())

        // Hide again on the private undo: the element is judged afresh, as in a normal tab.
        layer.remove("https://news.example/", "div.promo")
        assertEquals(emptyList<SiteRule>(), layer.rulesFor("https://news.example/"))
        // Forgetting a site in a private tab hides the normal tabs' rules there too, in the layer only.
        normal.put("https://shop.example/", rule("div.chat", label = "chat_widget"))
        normal.put("https://shop.example/", rule("div.kept", source = "user", label = "content", action = "ignore"))
        layer.clear("shop.example", keepUndone = true)
        assertEquals(listOf("div.kept"), layer.rulesFor("https://shop.example/").map { it.sig })
        assertEquals(2, normal.rulesFor("https://shop.example/").size)
        // A verdict learned again comes back.
        layer.put("https://shop.example/", rule("div.chat", label = "chat_widget"))
        assertEquals(setOf("div.kept", "div.chat"), layer.rulesFor("https://shop.example/").map { it.sig }.toSet())
        layer.clear()
        assertEquals(emptyList<SiteRule>(), layer.rulesFor("https://shop.example/"))
        assertEquals(2, normal.rulesFor("https://shop.example/").size)
        File(file.path + ".tmp").delete()
        file.delete()
    }

    @Test
    fun privatePagesAreNotCounted() {
        val events = mutableListOf<String>()
        val engine = SereneEngine(rules = null, cache = VerdictCache(), classifier = { null }, siteEnabled = { true })
        engine.events = object : EngineEvents {
            override fun page(site: String) { events += "page $site" }
        }
        val layer = VerdictCache(under = engine.cache)
        engine.newPage(cache = layer, counted = false).onMessage("""{"type":"hello","url":"https://secret.example/","top":true}""", top)
        assertEquals(emptyList<String>(), events)
        engine.newPage().onMessage("""{"type":"hello","url":"https://news.example/","top":true}""", top)
        assertEquals(listOf("page news.example"), events)
    }
}
