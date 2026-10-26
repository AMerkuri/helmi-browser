package app.serene.reader

import android.content.Context
import android.webkit.WebView
import app.serene.engine.EngineJson
import kotlinx.serialization.Serializable

/**
 * A page's article, as engine/dist/reader.js (Mozilla Readability + src/content/reader-entry.js)
 * returns it: clean HTML for the reader view. Page content, so untrusted: the reader view shows
 * it with scripts off.
 */
@Serializable
data class ReaderPage(
    val url: String = "",
    /** Readability thinks the page is an article. Otherwise [html] is its best guess. */
    val article: Boolean = false,
    val title: String = "",
    val byline: String = "",
    val siteName: String = "",
    val excerpt: String = "",
    val publishedTime: String = "",
    val lang: String = "",
    val dir: String = "",
    val html: String = "",
    val words: Int = 0,
    val error: String? = null,
) {
    /** Minutes to read at 230 words a minute, at least one. */
    val minutes: Int get() = maxOf(1, (words + 115) / 230)

    companion object {
        private var script: String? = null

        private fun script(context: Context): String = script
            ?: context.assets.open("reader.js").bufferedReader().use { it.readText() }.also { script = it }

        /** Runs the extractor on the page in [webView]; [done] gets null when the page gave nothing usable. */
        fun extract(webView: WebView, done: (ReaderPage?) -> Unit) {
            webView.evaluateJavascript("${script(webView.context)}\n;__sereneReader()") { json ->
                done(parse(json))
            }
        }

        /** evaluateJavascript's answer: the returned object as JSON, or "null". */
        fun parse(json: String?): ReaderPage? {
            if (json.isNullOrEmpty() || json == "null") return null
            val page = runCatching { EngineJson.decodeFromString(serializer(), json) }.getOrNull() ?: return null
            return page.takeIf { it.error == null && it.html.isNotBlank() }
        }
    }
}
