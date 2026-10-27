package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors engine/test/policy.test.mjs, so the Kotlin port decides exactly like the prototype.
class PolicyTest {
    private val base = Features(
        sig = "div.x", tag = "div", text = "We use cookies", textLen = 14, buttons = listOf("Accept all", "Reject all"),
        coverage = 0.2, edge = "bottom",
    )

    @Test fun onlyDeclineOrCloseButtonsAreSafe() {
        for (s in listOf("Reject all", "Nur notwendige Cookies", "Continuer sans accepter", "No thanks", "×", "Not now", "Rifiuta")) assertTrue(s, Policy.isSafeTarget(s))
        for (s in listOf("Accept all", "Allow", "Subscribe", "Open in app", "Sign in", "Got it", "Alle akzeptieren", "Yes, notify me", null)) assertFalse("$s", Policy.isSafeTarget(s))
        // Beyond Latin script: decline words count, agree words never do.
        for (s in listOf("拒否する", "同意しない", "閉じる", "拒绝", "不同意", "거부", "동의하지 않음", "Отклонить", "Не сейчас", "Reddet", "Stäng")) assertTrue(s, Policy.isSafeTarget(s))
        for (s in listOf("同意する", "同意して閉じる", "接受", "동의", "Принять", "Kabul et", "Godkänn alla")) assertFalse(s, Policy.isSafeTarget(s))
    }

    @Test fun modelPickingAcceptIsOverruled() {
        val v = Policy.actionFor("cookie_consent", 0, base)
        assertEquals("click", v.action)
        assertEquals("Reject all", v.buttonLabel)
        assertEquals("hide", Policy.actionFor("cookie_consent", 0, base.copy(buttons = listOf("Accept all", "Settings"))).action)
    }

    @Test fun rejectAndSubscribeIsNotADecline() {
        for (s in listOf("Rifiuta e abbonati", "Ablehnen und abonnieren", "Refuser et s'abonner", "Rechazar y suscribirse")) assertFalse(s, Policy.isSafeTarget(s))
        assertEquals("hide", Policy.actionFor("cookie_consent", 2, base.copy(buttons = listOf("ACCETTA E CONTINUA", "PREFERENZE", "Rifiuta e abbonati"))).action)
    }

    @Test fun siteHeadersNeverGoToTheModel() {
        fun hdr(f: Features) = f.copy(edge = "top", coverage = 0.07, textLen = 35)
        val weak = Heuristic("newsletter", 0.3, 0.0, -1)
        val headers = listOf(
            hdr(base.copy(tag = "header", text = "Skip to main content Log in Sign up", heuristic = weak)),
            hdr(base.copy(tag = "header", text = "Anmelden Menü öffnen", heuristic = weak)),
            hdr(base.copy(tokens = "sticky-nav", text = "Subscribe", heuristic = Heuristic("newsletter", 0.9, 0.0, -1))),
        )
        for (f in headers) {
            assertTrue(Policy.looksLikeChrome(f))
            val v = Policy.preDecide(f, llmAvailable = true)
            assertTrue(f.text, !v.needsLlm && v.action == "ignore")
        }
        // a thin cookie bar at the top is still handled
        val bar = hdr(base.copy(text = "We use cookies. OK Reject", heuristic = Heuristic("cookie_consent", 0.7, 0.0, 1)))
        assertEquals("click", Policy.preDecide(bar, llmAvailable = true).action)
        assertFalse(Policy.looksLikeChrome(bar.copy(edge = "bottom")))
    }

    @Test fun bottomTabBarsAndLinkHeavyHeadersNeverGoToTheModel() {
        val weak = Heuristic("login_wall", 0.3, 0.0, -1)
        // booking's bottom nav (the model called it a login wall), a tab bar, and a top bar of section links
        val navs = listOf(
            base.copy(tag = "nav", role = "navigation", edge = "bottom", coverage = 0.07, textLen = 20, text = "Search Saved Sign in", buttons = listOf("Search", "Saved", "Sign in"), heuristic = weak),
            base.copy(tokens = "tabbar", edge = "bottom", coverage = 0.06, textLen = 30, text = "Home Live Audio", buttons = listOf("Home", "Live", "Audio"), heuristic = weak),
            base.copy(edge = "top", coverage = 0.1, textLen = 300, text = "Se connecter Politique International", buttons = listOf("Menu", "Notifications", "Search", "Se connecter", "Politique", "International"), heuristic = weak),
        )
        for (f in navs) {
            assertTrue(f.text, Policy.looksLikeChrome(f))
            assertEquals(f.text, "ignore", Policy.preDecide(f, llmAvailable = true).action)
        }
        // an app banner at the bottom still goes to the model
        assertFalse(Policy.looksLikeChrome(base.copy(tokens = "smart-app-banner", edge = "bottom", coverage = 0.1, textLen = 40, buttons = listOf("Open", "Not now"))))
    }

