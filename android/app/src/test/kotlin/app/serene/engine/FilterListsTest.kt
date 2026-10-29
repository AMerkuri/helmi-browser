package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors engine/test/lists.test.mjs: the list catalog and refresh rules.
class FilterListsTest {
    private val h = 3600_000L

    @Test fun defaultsAreTheAnnoyanceLists() {
        assertEquals(listOf("fanboy-annoyances", "ubo-cookies", "ubo-annoyances", "app-banners"), FilterLists.CATALOG.filter { it.on }.map { it.id })
        assertTrue(FilterLists.CATALOG.filter { it.section != FilterLists.ANNOYANCES }.none { it.on })
        assertEquals(FilterLists.DEFAULT_ENABLED, FilterLists.Selection().enabled)
        val all = FilterLists.CATALOG.flatMap { it.lists }
        assertEquals("file names are unique", all.size, all.map { it.name }.toSet().size)
        // Only uBO's own lists may use trusted scriptlets.
        for (l in all) assertEquals(l.name, l.url.contains("/uBlockOrigin/uAssets/"), l.trusted)
        assertTrue("et" in FilterLists.CATALOG.first { it.id == "regional-et" }.langs)
    }

    @Test fun customListsComeLastAndAreNeverTrusted() {
        val url = "https://example.com/list.txt"
        val s = FilterLists.sourcesFor(FilterLists.Selection(enabled = setOf("ads"), custom = listOf(url)))
        assertEquals(listOf("easylist.txt", "ubo-filters.txt", "ubo-unbreak.txt", "ubo-quick-fixes.txt", FilterLists.customName(url)), s.map { it.name })
        assertFalse(s.last().trusted)
        assertTrue(s.last().custom)
        // The same file name as the JS host gives it.
        assertEquals("custom-6770a52271d4.txt", FilterLists.customName(url))
    }

    @Test fun regionalListsAreSuggestedByLanguage() {
        assertEquals(listOf("regional-et"), FilterLists.suggestedRegional(listOf("et", "en")))
        assertEquals(listOf("regional-nordic"), FilterLists.suggestedRegional(listOf("nb")))
        assertEquals(emptyList<String>(), FilterLists.suggestedRegional(listOf("en")))
    }

    @Test fun expiresSetsTheRefreshBetween12HoursAnd4Days() {
        assertEquals(24 * h, FilterLists.maxAgeMs("! Title: x\n! Expires: 1 days (update frequency)\n"))
        assertEquals(12 * h, FilterLists.maxAgeMs("! Expires: 8 hours"))
        assertEquals(96 * h, FilterLists.maxAgeMs("! Expires: 7 days"))
        assertEquals(24 * h, FilterLists.maxAgeMs("! Expires: 1 day"))
        assertEquals(96 * h, FilterLists.maxAgeMs("##.ad"))
    }

    @Test fun githubListsFallBackToJsDelivr() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@master/filters/filters.txt",
            FilterLists.mirrorUrl("https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"),
        )
        assertNull(FilterLists.mirrorUrl("https://filters.adtidy.org/extension/ublock/filters/20.txt"))
    }

    @Test fun titlesAndRuleCounts() {
        val text = "[Adblock Plus 2.0]\n! Title: My list\n! Expires: 1 day\n\nexample.com##.promo\n||ads.example^\n"
        assertEquals("My list", FilterLists.listTitle(text))
        assertEquals("Dandelion Sprout's Nordic filters", FilterLists.listTitle("! Title 🇬🇧: Dandelion Sprout's Nordic filters\n"))
        assertEquals(2, FilterLists.ruleCount(text))
        assertEquals(1, FilterLists.ruleCount("<!doctype html><html>"))
        assertEquals(0, FilterLists.ruleCount("! only comments\n\n"))
    }

    @Test fun everyListIsTitledForTheEngine() {
        assertEquals("! Title: My list\n! Title: My list\n||a.example^", FilterLists.titled("! Title: My list\n||a.example^", "x"))
        // Titles adblock-rust would miss (after a blank line, a flag before the colon) are repeated first.
        assertTrue(FilterLists.titled("[Adblock Plus 2.0]\n\n! Title 🇬🇧: Nordic\n", "x").startsWith("! Title: Nordic\n"))
        assertEquals("! Title: My filters\n##.promo", FilterLists.titled("##.promo", FilterLists.MY_FILTERS))
    }

    // The site sheet shows which line pressed a cookie banner's button (app only, no JS twin).
    @Test fun findsTheScriptletLineForAHost() {
        val text = """
            ! Title: uBO Cookie Notices
            example.com##+js(trusted-click-element, .other)
            swedbank.ee##+js(trusted-click-element, button.ui-cookie-consent__decline-button, , 1000)
            shop.*,~beta.shop.de##+js(trusted-click-element, #reject)
        """.trimIndent()
        val rule = "+js(trusted-click-element, button.ui-cookie-consent__decline-button, , 1000)"
        assertEquals("swedbank.ee##$rule", FilterLists.scriptletLine(text, rule, "www.swedbank.ee"))
        assertNull(FilterLists.scriptletLine(text, rule, "swedbank.se"))
        assertNull("other arguments", FilterLists.scriptletLine(text, "+js(trusted-click-element, button.ui-cookie-consent__decline-button)", "swedbank.ee"))
        assertEquals("shop.*,~beta.shop.de##+js(trusted-click-element, #reject)", FilterLists.scriptletLine(text, "+js(trusted-click-element,#reject)", "www.shop.de"))
        assertNull("excluded subdomain", FilterLists.scriptletLine(text, "+js(trusted-click-element, #reject)", "beta.shop.de"))
    }
}
