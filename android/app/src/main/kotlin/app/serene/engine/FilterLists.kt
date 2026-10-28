package app.serene.engine

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Filter list catalog, download, on-disk cache and engine build. Port of engine/src/host/lists.mjs.
 * Lists are fetched at runtime from upstream (never bundled) and refreshed as often as each list's
 * `! Expires:` header asks, between 12 hours and 4 days. The parsed engine is saved next to them,
 * so later launches skip parsing. [scriptlets] reads uBO's scriptlets, which the app ships
 * (engine/dist/scriptlets.json, built by `npm run build`). [selection] is read at each load, so
 * the last load always uses the latest settings.
 */
class FilterLists(private val dir: File, private val scriptlets: () -> String, private val selection: () -> Selection) {
    /**
     * [consent]: which part of the list is cookie-banner rules (see [consentPart]).
     * [trusted]: may use the scriptlets uBO reserves for its own lists (trusted-click-element, ...).
     * [custom]: a list the user added by URL; one that fails or has no rules is left out.
     */
    data class Source(val name: String, val url: String, val consent: String? = null, val trusted: Boolean = false, val custom: Boolean = false)

    /**
     * One switch in Settings › Filter lists. [on]: on by default. [section]: [ANNOYANCES]
     * (Serene's own job), [ADS] (ads and trackers), [REGIONAL] (ad lists for one region, offered
     * with "Block ads" and suggested by the phone's languages, [langs]).
     */
    data class Entry(val id: String, val section: String, val on: Boolean, val title: String, val summary: String, val lists: List<Source>, val langs: List<String> = emptyList())

    /** What to load: catalog entry ids, the user's own list URLs, and their own rules. */
    data class Selection(val enabled: Set<String> = DEFAULT_ENABLED, val custom: List<String> = emptyList(), val myFilters: String = "")

    data class ListStatus(val title: String?, val rules: Int, val bytes: Long, val updatedAt: Long, val error: String?)

    data class Status(val updatedAt: Long, val bytes: Long, val rules: Int, val lists: Map<String, ListStatus>)

    // v2: lists parsed with the trusted permission. An engine saved without it would keep
    // dropping uBO's trusted scriptlets, so it is not reused.
    // v3: parsed in debug mode, so a blocked request names its rule and list (the site sheet).
    private val engineFile = File(dir, "engine-v3.dat")
    private val consentFile = File(dir, "consent-v3.dat")
    // Which lists (and which own rules) the saved engine was built from.
    private val keyFile = File(dir, "engine-v3.key")
    private val legacy = listOf("engine.dat", "consent.dat", "resources.json", "engine-v2.dat", "consent-v2.dat", "engine-v2.key").map { File(dir, it) }

    /** Last download or parse problem per list file. */
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Title and rule count per list file, by its modification time (counting means reading it). */
    private val headers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String?, Int>>>()

    /** Per-list state for the settings screen, keyed by [Source.name]; null before any download. */
    fun status(sel: Selection = selection()): Status? {
        val lists = sourcesFor(sel).associate { s ->
            val f = File(dir, s.name)
            val (title, rules) = if (f.exists()) header(f) else null to 0
            s.name to ListStatus(title, rules, if (f.exists()) f.length() else 0, if (f.exists()) f.lastModified() else 0, errors[s.name])
        }
        val present = lists.values.filter { it.updatedAt > 0 }
        if (present.isEmpty()) return null
        return Status(present.minOf { it.updatedAt }, present.sumOf { it.bytes }, present.sumOf { it.rules }, lists)
    }

    private fun header(f: File): Pair<String?, Int> {
        val mtime = f.lastModified()
        headers[f.name]?.takeIf { it.first == mtime }?.let { return it.second }
        val text = f.readText()
        return (listTitle(text) to ruleCount(text)).also { headers[f.name] = mtime to it }
    }

