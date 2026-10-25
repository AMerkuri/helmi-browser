package app.serene.engine

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Filter list download, on-disk cache and engine build. Port of engine/src/host/lists.mjs.
 * Lists are fetched at runtime from upstream (never bundled) and refreshed every 4 days. The
 * parsed engine is saved next to them, so later launches skip parsing.
 */
class FilterLists(private val dir: File) {
    data class Source(val name: String, val url: String)

    data class Status(val updatedAt: Long, val bytes: Long, val error: String? = null)

    // Fanboy's Annoyance List already includes the EasyList Cookie List.
    val sources = listOf(
        Source("fanboy-annoyance.txt", "https://raw.githubusercontent.com/easylist/easylist/gh-pages/fanboy-annoyance.txt"),
        Source("annoyances-cookies.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt"),
        Source("annoyances-others.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt"),
    )
    // uBO scriptlets/redirect resources in the format adblock-rust's use_resources() expects.
    private val resources = Source("resources.json", "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json")

    private val engineFile = File(dir, "engine.dat")

    fun status(): Status? {
        val files = (sources + resources).map { File(dir, it.name) }
        if (!files.all { it.exists() }) return null
        return Status(files.minOf { it.lastModified() }, files.sumOf { it.length() })
    }

    /**
     * Returns a ready engine: from the saved engine when the lists are fresh, otherwise after
     * downloading what is stale. A stale copy beats no rules when the network is down.
     */
    fun load(force: Boolean = false): RulesEngine? {
        dir.mkdirs()
        var changed = false
        for (s in sources + resources) changed = fetch(s, force) or changed
        val texts = sources.mapNotNull { File(dir, it.name).takeIf(File::exists)?.readText() }
        val res = File(dir, resources.name).takeIf(File::exists)?.readText() ?: "[]"
        if (texts.isEmpty()) return null
        if (!changed && engineFile.exists()) {
            RulesEngine.fromSerialized(engineFile.readBytes(), res)?.let { return it }
        }
        val t0 = System.nanoTime()
        val engine = RulesEngine.fromLists(texts, res) ?: return null
        Log.i(TAG, "parsed ${texts.size} lists in ${(System.nanoTime() - t0) / 1_000_000} ms")
        engine.serialize()?.let { bytes -> runCatching { engineFile.writeBytes(bytes) } }
        return engine
    }

    /** True when a new copy was written. */
    private fun fetch(s: Source, force: Boolean): Boolean {
        val file = File(dir, s.name)
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return false
        return try {
            val c = URL(s.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (file.exists()) c.ifModifiedSince = file.lastModified()
            when (c.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> { file.setLastModified(System.currentTimeMillis()); false }
                HttpURLConnection.HTTP_OK -> {
                    val tmp = File(dir, s.name + ".tmp")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                    true
                }
                else -> throw IllegalStateException("HTTP ${c.responseCode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not fetch ${s.url}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "SereneLists"
        const val MAX_AGE_MS = 4L * 24 * 3600 * 1000
    }
}
