package app.serene.stats

import app.serene.engine.EngineEvents
import app.serene.engine.EngineJson
import app.serene.engine.FrameRef
import app.serene.engine.SereneEngine
import app.serene.engine.VerdictCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun totalsAddUp() {
        var t = Totals(since = 0)
        repeat(3) { t = t.withBlocked("example.com", "script") }
        t = t.withBlocked("example.com", "image").withBlocked("news.example", "xmlhttprequest")
        t = t.withCleared("news.example", "cookie_consent").withCleared("news.example", "newsletter").withPage().withModelCall(800, acted = true)
        assertEquals(5, t.requestsBlocked)
        assertEquals(2, t.annoyances)
        assertEquals(3L, t.requests["script"])
        assertEquals(listOf("example.com" to 4L, "news.example" to 3L), t.busiestSites(5))
        // Saved and read back as the app does.
        assertEquals(t, EngineJson.decodeFromString(Totals.serializer(), EngineJson.encodeToString(Totals.serializer(), t)))
    }

    @Test
    fun theQuieterSitesGoWhenThereAreTooMany() {
        var t = Totals(since = 0)
        repeat(5) { t = t.withBlocked("busy.example", "script") }
        for (i in 0 until Totals.MAX_SITES) t = t.withBlocked("site$i.example", "script")
        assertTrue(t.sites.size <= Totals.MAX_SITES)
        assertEquals(5L, t.sites["busy.example"])
        assertEquals(Totals.MAX_SITES + 5L, t.requestsBlocked) // the totals themselves stay
    }

    /** A page has one cookie banner, however many ways it was dealt with. */
    @Test
    fun aCookieBannerCountsOncePerPage() {
        val seen = mutableListOf<String>()
        val engine = SereneEngine(rules = null, cache = VerdictCache(), classifier = { null }, siteEnabled = { true })
        engine.events = object : EngineEvents {
            override fun page(site: String) { seen += "page $site" }
            override fun cleared(site: String, label: String) { seen += "cleared $site $label" }
            override fun consentRejected(site: String) { seen += "rejected $site" }
        }
        val top = object : FrameRef {
            override val isTop = true
            override fun send(json: String) {}
        }
        val page = engine.newPage()
        page.onMessage("""{"type":"hello","url":"https://www.bbc.co.uk/news"}""", top)
        page.onMessage("""{"type":"listHit","label":"cookie_consent"}""", top)
        page.onMessage("""{"type":"autoconsent","origin":"https://www.bbc.co.uk","event":"optOutResult","cmp":"Example","result":true}""", top)
        page.onMessage("""{"type":"autoconsent","origin":"https://cmp.example","event":"optOutResult","cmp":"Example","result":true}""", top)
        page.onMessage("""{"type":"listHit","label":"cookie_consent"}""", top)
        assertEquals(listOf("page bbc.co.uk", "cleared bbc.co.uk cookie_consent", "rejected bbc.co.uk"), seen)
    }
}