    /**
     * Returns a ready engine: from the saved engine when the lists are fresh and unchanged,
     * otherwise after downloading what is stale. A stale copy beats no rules when the network is
     * down. On a [metered] network, lists over [METERED_MAX_BYTES] wait for an unmetered one
     * unless [force]d (a first download always happens).
     */
    @Synchronized
    fun load(force: Boolean = false, metered: Boolean = false): RulesEngine? {
        dir.mkdirs()
        legacy.forEach { it.delete() }
        val sel = selection()
        val sources = sourcesFor(sel)
        // Lists switched off no longer take space.
        dir.listFiles { f -> f.name.endsWith(".txt") && sources.none { it.name == f.name } }?.forEach { it.delete() }
        var changed = false
        for (s in sources) changed = fetch(s, force, metered) or changed
        val present = sources.mapNotNull { s -> File(dir, s.name).takeIf(File::exists)?.let { s to it.readText() } }.filter { (s, text) ->
            if (s.custom && ruleCount(text) == 0) { errors[s.name] = "no rules in this file"; false } else true
        }
        // Every list gets a title, so the site sheet can say which one blocked a request.
        val lists = present.map { (s, text) -> RulesEngine.ListText(titled(text, if (s.custom) s.url else s.name), s.trusted) } +
            listOfNotNull(sel.myFilters.takeIf { it.isNotBlank() }?.let { RulesEngine.ListText(titled(it, MY_FILTERS)) })
        if (lists.isEmpty()) return null
        val res = scriptlets()
        val key = cacheKey(present.map { it.first }, sel.myFilters)
        if (!changed && engineFile.exists() && consentFile.exists() && keyFile.takeIf(File::exists)?.readText() == key) {
            RulesEngine.fromSerialized(engineFile.readBytes(), res)?.let { engine ->
                engine.consent = RulesEngine.fromSerialized(consentFile.readBytes(), "[]")
                return engine
            }
        }
        val t0 = System.nanoTime()
        val engine = RulesEngine.fromLists(lists, res) ?: return null
        engine.consent = present.map { (s, text) -> consentPart(text, s.consent) }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.let { parts -> RulesEngine.fromLists(parts.map { RulesEngine.ListText(it) }, "[]") }
        Log.i(TAG, "parsed ${lists.size} lists in ${(System.nanoTime() - t0) / 1_000_000} ms")
        keyFile.delete()
        val saved = engine.serialize()?.let { bytes -> runCatching { engineFile.writeBytes(bytes) }.isSuccess } == true
        engine.consent?.serialize()?.let { bytes -> runCatching { consentFile.writeBytes(bytes) } }
        if (saved) runCatching { keyFile.writeText(key) }
        return engine
    }

