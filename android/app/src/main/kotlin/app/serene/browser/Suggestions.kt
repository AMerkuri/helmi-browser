package app.serene.browser

import app.serene.bookmarks.Bookmark
import app.serene.bookmarks.Bookmarks
import app.serene.engine.EngineJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ln
import kotlin.math.pow

/** A page the address bar offers as you type: from the history, the bookmarks, or both. */
data class PageSuggestion(
    val url: String,
    val title: String,
    val bookmarked: Boolean,
    /** Visits in the history, all days added up (0: a bookmark never opened in the kept history). */
    val visits: Int,
    /** The last visit, or when it was bookmarked, in ms. */
    val lastAt: Long,
) {
    val label: String get() = title.ifBlank { url }
}

/** The field's inline completion: the [text] it shows, and the page Go opens ([url], with the scheme the page had). */
data class Completion(val text: String, val url: String)

/**
 * What the address bar suggests as you type, as in Chrome: pages from the history and bookmarks
 * (all on the phone), the address it completes in the field itself ([completion]), and the search
 * engine's own suggestions ([parseSearchSuggestions]), which come over the network.
 */
object Suggestions {
    const val MAX_PAGES = 5
    const val MAX_SEARCHES = 5

    /**
     * The pages that match [query] (every word, in the title or the address), best first: an address
     * that starts with what was typed, then a title or address with a word that starts with the
     * first word, then any other match; within those, bookmarks, pages visited often and lately.
     */
    fun pages(query: String, visits: List<Visit>, bookmarks: List<Bookmark>, now: Long, limit: Int = MAX_PAGES): List<PageSuggestion> {
        val words = History.words(query)
        if (words.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, PageSuggestion>()
        for (v in visits) {
            if (!v.matches(words)) continue
            val k = Bookmarks.key(v.url)
            val seen = byKey[k]
            byKey[k] = if (seen == null) PageSuggestion(v.url, v.title, false, v.count, v.at)
            else seen.copy(title = seen.title.ifBlank { v.title }, visits = seen.visits + v.count, lastAt = maxOf(seen.lastAt, v.at))
        }
        for (b in Bookmarks.rank(bookmarks, query)) {
            val k = Bookmarks.key(b.url)
            val seen = byKey[k]
            // The bookmark's name is the one its owner chose.
            byKey[k] = seen?.copy(title = b.title, bookmarked = true) ?: PageSuggestion(b.url, b.title, true, 0, b.added)
        }
        val typed = typedAddress(query)
        return byKey.values
            .map { it to score(it, typed, words.first().lowercase(), now) }
            .sortedWith(compareByDescending<Pair<PageSuggestion, Double>> { it.second }.thenByDescending { it.first.lastAt })
            .take(limit).map { it.first }
    }

    /**
     * The whole address the field completes [typed] to, as Chrome does: the site of a page visited
     * at least twice (or bookmarked) whose address starts with what was typed, and its path once
     * the typing goes past the site (a "/"). Its text starts with [typed] as typed; null when there
     * is nothing to add (a space in it, no such page). The site's page visited most gives the
     * completion's scheme and "www." (an http-only site stays http).
     */
    fun completion(typed: String, pages: List<PageSuggestion>): Completion? {
        if (typed.isEmpty() || typed.any(Char::isWhitespace)) return null
        val lower = typed.lowercase()
        val bareTyped = typedAddress(lower) ?: return null
        if (bareTyped.isEmpty()) return null
        val pastSite = '/' in bareTyped
        // By the target in lower case; the path keeps its own case (it can matter), the site doesn't.
        class Target(val text: String, var url: String, var weight: Int, var top: Int)
        val targets = LinkedHashMap<String, Target>()
        for (p in pages) {
            val address = p.url.substringAfter("://").let { if (it.startsWith("www.", ignoreCase = true)) it.substring(4) else it }
            if (!address.lowercase().startsWith(bareTyped)) continue
            val target = if (pastSite) address.trimEnd('/') else hostPart(address).lowercase()
            if (target.length <= bareTyped.length) continue
            // A bookmark counts as two visits: saved on purpose.
            val weight = p.visits + if (p.bookmarked) 2 else 0
            val prefix = p.url.substring(0, p.url.length - address.length) // scheme, "www." if it had one
            val t = targets.getOrPut(target.lowercase()) { Target(target, prefix + target, 0, weight) }
            t.weight += weight
            if (weight > t.top) { t.top = weight; t.url = prefix + target }
        }
        val best = targets.values.filter { it.weight >= 2 }
            .maxWithOrNull(compareBy<Target> { it.weight }.thenBy { -it.text.length }) // then the shorter
            ?: return null
        return Completion(typed + best.text.substring(bareTyped.length), best.url)
    }

    /**
     * The address part of [query] as it compares with [bare] addresses: lower case, no scheme, no
     * "www."; null when it can't be the start of an address (it has a space).
     */
    internal fun typedAddress(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty() || q.any(Char::isWhitespace)) return null
        val noScheme = when {
            q.startsWith("https://") -> q.removePrefix("https://")
            q.startsWith("http://") -> q.removePrefix("http://")
            else -> q
        }
        // "www." typed so far matches every site, as Chrome's does.
        return if ("www.".startsWith(noScheme)) "" else noScheme.removePrefix("www.")
    }

