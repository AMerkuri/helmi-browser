package app.serene.browser

import app.serene.engine.EngineJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Each open tab's back/forward list, kept over a restart so back and the history swipes still
 * go somewhere after the app was killed. Only the saved tabs' URLs were kept before, so a
 * restored tab had a history of one page. It is what WebView.saveState gives (WebView's own
 * bytes: the pages' URLs and titles, scroll positions, form fields; not the pages themselves),
 * one [Saved] per tab in the order of the saved URLs.
 */
object TabHistory {
    /** A tab's [url] and its WebView state, the Bundle's byte arrays by key, in base64. */
    @Serializable
    data class Saved(val url: String, val state: Map<String, String> = emptyMap()) {
        fun bytes(): Map<String, ByteArray> = runCatching { state.mapValues { Base64.getDecoder().decode(it.value) } }.getOrDefault(emptyMap())

        companion object {
            fun of(url: String, bytes: Map<String, ByteArray>) = Saved(url, bytes.mapValues { Base64.getEncoder().encodeToString(it.value) })
        }
    }

    private val serializer = ListSerializer(Saved.serializer())

    // One writer, and reads queue behind it: an activity recreated right after onStop reads what it wrote.
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tab-history") }

    /** Writes [tabs] to [file], off the main thread. */
    fun save(file: File, tabs: List<Saved>) {
        io.execute { write(file, tabs) }
    }

    /** What [save] wrote last; empty when there is nothing (or nothing readable). */
    fun load(file: File): List<Saved> = io.submit<List<Saved>> { read(file) }.get()

    /**
     * The history saved for each of [urls], the saved tabs, by position: a tab whose saved history
     * is of another page (the two were written at different times) gets none and loads its URL.
     */
    fun forTabs(urls: List<String>, saved: List<Saved>): List<Saved?> =
        urls.mapIndexed { i, u -> saved.getOrNull(i)?.takeIf { it.url == u && it.state.isNotEmpty() } }

    internal fun write(file: File, tabs: List<Saved>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(EngineJson.encodeToString(serializer, tabs))
            tmp.renameTo(file)
        }
    }

    internal fun read(file: File): List<Saved> =
        runCatching { if (file.exists()) EngineJson.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())
}