    /** True when a new copy was written. */
    private fun fetch(s: Source, force: Boolean, metered: Boolean): Boolean {
        val file = File(dir, s.name)
        if (!force && file.exists()) {
            val head = file.bufferedReader().use { r -> CharArray(HEADER_CHARS).let { b -> String(b, 0, maxOf(0, r.read(b))) } }
            if (System.currentTimeMillis() - file.lastModified() < maxAgeMs(head)) return false
            if (metered && file.length() > METERED_MAX_BYTES) return false
        }
        var last: Exception? = null
        for (url in listOfNotNull(s.url, mirrorUrl(s.url))) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (file.exists()) c.ifModifiedSince = file.lastModified()
                val wrote = when (c.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> { file.setLastModified(System.currentTimeMillis()); false }
                    HttpURLConnection.HTTP_OK -> {
                        val tmp = File(dir, s.name + ".tmp")
                        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(file)
                        true
                    }
                    else -> throw IllegalStateException("HTTP ${c.responseCode}")
                }
                errors.remove(s.name)
                return wrote
            } catch (e: Exception) {
                last = e
            }
        }
        Log.w(TAG, "could not fetch ${s.url}: ${last?.message}")
        errors[s.name] = last?.message ?: "download failed"
        return false
    }

    companion object {
        private const val TAG = "SereneLists"
        private const val HEADER_CHARS = 4000
        const val MIN_AGE_MS = 12L * 3600 * 1000
        const val MAX_AGE_MS = 4L * 24 * 3600 * 1000
        /** Bigger lists (EasyList, EasyPrivacy, AdGuard Popups) refresh only on unmetered networks. */
        const val METERED_MAX_BYTES = 1_000_000L
        const val ALL = "all"
        /** The title the user's own rules go by in the site sheet. */
        const val MY_FILTERS = "My filters"
        const val EASYLIST_COOKIE_SECTIONS = "easylist-cookie-sections"
        const val ANNOYANCES = "annoyances"
        const val ADS = "ads"
        const val REGIONAL = "regional"
        private val SECTION = Regex("""^! \*\*\* easylist:(\S+) \*\*\*""")

        // EasyList's site is the canonical source, but the GitHub mirrors carry the same compiled
        // files. Fanboy's Annoyance List already includes the EasyList Cookie List. Only uBO's own
        // lists are trusted, as in uBO.
        private const val RAW = "https://raw.githubusercontent.com/"
        private const val UBO = RAW + "uBlockOrigin/uAssets/master/filters/"
        private const val ADGUARD = "https://filters.adtidy.org/extension/ublock/filters/"

        val CATALOG: List<Entry> = listOf(
            Entry("fanboy-annoyances", ANNOYANCES, true, "Fanboy's Annoyances", "Cookie notices, newsletters, social widgets, push prompts", listOf(
                Source("fanboy-annoyance.txt", RAW + "easylist/easylist/gh-pages/fanboy-annoyance.txt", consent = EASYLIST_COOKIE_SECTIONS),
            )),
            Entry("ubo-cookies", ANNOYANCES, true, "uBO Cookie Notices", "Cookie notices, often answered with \"reject\"", listOf(
                Source("annoyances-cookies.txt", UBO + "annoyances-cookies.txt", consent = ALL, trusted = true),
            )),
            Entry("ubo-annoyances", ANNOYANCES, true, "uBO Other Annoyances", "Popups, overlays, sticky nags", listOf(
                Source("annoyances-others.txt", UBO + "annoyances-others.txt", trusted = true),
            )),
            // "Open in app" bars and interstitials: AdGuard's list (its uBO-syntax build) and Fanboy's.
            Entry("app-banners", ANNOYANCES, true, "Mobile app banners", "\"Open in app\" bars and interstitials (AdGuard, Fanboy)", listOf(
                Source("adguard-mobile-app-banners.txt", ADGUARD + "20.txt"),
                Source("fanboy-mobile-notifications.txt", "https://secure.fanboy.co.nz/fanboy-mobile-notifications.txt"),
            )),
            Entry("more-popups", ANNOYANCES, false, "More popups and widgets", "AdGuard Popups, Other Annoyances and Widgets: catches more, and hides more by mistake", listOf(
                Source("adguard-popups.txt", ADGUARD + "19.txt"),
                Source("adguard-other-annoyances.txt", ADGUARD + "21.txt"),
                Source("adguard-widgets.txt", ADGUARD + "22.txt"),
            )),
            Entry("trackers", ADS, false, "Block trackers", "EasyPrivacy and uBO Privacy: blocks requests, hides nothing", listOf(
                Source("easyprivacy.txt", RAW + "easylist/easylist/gh-pages/easyprivacy.txt"),
                Source("ubo-privacy.txt", UBO + "privacy.txt", trusted = true),
            )),
            // uBO's own list and Quick fixes carry the ad-blocker-wall fixes; Unbreak undoes EasyList's breakage.
            Entry("ads", ADS, false, "Block ads", "EasyList and uBO filters, with their fixes for ad-blocker walls", listOf(
                Source("easylist.txt", RAW + "easylist/easylist/gh-pages/easylist.txt"),
                Source("ubo-filters.txt", UBO + "filters.txt", trusted = true),
                Source("ubo-unbreak.txt", UBO + "unbreak.txt", trusted = true),
                Source("ubo-quick-fixes.txt", UBO + "quick-fixes.txt", trusted = true),
            )),
        ) + listOf(
            listOf("et", "Estonian", "et", "https://ubo-et.lepik.io/list.txt"),
            listOf("fi", "Finnish", "fi", RAW + "finnish-easylist-addition/finnish-easylist-addition/gh-pages/Finland_adb.txt"),
            listOf("lv", "Latvian", "lv", RAW + "Latvian-List/adblock-latvian/master/lists/latvian-list.txt"),
            listOf("lt", "Lithuanian", "lt", RAW + "EasyList-Lithuania/easylist_lithuania/master/easylistlithuania.txt"),
            listOf("nordic", "Nordic", "nb,nn,no,da,is", RAW + "DandelionSprout/adfilt/master/NorwegianList.txt"),
            listOf("sv", "Swedish", "sv", RAW + "lassekongo83/Frellwits-filter-lists/master/Frellwits-Swedish-Filter.txt"),
            listOf("de", "German", "de", "https://easylist-downloads.adblockplus.org/easylistgermany.txt"),
            listOf("pl", "Polish", "pl", RAW + "MajkiIT/polish-ads-filter/master/polish-adblock-filters/adblock.txt"),
            listOf("ru", "Russian and Ukrainian", "ru,uk,be", "https://easylist-downloads.adblockplus.org/advblock.txt"),
            listOf("fr", "French", "fr", ADGUARD + "16.txt"),
            listOf("es", "Spanish", "es", "https://easylist-downloads.adblockplus.org/easylistspanish.txt"),
            listOf("it", "Italian", "it", "https://easylist-downloads.adblockplus.org/easylistitaly.txt"),
            listOf("nl", "Dutch", "nl", ADGUARD + "8.txt"),
        ).map { (code, title, langs, url) ->
            Entry("regional-$code", REGIONAL, false, title, "Ads on $title sites", listOf(Source("regional-$code.txt", url)), langs.split(','))
        }

        val DEFAULT_ENABLED: Set<String> = CATALOG.filter { it.on }.map { it.id }.toSet()

        /** A custom list's file name: stable for its URL. */
        fun customName(url: String): String =
            "custom-" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12) + ".txt"

        /** The catalog entries in [Selection.enabled], then the user's own list URLs (never trusted). */
        fun sourcesFor(sel: Selection): List<Source> =
            CATALOG.filter { it.id in sel.enabled }.flatMap { it.lists } + sel.custom.map { Source(customName(it), it, custom = true) }

        /** Regional entries for the phone's [languages] (ISO 639 codes, as Locale.getLanguage()). */
        fun suggestedRegional(languages: List<String>): List<String> =
            CATALOG.filter { e -> e.section == REGIONAL && e.langs.any { it in languages } }.map { it.id }

        private val EXPIRES = Regex("""^! *Expires: *(\d+) *(hour|day)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** The refresh interval a list asks for in its header, within [MIN_AGE_MS]..[MAX_AGE_MS]. */
        fun maxAgeMs(text: String): Long {
            val m = EXPIRES.find(text.take(HEADER_CHARS)) ?: return MAX_AGE_MS
            val n = m.groupValues[1].toLong()
            val ms = if (m.groupValues[2].equals("hour", ignoreCase = true)) n * 3600_000 else n * 24 * 3600_000
            return ms.coerceIn(MIN_AGE_MS, MAX_AGE_MS)
        }

        private val RAW_URL = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")

        /** jsDelivr's copy of a raw.githubusercontent.com file, tried when GitHub fails; else null. */
        fun mirrorUrl(url: String): String? =
            RAW_URL.find(url)?.destructured?.let { (owner, repo, branch, path) -> "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path" }

        private val TITLE = Regex("""^! *Title[^:]*: *(.+)$""", RegexOption.MULTILINE)

        /** A list's `! Title:` header, or null. */
        fun listTitle(text: String): String? = TITLE.find(text.take(HEADER_CHARS))?.groupValues?.get(1)?.trim()

        /**
         * [text] headed by its title as [listTitle] reads it (else [fallback]), on a first line in
         * the one form adblock-rust reads: it stops at a blank line and wants exactly "Title: ".
         */
        fun titled(text: String, fallback: String): String = "! Title: ${listTitle(text) ?: fallback}\n$text"

        /** Lines that are rules: not comments, headers or blank. */
        fun ruleCount(text: String): Int = text.lineSequence().count { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith('!') && !t.startsWith('[')
        }

        private fun cacheKey(sources: List<Source>, myFilters: String): String =
            (sources.map { "${it.name}:${it.trusted}" } + "my-filters:" + MessageDigest.getInstance("SHA-1").digest(myFilters.toByteArray()).joinToString("") { "%02x".format(it) })
                .joinToString("\n")

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
