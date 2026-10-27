package app.serene.report

import app.serene.engine.EngineJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder

class IssueReportTest {
    private fun report(logLines: Int = 5, html: String = "<div class=\"nl-box\">Subscribe to our newsletter</div>") = buildJsonObject {
        put("url", "https://news.example.com/story?session=s3cr3t&email=a%40b.c#top")
        put("app", "0.1 (42, abc1234)")
        put("device", "Pixel 8, Android 15 (SDK 35)")
        put("webview", "130.0.6723.58")
        put("rules", "3 lists")
        put("model", "Ready")
        put("candidates", JsonArray(listOf(buildJsonObject {
            put("id", 1)
            put("features", buildJsonObject { put("sig", "div.nl-box") })
            put("label", "content"); put("action", "ignore"); put("source", "heuristic")
        })))
        put("log", JsonArray((1..logLines).map { JsonPrimitive("${it * 10}ms some log line number $it with detail") }))
        put("snapshot", buildJsonObject {
            put("engineStarted", true)
            put("overlays", JsonArray(listOf(buildJsonObject { put("sig", "div.nl-box"); put("coverage", 0.4); put("html", html) })))
        })
    }

    private fun params(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun fillsTheTemplateFields() {
        val url = IssueReport.url(report(), "Box after scrolling")
        assertTrue(url.startsWith("https://github.com/${IssueReport.REPO}/issues/new?template=missed-popup.yml&"))
        val p = params(url)
        assertEquals("Missed popup: news.example.com", p["title"])
        assertEquals("https://news.example.com/story", p["url"]) // no query string or fragment
        assertEquals("Box after scrolling", p["note"])
        assertTrue(p.getValue("app").contains("Pixel 8"))
        assertTrue(p.getValue("engine").contains("#1 div.nl-box content/ignore via heuristic"))
        assertTrue(p.getValue("overlay").contains("<div class=\"nl-box\">"))
        assertFalse(url.contains("s3cr3t"))
    }

    @Test fun dropsQueryStringsInsideTheLogAndHtml() {
        val r = buildJsonObject {
            for ((k, v) in report()) put(k, v)
            put("log", JsonArray(listOf(
                JsonPrimitive("350ms hello from top https://news.example.com/story?session=s3cr3t#top"),
                JsonPrimitive("815ms hello from frame https://login.example.net/frame?state=st4te99&origin=x"),
                JsonPrimitive("900ms blocked script https://ads.example.org/tag.js"),
            )))
            put("snapshot", buildJsonObject {
                put("overlays", JsonArray(listOf(buildJsonObject {
                    put("sig", "div.nl-box")
                    put("html", "<a href=\"https://news.example.com/unsub?email=a%40b.c&amp;id=7\">Unsubscribe</a><img src='https://cdn.example.com/logo.png#token=xyz'>")
                })))
            })
        }
        val url = IssueReport.url(r, "n")
        for (secret in listOf("s3cr3t", "st4te99", "a%40b.c", "token=xyz")) assertFalse(secret, URLDecoder.decode(url, "UTF-8").contains(secret))
        val p = params(url)
        assertTrue(p.getValue("engine").contains("hello from top https://news.example.com/story\n"))
        assertTrue(p.getValue("engine").contains("hello from frame https://login.example.net/frame\n"))
        assertTrue(p.getValue("engine").contains("blocked script https://ads.example.org/tag.js"))
        assertTrue(p.getValue("overlay").contains("<a href=\"https://news.example.com/unsub\">Unsubscribe</a><img src='https://cdn.example.com/logo.png'>"))
    }

    @Test fun trimsToTheUrlLimit() {
        val url = IssueReport.url(report(logLines = 200, html = "<div>" + "x".repeat(6000) + "</div>"), "note")
        assertTrue("${url.length}", url.length <= IssueReport.MAX_URL)
        val p = params(url)
        assertEquals("note", p["note"])
        assertTrue(p.getValue("overlay").endsWith("…(trimmed)"))
    }

    // The query parameters are the form's field ids; keep the two in step.
    @Test fun matchesTheIssueForm() {
        val form = File("../../.github/ISSUE_TEMPLATE/missed-popup.yml").readText()
        val ids = Regex("""^\s+id: (\w+)""", RegexOption.MULTILINE).findAll(form).map { it.groupValues[1] }.toSet()
        val keys = params(IssueReport.url(report(), "n")).keys - setOf("template", "title")
        assertTrue("$keys not in $ids", ids.containsAll(keys))
    }
}

class UniformTest {
    @Test fun blankAndNot() {
        assertTrue(Reporter.isUniform(IntArray(256) { -1 }))
        assertFalse(Reporter.isUniform(IntArray(256) { if (it == 200) 0xFFCC0000.toInt() else -1 }))
    }
}