    @Test fun modelVerdictsPolicyPicksTheButtonUnsureLabelsAreLeftAlone() {
        val v = Policy.fromModel("cookie_consent", 0.97, base)
        assertEquals("click", v.action)
        assertEquals("Reject all", v.buttonLabel)
        assertEquals("ignore", Policy.fromModel("cookie_consent", 0.5, base).action)
        assertEquals("llm-unsure", Policy.fromModel("cookie_consent", 0.5, base).source)
        assertEquals("generate mode has no confidence", "click", Policy.fromModel("cookie_consent", null, base).action)
        assertEquals("ignore", Policy.fromModel("content", 0.3, base).action)
    }

    @Test fun paywallAndLoginAreHiddenNeverClicked() {
        assertEquals("hide", Policy.actionFor("paywall_teaser", 0, base.copy(buttons = listOf("Close"))).action)
        assertEquals("hide", Policy.actionFor("login_wall", 0, base.copy(buttons = listOf("Close"))).action)
        assertEquals("ignore", Policy.actionFor("content", 1, base).action)
    }

    @Test fun heuristicRouting() {
        fun h(label: String, score: Double, structure: Double = 0.2, rejectIdx: Int = 1) = base.copy(heuristic = Heuristic(label, score, structure, rejectIdx))
        assertEquals("heuristic", Policy.preDecide(h("cookie_consent", 0.95), true).source)
        assertTrue(Policy.preDecide(h("cookie_consent", 0.5), true).needsLlm)
        assertEquals("click", Policy.preDecide(h("cookie_consent", 0.7), false).action)
        assertEquals("ignore", Policy.preDecide(h("cookie_consent", 0.5), false).action)
        assertTrue("modal-shaped, no keywords → model", Policy.preDecide(h("content", 0.0, 0.35), true).needsLlm)
        assertEquals("ignore", Policy.preDecide(h("content", 0.0, 0.1), true).action)
        assertEquals("ignore", Policy.preDecide(h("cookie_consent", 0.95).copy(userInitiated = true), true).action)
    }

    @Test fun promptStaysCompactAndRepliesAreValidated() {
        val p = Prompt.build(base.copy(text = "x".repeat(1000)))
        assertTrue("prompt is ${p.length} chars", p.length < 600)
        assertTrue(p.contains("[1] Reject all"))
        assertEquals(Prompt.Reply("newsletter", 1), Prompt.parse("""{"label":"newsletter","target":1}""", 2))
        assertEquals(Prompt.Reply("survey", -1), Prompt.parse("""<think></think>{"label":"survey","target":9}""", 2))
        assertNull(Prompt.parse("""{"label":"banana","target":0}""", 2))
        assertNull(Prompt.parse("nope", 2))
    }

    @Test fun cacheKeysByRegistrableDomainAndEmitsCss() {
        assertEquals("bbc.co.uk", siteOf("https://www.bbc.co.uk/news"))
        val c = VerdictCache()
        c.put("https://a.example.com/x", SiteRule(sig = "div.nl-modal", label = "newsletter", action = "hide", source = "llm"))
        c.put("https://b.example.com/y", SiteRule(sig = "div > div", label = "newsletter", action = "hide", source = "llm"))
        assertEquals(2, c.rulesFor("https://example.com/").size)
        assertEquals("div.nl-modal{display:none!important}", c.cssFor("https://example.com/"))
        // With autoconsent on, a cookie banner is parked as a consent selector instead of hidden.
        c.put("https://example.com/", SiteRule(sig = "div#cmp", label = "cookie_consent", action = "hide", source = "heuristic"))
        assertEquals("div.nl-modal{display:none!important}", c.cssFor("https://example.com/", parkCookies = true))
        assertEquals(listOf("div#cmp"), c.cookieSelectorsFor("https://example.com/"))
        c.undo("https://example.com/", "div.nl-modal")
        assertEquals("ignore", c.rulesFor("https://example.com/")[0].action)
    }

    @Test fun featuresDecodeFromTheContentScript() {
        val json = """{"sig":"div#cmp","tag":"div","buttons":["Reject"],"coverage":0.42,"edge":"bottom","zIndex":2147483647,"heuristic":{"label":"cookie_consent","score":0.9,"structure":0.3,"rejectIdx":0,"scores":{"cookie_consent":0.9}},"_extra":1}"""
        val f = EngineJson.decodeFromString(Features.serializer(), json)
        assertEquals("cookie_consent", f.heuristic.label)
        assertEquals(0.42, f.coverage, 1e-9)
        assertEquals("click", Policy.preDecide(f, llmAvailable = true).action)
    }
}
