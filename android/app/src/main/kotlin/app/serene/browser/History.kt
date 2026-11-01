package app.serene.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.engine.EngineJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * A page in the browsing history: [url] and its [title], last visited [at] (ms), visited [count]
 * times that day. As in Chrome's history list, a page shows once a day: visiting it again the
 * same day moves it to the top.
 */
@Serializable
data class Visit(
    @SerialName("u") val url: String,
    @SerialName("t") val title: String = "",
    @SerialName("a") val at: Long,
    @SerialName("n") val count: Int = 1,
) {
    /** The title, or the URL for a page without one. */
    val label: String get() = title.ifBlank { url }

    /** Every word of [words] is in the title or the URL (any case). */
    fun matches(words: List<String>) = words.all { title.contains(it, ignoreCase = true) || url.contains(it, ignoreCase = true) }
}

/**
 * The pages visited (http and https only), newest first, as Compose state for the History
 * screen. The tabs record each step of their back/forward lists ([visit]) and the titles that
 * come after ([titled]). Kept [KEEP_DAYS] days (at most [MAX_ENTRIES] pages) in [file], saved a
 * few seconds after a change and on [save]. [file] null: in memory only (tests).
 *
 * [search] is the title/URL lookup the address bar's suggestions build on.
 */
class History(
    private val file: File?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Newest first. Read on the main thread; changes come from the main thread too (and [file]'s load). */
    var entries by mutableStateOf<List<Visit>>(emptyList())
        private set
    private var loaded = file == null
    private var saving: Job? = null

    init {
        if (file != null) scope.launch(io) {
            val old = read(file)
            synchronized(this@History) {
                // Visits made while the file was read go on top of it.
                entries = merge(entries, old)
                loaded = true
            }
        }
    }

    /** A step to [url] in a tab (not a reload). */
    fun visit(url: String, title: String = "") {
        if (!recorded(url)) return
        val t = now()
        val name = title.takeUnless { urlLike(it, url) }.orEmpty()
        change { list ->
            val day = dayOf(t)
            val same = list.indexOfFirst { it.url == url && dayOf(it.at) == day }
            val v = if (same < 0) Visit(url, name, t) else list[same].let { it.copy(title = name.ifBlank { it.title }, at = t, count = it.count + 1) }
            listOf(v) + (if (same < 0) list else list.filterIndexed { i, _ -> i != same })
        }
    }

    /** The page at [url] got its title (it comes after the step), or changed it. */
    fun titled(url: String?, title: String?) {
        if (url == null || title.isNullOrBlank() || urlLike(title, url)) return
        change { list ->
            val i = list.indexOfFirst { it.url == url }
            if (i < 0 || list[i].title == title) list else list.toMutableList().also { it[i] = it[i].copy(title = title) }
        }
    }

    /** History screen: the entries matching [query] (all words, title or URL), newest first. */
    fun matching(query: String): List<Visit> {
        val words = words(query)
        return if (words.isEmpty()) entries else entries.filter { it.matches(words) }
    }

    /**
     * Each page matching [query] once, newest visit first, with its visits of every day added up in
     * [Visit.count]: for suggestions as you type. At most [limit].
     */
    fun search(query: String, limit: Int = 10): List<Visit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val byUrl = LinkedHashMap<String, Visit>()
        for (v in entries) {
            if (!v.matches(words)) continue
            val seen = byUrl[v.url]
            if (seen == null) {
                if (byUrl.size >= limit) continue
                byUrl[v.url] = v
            } else {
                byUrl[v.url] = seen.copy(count = seen.count + v.count)
            }
        }
        return byUrl.values.toList()
    }

    fun delete(v: Visit) = change { list -> list.filterNot { it == v } }

    /** Every page visited at or after [from] (ms); 0 is all of it. */
    fun deleteSince(from: Long) = change { list -> if (from <= 0) emptyList() else list.filter { it.at < from } }

    fun clear() = deleteSince(0)

    /** Writes the history now (the app is going to the background). */
    fun save() {
        val f = file ?: return
        val list = synchronized(this) { if (!loaded) return else entries }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(EngineJson.encodeToString(serializer, list))
            tmp.renameTo(f)
        }
    }

    private fun change(f: (List<Visit>) -> List<Visit>) {
        synchronized(this) {
            entries = trim(f(entries))
            if (file == null || saving?.isActive == true) return
            saving = scope.launch(io) { delay(SAVE_DELAY_MS); save() }
        }
    }

    private fun trim(list: List<Visit>): List<Visit> {
        val oldest = now() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        return if (list.size <= MAX_ENTRIES && (list.lastOrNull()?.at ?: oldest) >= oldest) list
        else list.asSequence().filter { it.at >= oldest }.take(MAX_ENTRIES).toList()
    }

    /** [mine] (made since start) on top of what [file] had, a page once a day. */
    private fun merge(mine: List<Visit>, old: List<Visit>): List<Visit> {
        if (mine.isEmpty()) return trim(old)
        val days = mine.map { it.url to dayOf(it.at) }.toSet()
        return trim(mine + old.filterNot { (it.url to dayOf(it.at)) in days })
    }

    private fun dayOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()

    private fun read(f: File): List<Visit> =
        runCatching { if (f.exists()) EngineJson.decodeFromString(serializer, f.readText()) else emptyList() }.getOrDefault(emptyList())
            .sortedByDescending { it.at }

    companion object {
        const val KEEP_DAYS = 90L
        const val MAX_ENTRIES = 10_000
        const val SAVE_DELAY_MS = 5_000L
        private val serializer = ListSerializer(Visit.serializer())

        /** Web pages only: not about:blank, data:, file: or the app's own pages. */
        fun recorded(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        /** WebView's stand-in title for a page without one (or before it has one): its URL, maybe without the scheme. */
        fun urlLike(title: String, url: String): Boolean {
            if (title.isBlank()) return true
            fun bare(s: String) = s.trim().lowercase().substringAfter("://").removePrefix("www.").trimEnd('/')
            val t = bare(title)
            return t == bare(url) || (title.none(Char::isWhitespace) && ('/' in t || '.' in t) && bare(url).startsWith(t))
        }

        fun words(query: String) = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}
