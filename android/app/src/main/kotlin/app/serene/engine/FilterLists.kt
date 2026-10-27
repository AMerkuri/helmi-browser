package app.serene.engine

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Filter list download, on-disk cache and engine build. Port of engine/src/host/lists.mjs.
 * Lists are fetched at runtime from upstream (never bundled) and refreshed every 4 days. The
 * parsed engine is saved next to them, so later launches skip parsing. [scriptlets] reads uBO's
 * scriptlets, which the app ships (engine/dist/scriptlets.json, built by `npm run build`).
 */
class FilterLists(private val dir: File, private val scriptlets: () -> String) {
    /**
     * [consent]: which part of the list is cookie-banner rules (see [consentPart]).
     * [trusted]: may use the scriptlets uBO reserves for its own lists (trusted-click-element, ...).
     */
    data class Source(val name: String, val url: String, val consent: String? = null, val trusted: Boolean = false)

    data class Status(val updatedAt: Long, val bytes: Long, val error: String? = null)

    // Fanboy's Annoyance List already includes the EasyList Cookie List.
    val sources = listOf(
        Source("fanboy-annoyance.txt", "https://raw.githubusercontent.com/easylist/easylist/gh-pages/fanboy-annoyance.txt", consent = EASYLIST_COOKIE_SECTIONS),
        Source("annoyances-cookies.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt", consent = ALL, trusted = true),
        Source("annoyances-others.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt", trusted = true),
    )

    // v2: lists parsed with the trusted permission. An engine saved without it would keep
    // dropping uBO's trusted scriptlets, so it is not reused.
    private val engineFile = File(dir, "engine-v2.dat")
    private val consentFile = File(dir, "consent-v2.dat")
    private val legacy = listOf("engine.dat", "consent.dat", "resources.json").map { File(dir, it) }

    fun status(): Status? {
        val files = sources.map { File(dir, it.name) }
        if (!files.all { it.exists() }) return null
        return Status(files.minOf { it.lastModified() }, files.sumOf { it.length() })
    }

    /**
     * Returns a ready engine: from the saved engine when the lists are fresh, otherwise after
     * downloading what is stale. A stale copy beats no rules when the network is down.
     */
    fun load(force: Boolean = false): RulesEngine? {
        dir.mkdirs()
        legacy.forEach { it.delete() }
        var changed = false
        for (s in sources) changed = fetch(s, force) or changed
        val present = sources.mapNotNull { s -> File(dir, s.name).takeIf(File::exists)?.let { s to it.readText() } }
        val res = scriptlets()
        if (present.isEmpty()) return null
        if (!changed && engineFile.exists() && consentFile.exists()) {
            RulesEngine.fromSerialized(engineFile.readBytes(), res)?.let { engine ->
                engine.consent = RulesEngine.fromSerialized(consentFile.readBytes(), "[]")
                return engine
            }
        }
        val t0 = System.nanoTime()
        val engine = RulesEngine.fromLists(present.map { (s, text) -> RulesEngine.ListText(text, s.trusted) }, res) ?: return null
        engine.consent = present.map { (s, text) -> consentPart(text, s.consent) }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.let { parts -> RulesEngine.fromLists(parts.map { RulesEngine.ListText(it) }, "[]") }
        Log.i(TAG, "parsed ${present.size} lists in ${(System.nanoTime() - t0) / 1_000_000} ms")
        engine.serialize()?.let { bytes -> runCatching { engineFile.writeBytes(bytes) } }
        engine.consent?.serialize()?.let { bytes -> runCatching { consentFile.writeBytes(bytes) } }
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
        const val ALL = "all"
        const val EASYLIST_COOKIE_SECTIONS = "easylist-cookie-sections"
        private val SECTION = Regex("""^! \*\*\* easylist:(\S+) \*\*\*""")

        /** The cookie-banner part of a list: all of it, the EasyList Cookie sections of a combined list, or none. */
        fun consentPart(text: String, consent: String?): String = when (consent) {
            ALL -> text
            EASYLIST_COOKIE_SECTIONS -> {
                var on = false
                text.lineSequence().filter { line ->
                    val m = SECTION.find(line)
                    if (m != null) { on = m.groupValues[1].startsWith("easylist_cookie/"); false } else on
                }.joinToString("\n")
            }
            else -> ""
        }
    }
}
