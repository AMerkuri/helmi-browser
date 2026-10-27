package app.serene.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder

/**
 * "Report missed popup" as a GitHub issue: the report Reporter.capture() saved, laid out in the
 * fields of .github/ISSUE_TEMPLATE/missed-popup.yml and passed as query parameters, so the user
 * submits a prefilled form from their own GitHub account. No token in the app.
 */
object IssueReport {
    const val REPO = "AMerkuri/serene-browser"
    private const val TEMPLATE = "missed-popup.yml"

    /** GitHub and browsers take longer URLs, but past ~8 KB some proxies and servers give up. */
    const val MAX_URL = 8000
    private const val LOG_LINES = 40

    /** The new-issue URL for [report] (report.json) and the user's [note]. */
    fun url(report: JsonObject, note: String, maxUrl: Int = MAX_URL): String {
        val pageUrl = report.str("url").orEmpty()
        val fields = linkedMapOf(
            "title" to "Missed popup: ${host(pageUrl)}",
            "url" to stripQuery(pageUrl),
            "note" to note.trim(),
            "app" to listOfNotNull(report.str("app"), report.str("device"), report.str("webview")?.let { "WebView $it" }).joinToString(", "),
            "engine" to stripQueries(engineText(report)),
            "overlay" to stripQueries(overlayText(report)),
        )
        // Trim the two big fields, longest first, until the URL fits; then the note, if it must.
        var out = build(fields)
        var guard = 0
        while (out.length > maxUrl && guard++ < 100) {
            val key = listOf("overlay", "engine").maxBy { fields.getValue(it).length }
                .takeIf { fields.getValue(it).length > 200 } ?: "note"
            val v = fields.getValue(key)
            if (v.isEmpty()) break
            fields[key] = cut(v, (v.length * 0.8).toInt())
            out = build(fields)
        }
        return out
    }

    private fun build(fields: Map<String, String>) = buildString {
        append("https://github.com/$REPO/issues/new?template=$TEMPLATE")
        for ((k, v) in fields) if (v.isNotEmpty()) append('&').append(k).append('=').append(encode(v))
    }

    // URLEncoder writes spaces as '+'; %20 reads the same everywhere.
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun cut(s: String, n: Int): String {
        if (s.length <= n) return s
        val marker = "\n…(trimmed)"
        val keep = (n - marker.length).coerceAtLeast(0)
        val at = s.lastIndexOf('\n', keep).takeIf { it > keep / 2 } ?: keep
        return s.take(at) + marker
    }

    private fun engineText(r: JsonObject) = buildString {
        appendLine("rules: ${r.str("rules")}")
        appendLine("model: ${r.str("model")} (${r.str("modelId")})")
        appendLine("engine on: ${r.str("engineEnabled")}, site on: ${r.str("siteEnabled")}, document-start script: ${r.str("documentStartScript")}, blocked requests: ${r.str("blockedRequests")}")
        val candidates = r["candidates"]?.jsonArray.orEmpty()
        appendLine("candidates: ${if (candidates.isEmpty()) "none" else ""}")
        for (c in candidates) {
            val o = c.jsonObject
            val f = o["features"]?.jsonObject
            append("  #${o.str("id")} ${f?.str("sig")} ")
            append("${o.str("label") ?: "?"}/${o.str("action") ?: "?"} via ${o.str("source") ?: "?"}")
            o.str("buttonLabel")?.let { append(" \"$it\"") }
            o.str("applied")?.let { append(" applied=$it") }
            appendLine()
        }
        val log = r["log"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        appendLine("log (last ${minOf(log.size, LOG_LINES)} of ${log.size}):")
        log.takeLast(LOG_LINES).forEach { appendLine("  $it") }
    }.trimEnd()

    private fun overlayText(r: JsonObject): String {
        val snap = r["snapshot"] as? JsonObject ?: return "<!-- no snapshot: the page did not answer -->"
        val overlays = snap["overlays"] as? JsonArray ?: JsonArray(emptyList())
        return buildString {
            appendLine("<!-- engine started: ${snap.str("engineStarted")}, scroll locked: ${snap.str("scrollLocked")}, viewport: ${snap["viewport"]} -->")
            if (overlays.isEmpty()) appendLine("<!-- nothing fixed or sticky on top of the page -->")
            for (o in overlays) {
                val f = o.jsonObject
                val h = f["heuristic"] as? JsonObject
                appendLine("<!-- ${f.str("sig") ?: f.str("tag")}: coverage ${f.str("coverage")}, edge ${f.str("edge")}, heuristic ${h?.str("label")} ${h?.str("score")}, serene=${f.str("serene")} -->")
                appendLine(f.str("html") ?: f.str("text").orEmpty())
            }
        }.trimEnd()
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url.take(60)

    /** Query strings and fragments often carry session ids and emails; issues are public. */
    fun stripQuery(url: String) = url.substringBefore('#').substringBefore('?')

    // The page log ("hello from top <href>", frame and blocked request URLs) and the overlay's
    // HTML (links, image and iframe sources) carry addresses too.
    private val URL_TAIL = Regex("""(https?://[^\s?#"'<>`]+)[?#][^\s"'<>`]*""")

    /** [stripQuery] for every http(s) address inside [text]. */
    fun stripQueries(text: String) = text.replace(URL_TAIL, "$1")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
