package app.serene.browser

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ⋮ › Find in page, on one page's WebView: every match of the text highlighted as it is typed
 * (findAllAsync), the current one shown, the next and previous one a tap away. The bar holding it
 * goes away on a new page, another tab, or a new WebView after a crash; [close] clears the
 * highlights.
 */
class FindInPage(val webView: WebView) {
    /** The text looked for. */
    var query by mutableStateOf("")
        private set

    /** Which match is shown, from 0, of [total]. */
    var active by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    init {
        // One listener per WebView, and nothing else sets it.
        webView.setFindListener { activeMatch, matches, _ ->
            // A late answer to a search the field has since been emptied of.
            if (query.isEmpty()) return@setFindListener
            active = activeMatch.coerceAtLeast(0)
            total = matches
        }
    }

    fun search(text: String) {
        if (text == query) return
        query = text
        if (text.isEmpty()) {
            webView.clearMatches()
            active = 0
            total = 0
        } else {
            webView.findAllAsync(text)
        }
    }

    /** The next match ([forward]) or the one before, round from the last to the first. */
    fun next(forward: Boolean) {
        if (total > 0) webView.findNext(forward)
    }

    fun close() {
        // The tab may have been closed and its WebView destroyed.
        runCatching {
            webView.setFindListener(null)
            webView.clearMatches()
        }
    }

    /** "3/12" as in Chrome; "0/0" when nothing matches; nothing while there is no text. */
    val count: String get() = countLabel(query, active, total)
}

internal fun countLabel(query: String, active: Int, total: Int): String = when {
    query.isEmpty() -> ""
    total <= 0 -> "0/0"
    else -> "${active.coerceIn(0, total - 1) + 1}/$total"
}