    /** An address as it is typed: lower case, no scheme, no "www.". */
    internal fun bare(url: String) = url.lowercase().substringAfter("://").removePrefix("www.")

    /** The host (and port) of a [bare] address. */
    private fun hostPart(bare: String) = bare.substringBefore('/').substringBefore('?').substringBefore('#')

    private fun score(p: PageSuggestion, typed: String?, firstWord: String, now: Long): Double {
        val bare = bare(p.url)
        val match = when {
            typed != null && typed.isNotEmpty() && bare.startsWith(typed) -> 1000.0 - minOf(bare.length, 100) * 2
            startsWord(p.title.lowercase(), firstWord) || startsWord(bare, firstWord) -> 400.0
            else -> 100.0
        }
        val days = (now - p.lastAt).coerceAtLeast(0) / 86_400_000.0
        return match + (if (p.bookmarked) 200.0 else 0.0) + 60 * ln(1.0 + p.visits) + 150 * 0.5.pow(days / 7)
    }

    private fun startsWord(text: String, word: String): Boolean {
        var i = text.indexOf(word)
        while (i >= 0) {
            if (i == 0 || !text[i - 1].isLetterOrDigit()) return true
            i = text.indexOf(word, i + 1)
        }
        return false
    }

    /**
     * The suggestions in an OpenSearch reply (`["query", ["one", "two"], …]`), as DuckDuckGo,
     * Google, Brave and Startpage send them; the query itself left out, at most [limit].
     */
    fun parseSearchSuggestions(body: String, query: String, limit: Int = MAX_SEARCHES): List<String> {
        val list = runCatching { (EngineJson.parseToJsonElement(body) as JsonArray)[1] as JsonArray }.getOrNull() ?: return emptyList()
        val q = query.trim()
        return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() && !it.equals(q, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    /** The search engine's suggestions for [query]; empty when it doesn't answer in time. Blocks: not on the main thread. */
    fun fetchSearchSuggestions(template: String, query: String): List<String> = runCatching {
        val url = URL(template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8")))
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json, application/x-suggestions+json")
        }
        try {
            if (c.responseCode != 200) return@runCatching emptyList()
            val body = c.inputStream.use { s -> String(readAtMost(s, MAX_REPLY_BYTES), Charsets.UTF_8) }
            parseSearchSuggestions(body, query)
        } finally {
            c.disconnect()
        }
    }.getOrDefault(emptyList())

    private const val MAX_REPLY_BYTES = 64 * 1024

    private fun readAtMost(s: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
