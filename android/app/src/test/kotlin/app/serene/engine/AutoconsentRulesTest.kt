package app.serene.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The expected rule sets are what autoconsent's own filterCompactRules returns for the same
// URLs (engine/node_modules/@duckduckgo/autoconsent, 16.61.0).
class AutoconsentRulesTest {
    private val dist = File("../../engine/dist")
    private val ac = AutoconsentRules(File(dist, "autoconsent-rules.json").readText(), File(dist, "autoconsent-config.json").readText())

    private fun names(url: String, isTop: Boolean) =
        ac.forFrame(url, isTop)["rules"]!!.jsonObject["compact"]!!.jsonObject["r"]!!.jsonArray.map { it.jsonArray[1].jsonPrimitive.content }

    @Test fun rulesForEachFrameMatchFilterCompactRules() {
        val generic = names("https://example.org/", true)
        assertEquals(423, generic.size)
        assertTrue("sourcepoint-top" in generic)
        val bbc = names("https://www.bbc.com/", true)
        assertEquals(425, bbc.size)
        assertEquals(listOf("bbc.com", "bbc.com-orb-cosmetic"), bbc - generic.toSet())
        assertEquals(listOf("cookiealert", "piano.io"), names("https://cdn.privacy-mgmt.com/index.html?message_id=1", false))
    }

    @Test fun configIsOptOutOnly() {
        val config = ac.forFrame("https://example.org/", true)["config"]!!.jsonObject
        assertEquals("optOut", config["autoAction"]!!.jsonPrimitive.content)
        assertTrue(config["disabledCmps"]!!.jsonArray.any { it.jsonPrimitive.content == "healthline-media" })
    }

    @Test fun consentPartOfTheLists() {
        val combined = """
            ! *** easylist:fanboy-addon/fanboy_annoyance_general_hide.txt ***
            ##.newsletter-modal
            ! *** easylist:easylist_cookie/easylist_cookie_general_hide.txt ***
            ##.cookie-banner
            ###onetrust-banner-sdk
            ! *** easylist:fanboy-addon/fanboy_social_general_hide.txt ***
            ##.share-bar
        """.trimIndent()
        assertEquals("##.cookie-banner\n###onetrust-banner-sdk", FilterLists.consentPart(combined, FilterLists.EASYLIST_COOKIE_SECTIONS))
        assertEquals(combined, FilterLists.consentPart(combined, FilterLists.ALL))
        assertEquals("", FilterLists.consentPart(combined, null))
    }
}
