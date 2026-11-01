package app.serene.bookmarks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.engine.EngineJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors

/**
 * A saved page. [folder] is the folder path it was imported under (outermost first), kept so an
 * export puts it back where it came from; bookmarks saved in Helmi have none.
 */
@Serializable
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    /** When it was saved, in ms. */
    val added: Long,
    val folder: List<String> = emptyList(),
)

/** What an import did: bookmarks added, and ones left out (already saved, or not a web page). */
data class ImportResult(val added: Int, val skipped: Int)

/**
 * The bookmarks, newest first, as Compose state, saved to [file] (bookmarks.json) after each
 * change. Changed on the main thread; written on a thread of its own.
 */
class Bookmarks(private val file: File?) {
    var items by mutableStateOf(load())
        private set

    /** The bookmark for [url], if it is saved. A trailing slash makes no difference. */
    fun find(url: String): Bookmark? {
        val k = key(url)
        return items.firstOrNull { key(it.url) == k }
    }

    fun isSaved(url: String) = find(url) != null

    /** Saves [url] (once: an already saved page keeps its bookmark). */
    fun add(url: String, title: String, now: Long = System.currentTimeMillis()): Bookmark {
        find(url)?.let { return it }
        val b = Bookmark(nextId(), url, title.trim().ifEmpty { url }, now)
        change(listOf(b) + items)
        return b
    }

    fun remove(id: Long) = change(items.filterNot { it.id == id })

    /** Puts [b] back where it was, after a delete was undone. */
    fun restore(b: Bookmark, index: Int) {
        if (items.any { it.id == b.id }) return
        change(items.toMutableList().apply { add(index.coerceIn(0, size), b) })
    }

    /** A new name and address for bookmark [id]; a blank name becomes the address. */
    fun edit(id: Long, title: String, url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        change(items.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { u }, url = u) else it })
    }

    /** Bookmarks matching [query], best first (see [rank]). For the bookmarks screen and address bar suggestions. */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<Bookmark> = rank(items, query, limit)

    /**
     * Adds what a bookmarks file held, after the saved ones, in the file's order. Pages already
     * saved (or twice in the file) and addresses that aren't web pages (javascript: bookmarklets,
     * Firefox's place: queries) are skipped.
     */
    fun import(entries: List<BookmarksHtml.Entry>, now: Long = System.currentTimeMillis()): ImportResult {
        val seen = items.mapTo(HashSet()) { key(it.url) }
        var id = nextId()
        val added = entries.mapNotNull { e ->
            if (!isWebAddress(e.url) || !seen.add(key(e.url))) null
            else Bookmark(id++, e.url, e.title.trim().ifEmpty { e.url }, e.added ?: now, e.folder)
        }
        if (added.isNotEmpty()) change(items + added)
        return ImportResult(added.size, entries.size - added.size)
    }

    private fun nextId() = (items.maxOfOrNull { it.id } ?: 0) + 1

    private fun change(new: List<Bookmark>) {
        items = new
        val f = file ?: return
        io.execute { write(f, new) }
    }

    private fun load(): List<Bookmark> = file?.let(::read) ?: emptyList()

    companion object {
        private val serializer = ListSerializer(Bookmark.serializer())
        private val io = Executors.newSingleThreadExecutor { Thread(it, "bookmarks") }

        internal fun write(file: File, items: List<Bookmark>) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(EngineJson.encodeToString(serializer, items))
                tmp.renameTo(file)
            }
        }

        internal fun read(file: File): List<Bookmark> =
            runCatching { if (file.exists()) EngineJson.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

        /** Lets the writer finish (tests). */
        internal fun flush() = io.submit {}.get()

        internal fun key(url: String) = url.trim().removeSuffix("/")

        private fun isWebAddress(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

        /** The address as it is typed: no scheme, no www. */
        private fun bare(url: String) = url.lowercase().substringAfter("://").removePrefix("www.")

        /**
         * The bookmarks whose title or address holds every word of [query] (any case), best first:
         * an address that starts with the query (what was typed so far), then a title or address
         * where a word starts with the first word, then any other match; newer first among equals.
         * A blank query is all of them, newest first.
         */
        fun rank(items: List<Bookmark>, query: String, limit: Int = Int.MAX_VALUE): List<Bookmark> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return items.sortedByDescending { it.added }.take(limit)
            val words = q.split(Regex("\\s+"))
            val first = words.first()
            val typed = bare(q)
            return items.mapNotNull { b ->
                val title = b.title.lowercase()
                val url = bare(b.url)
                if (!words.all { title.contains(it) || url.contains(it) }) return@mapNotNull null
                val score = when {
                    url.startsWith(typed) -> 0
                    startsWord(title, first) || startsWord(url, first) -> 1
                    else -> 2
                }
                score to b
            }.sortedWith(compareBy<Pair<Int, Bookmark>> { it.first }.thenByDescending { it.second.added })
                .take(limit).map { it.second }
        }

        private fun startsWord(text: String, word: String): Boolean {
            var i = text.indexOf(word)
            while (i >= 0) {
                if (i == 0 || !text[i - 1].isLetterOrDigit()) return true
                i = text.indexOf(word, i + 1)
            }
            return false
        }
    }
}
