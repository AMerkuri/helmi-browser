package app.serene.engine

import com.google.common.net.InternetDomainName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.net.URI

/** Registrable domain (eTLD+1) of a URL, or its host when there is none (IPs, localhost). */
fun siteOf(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return url
    return runCatching {
        val d = InternetDomainName.from(host)
        if (d.isUnderRegistrySuffix) d.topDomainUnderRegistrySuffix().toString() else host
    }.getOrDefault(host)
}

@Serializable
data class SiteRule(
    val sig: String,
    val label: String,
    val action: String,
    val buttonLabel: String? = null,
    val source: String,
    /** For an undo: the label the element had before the user said it was content. */
    val was: String? = null,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * Per-site verdict cache. Key: eTLD+1, then the candidate's stable selector signature.
 * A decision made once (by heuristics or the model) becomes a local rule for later visits.
 * Port of engine/src/host/cache.mjs, persisted as one JSON file.
 *
 * With [under], a layer over another cache (private tabs, app.serene.browser.PrivateBrowsing): it
 * reads [under]'s rules as its own, but what it learns or is told stays in it, in memory, so no
 * site visited in a private tab ends up in [under]'s file. A rule of [under] taken out here
 * (Hide again, Show again, Forget this site) is only hidden from this layer.
 */
class VerdictCache(private val file: File? = null, private val under: VerdictCache? = null) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(SiteRule.serializer()))
    private val sites: MutableMap<String, MutableList<SiteRule>> = mutableMapOf()
    // For a layer: [under]'s rules taken out here, by site, then signature.
    private val dropped: MutableMap<String, MutableSet<String>> = mutableMapOf()
    // For a layer: everything was forgotten, [under]'s rules too.
    private var droppedAll = false

    init {
        val f = file
        if (f != null && f.exists()) {
            runCatching { EngineJson.decodeFromString(serializer, f.readText()) }
                .getOrNull()?.forEach { (k, v) -> sites[k] = v.toMutableList() }
        }
    }

    @Synchronized fun rulesFor(url: String): List<SiteRule> = rulesForSite(siteOf(url))

    @Synchronized private fun rulesForSite(site: String): List<SiteRule> {
        val own = sites[site]?.toList() ?: emptyList()
        val below = under?.takeIf { !droppedAll } ?: return own
        val gone = dropped[site].orEmpty()
        return below.rulesForSite(site).filter { r -> r.sig !in gone && own.none { it.sig == r.sig } } + own
    }

    @Synchronized fun put(url: String, rule: SiteRule) {
        dropped[siteOf(url)]?.remove(rule.sig)
        val list = sites.getOrPut(siteOf(url)) { mutableListOf() }
        val i = list.indexOfFirst { it.sig == rule.sig }
        val entry = rule.copy(ts = System.currentTimeMillis())
        if (i >= 0) list[i] = entry else list += entry
        save()
    }

    /** User pressed undo: remember the element as content for this site. [was] keeps what it was
     *  taken for, so the site sheet can name it and offer to hide it again. */
    fun undo(url: String, sig: String, was: String? = null) =
        put(url, SiteRule(sig = sig, label = "content", action = "ignore", source = "user", was = was))

    /** Elements the user undid on this site. */
    fun undone(url: String): List<SiteRule> = rulesFor(url).filter { it.source == "user" }

    /** Elements the user hid on this site (Hide on one the model left alone). */
    fun hiddenByUser(url: String): List<SiteRule> = rulesFor(url).filter { it.source == "user-hide" }

    /** The user put a cookie banner back with Undo: they answer it themselves on this site, so
     *  neither autoconsent nor a list's scriptlet presses its buttons. */
    fun userAnswersCookies(url: String): Boolean = undone(url).any { it.was == "cookie_consent" }

    /** Drop one rule, so the element is judged afresh on the next load (reverses an undo). */
    @Synchronized fun remove(url: String, sig: String) {
        val site = siteOf(url)
        if (under != null) dropped.getOrPut(site) { mutableSetOf() } += sig
        val list = sites[site] ?: return
        list.removeAll { it.sig == sig }
        if (list.isEmpty()) sites.remove(site)
        save()
    }

    /**
     * Forget everything learned for one site, or for all sites. [keepUndone] keeps the user's Undo
     * rules. A layer forgets its own rules and hides [under]'s for the site.
     */
    @Synchronized fun clear(site: String? = null, keepUndone: Boolean = false) {
        if (under != null) {
            if (site == null) { dropped.clear(); droppedAll = true }
            else under.rulesForSite(site).filter { !keepUndone || it.source != "user" }.forEach { dropped.getOrPut(site) { mutableSetOf() } += it.sig }
        }
        if (site == null) sites.clear()
        else if (!keepUndone) sites.remove(site)
        else sites[site]?.let { list -> list.removeAll { it.source != "user" }; if (list.isEmpty()) sites.remove(site) }
        save()
    }

    @Synchronized fun siteCount(): Int = sites.size

    /** Cached hide verdicts as cosmetic CSS, injected at page start so there is no flash. With
     *  [parkCookies] (autoconsent on) cookie banners are left out: they go to [cookieSelectorsFor].
     *  An element the user asked to see again (the content script marks it) is left alone. */
    fun cssFor(url: String, parkCookies: Boolean = false): String = hideRules(url)
        .filter { !(parkCookies && it.label == "cookie_consent") }
        .joinToString("\n") { "${it.sig}:not([$SHOWN]){display:none!important}" }

    /** Cached cookie banners as consent selectors: parked while autoconsent tries to reject them,
     *  which it cannot do to a banner hidden from the start (techspot, #24). */
    fun cookieSelectorsFor(url: String): List<String> = hideRules(url).filter { it.label == "cookie_consent" }.map { it.sig }

    private companion object {
        /** Set by the content script on an overlay the user asked to see again ("Manage cookies"). */
        const val SHOWN = "data-serene-shown"
    }

    private fun hideRules(url: String) = rulesFor(url).filter { it.action == "hide" && (it.sig.contains('#') || it.sig.contains('.')) }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(EngineJson.encodeToString(serializer, sites))
            tmp.renameTo(f)
        }
    }
}
