package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app shows about the model's work: counts, the site sheet's left-alone list, the Flags note. */
class ModelDecisionsTest {
    private val top = object : FrameRef {
        override val isTop = true
        override fun send(json: String) {}
    }

    /** Between the heuristics' thresholds, so the model decides. */
    private fun candidate(id: Int, sig: String, cached: Boolean = false) =
        """{"type":"candidate","id":$id,"cached":$cached,"features":{"sig":"$sig","text":"Get our weekly letter","buttons":["Sign up","No thanks"],""" +
            """"coverage":0.3,"edge":"center","heuristic":{"label":"newsletter","score":0.5,"structure":0.2,"rejectIdx":1}}}"""

    private fun PageSession.waitFor(n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (candidates().size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n, candidates().size)
    }

    @Test
    fun modelVerdictsAreCountedShownAndCanBeOverruled() {
        val events = mutableListOf<String>()
        val cache = VerdictCache()
        // The model calls the first box a newsletter and the second page content.
        val model = object : Classifier {
            override suspend fun classify(f: Features) = ClassifierResult(if (f.sig == "div.promo") "newsletter" else "content", -1, 400)
        }
        val engine = SereneEngine(rules = null, cache = cache, classifier = { model }, siteEnabled = { true })
        engine.events = object : EngineEvents {
            override fun modelCall(ms: Long, acted: Boolean) { events += "call $ms $acted" }
            override fun modelOverruled(wasHidden: Boolean) { events += "overruled $wasHidden" }
        }
        val page = engine.newPage()
        page.onMessage("""{"type":"hello","url":"https://news.example/a"}""", top)
        page.onMessage(candidate(1, "div.promo"), top)
        page.waitFor(1)
        page.onMessage(candidate(2, "div.letter"), top)
        page.waitFor(2)

        assertEquals(listOf("call 400 true", "call 400 false"), events)
        val (hid, left) = page.candidates()
        assertTrue(hid.byModel && hid.acted)
        assertEquals(listOf(left.features.sig), page.leftByModel().map { it.features.sig })
        assertEquals(listOf(1, 2), page.takeModelNotices().map { it.id })
        assertEquals(emptyList<Int>(), page.takeModelNotices().map { it.id }) // each note once

        // Hide on the one it left alone: a hide rule for the site, under the heuristics' kind.
        page.hide(left)
        val rule = cache.rulesFor("https://news.example/b").single { it.sig == "div.letter" }
        assertEquals(listOf("hide", "newsletter", "user-hide"), listOf(rule.action, rule.label, rule.source))
        page.undo(hid)
        assertEquals(listOf("call 400 true", "call 400 false", "overruled false", "overruled true"), events)
        // Listed for the sheet's Show again, which drops the rule.
        assertEquals(listOf("div.letter"), page.hiddenByUser().map { it.sig })
        page.showAgain(page.hiddenByUser().single())
        assertTrue(page.hiddenByUser().isEmpty())

        // A later visit: the remembered verdict still says who made it.
        cache.put("https://news.example/a", SiteRule(sig = "div.left", label = "content", action = "ignore", source = "llm"))
        val next = engine.newPage()
        next.onMessage("""{"type":"hello","url":"https://news.example/c"}""", top)
        next.onMessage(candidate(1, "div.left", cached = true), top)
        next.waitFor(1)
        val remembered = next.candidates().single()
        assertEquals("llm", remembered.decidedBy)
        assertEquals(listOf("div.left"), next.leftByModel().map { it.features.sig })
        assertFalse(next.takeModelNotices().isNotEmpty()) // a remembered verdict is not a new decision
    }
}
