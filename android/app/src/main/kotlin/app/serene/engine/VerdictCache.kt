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
 */
class VerdictCache(private val file: File? = null) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(SiteRule.serializer()))
    private val sites: MutableMap<String, MutableList<SiteRule>> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists()) {
            runCatching { EngineJson.decodeFromString(serializer, f.readText()) }
                .getOrNull()?.forEach { (k, v) -> sites[k] = v.toMutableList() }
        }
    }

    @Synchronized fun rulesFor(url: String): List<SiteRule> = sites[siteOf(url)]?.toList() ?: emptyList()

    @Synchronized fun put(url: String, rule: SiteRule) {
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

    /** Drop one rule, so the element is judged afresh on the next load (reverses an undo). */
    @Synchronized fun remove(url: String, sig: String) {
        val site = siteOf(url)
        val list = sites[site] ?: return
        list.removeAll { it.sig == sig }
        if (list.isEmpty()) sites.remove(site)
        save()
    }

    /** Forget everything learned for one site, or for all sites. */
    @Synchronized fun clear(site: String? = null) {
        if (site == null) sites.clear() else sites.remove(site)
        save()
    }

    @Synchronized fun siteCount(): Int = sites.size

    /** Cached hide verdicts as cosmetic CSS, injected at page start so there is no flash. With
     *  [parkCookies] (autoconsent on) cookie banners are left out: they go to [cookieSelectorsFor]. */
    fun cssFor(url: String, parkCookies: Boolean = false): String = hideRules(url)
        .filter { !(parkCookies && it.label == "cookie_consent") }
        .joinToString("\n") { "${it.sig}{display:none!important}" }

    /** Cached cookie banners as consent selectors: parked while autoconsent tries to reject them,
     *  which it cannot do to a banner hidden from the start (techspot, #24). */
    fun cookieSelectorsFor(url: String): List<String> = hideRules(url).filter { it.label == "cookie_consent" }.map { it.sig }

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
