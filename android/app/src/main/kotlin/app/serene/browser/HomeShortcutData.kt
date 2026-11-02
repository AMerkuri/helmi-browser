package app.serene.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.IDN
import java.net.URI

/**
 * What ⋮ › Add to home screen reads from a page, and the choices made from it, as Chrome makes
 * them: the shortcut's name, and which of the site's icons to fetch (its web app manifest's,
 * then its apple-touch-icon, then its favicons). Plain Kotlin, so the unit tests run it; the
 * fetching, drawing and pinning are in HomeShortcut.
 */
object HomeShortcutData {
    /** Where an icon was named. In the order they are tried. */
    enum class Source { MANIFEST, TOUCH, LINK, FAVICON }

    /** [size]: the largest declared size in px, 0 when unknown. */
    data class Icon(val url: String, val size: Int, val maskable: Boolean, val source: Source)

    /** The page's own: from PAGE_INFO_JS. [appName]: apple-mobile-web-app-title or application-name. */
    data class Page(
        val title: String = "",
        val manifestUrl: String = "",
        val appName: String = "",
        val themeColor: String = "",
        val icons: List<Icon> = emptyList(),
    )

    data class Manifest(
        val name: String = "",
        val shortName: String = "",
        val themeColor: String = "",
        val icons: List<Icon> = emptyList(),
    )

    /**
     * Run in the page: what Chrome's Add to home screen reads. A JSON string, so evaluateJavascript
     * hands it back quoted once more.
     */
    const val PAGE_INFO_JS = """(() => {
  const abs = h => { try { return new URL(h, document.baseURI).href; } catch (e) { return ''; } };
  const meta = n => { const m = document.querySelector('meta[name="' + n + '" i][content]'); return m ? m.content.trim() : ''; };
  const theme = [...document.querySelectorAll('meta[name="theme-color" i][content]')]
    .find(m => !m.media || matchMedia(m.media).matches);
  const manifest = document.querySelector('link[rel~="manifest" i][href]');
  const icons = [...document.querySelectorAll('link[rel][href]')]
    .map(l => ({ rel: l.rel.toLowerCase(), href: abs(l.getAttribute('href')), sizes: l.getAttribute('sizes') || '', type: (l.type || '').toLowerCase() }))
    .filter(i => /(^|\s)(icon|apple-touch-icon|apple-touch-icon-precomposed)(\s|$)/.test(i.rel))
    .slice(0, 40);
  return JSON.stringify({
    title: document.title || '',
    manifest: manifest ? abs(manifest.getAttribute('href')) : '',
    appName: meta('apple-mobile-web-app-title') || meta('application-name'),
    themeColor: theme ? theme.content.trim() : '',
    icons,
  });
})()"""

    private val json = Json { ignoreUnknownKeys = true }

    /** PAGE_INFO_JS's answer as evaluateJavascript gives it (a JSON string of JSON). */
    fun parsePage(result: String?): Page? = runCatching {
        val inner = (json.parseToJsonElement(result ?: return null) as? JsonPrimitive)?.contentOrNull ?: return null
        val o = json.parseToJsonElement(inner) as? JsonObject ?: return null
        val icons = (o["icons"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val href = i.str("href")
            if (!usable(href, i.str("type"))) return@mapNotNull null
            val rel = i.str("rel").split(Regex("\\s+"))
            val touch = rel.any { it.startsWith("apple-touch-icon") }
            Icon(href, largestSize(i.str("sizes")), maskable = false, source = if (touch) Source.TOUCH else Source.LINK)
        }
        Page(
            title = o.str("title").oneLine(),
            manifestUrl = o.str("manifest").takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty(),
            appName = o.str("appName").oneLine(),
            themeColor = o.str("themeColor"),
            icons = icons,
        )
    }.getOrNull()

    /** A web app manifest; its icons' addresses are relative to [manifestUrl]. */
    fun parseManifest(text: String, manifestUrl: String): Manifest? = runCatching {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val base = URI(manifestUrl)
        val icons = (o["icons"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val src = runCatching { base.resolve(i.str("src").trim()).toString() }.getOrNull() ?: return@mapNotNull null
            if (!usable(src, i.str("type"))) return@mapNotNull null
            val purpose = i.str("purpose").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { listOf("any") }
            when {
                "any" in purpose -> Icon(src, largestSize(i.str("sizes")), maskable = false, source = Source.MANIFEST)
                "maskable" in purpose -> Icon(src, largestSize(i.str("sizes")), maskable = true, source = Source.MANIFEST)
                else -> null // monochrome only: a silhouette for the system to colour, not an icon
            }
        }
        Manifest(
            name = o.str("name").oneLine(),
            shortName = o.str("short_name").oneLine(),
            themeColor = o.str("theme_color"),
            icons = icons,
        )
    }.getOrNull()

    /**
     * The icons to try, best first: the manifest's (a maskable one first, it fills the launcher's
     * shape), the apple-touch-icon, the page's other icons, then /favicon.ico. Within each, the
     * smallest at least [idealPx], else the largest; unknown sizes in between.
     */
    fun candidates(pageUrl: String, page: Page?, manifest: Manifest?, idealPx: Int): List<Icon> {
        val rank = compareBy<Icon>({ it.source.ordinal }, { if (it.source == Source.MANIFEST && !it.maskable) 1 else 0 }, {
            when {
                it.size >= idealPx -> 0
                it.size == 0 -> 1
                else -> 2
            }
        }, { if (it.size >= idealPx) it.size else -it.size })
        val favicon = runCatching { URI(pageUrl).let { u -> URI(u.scheme, u.authority, "/favicon.ico", null, null).toString() } }.getOrNull()
            ?.takeIf { it.startsWith("http") }
            ?.let { Icon(it, 0, maskable = false, source = Source.FAVICON) }
        return (manifest?.icons.orEmpty() + page?.icons.orEmpty() + listOfNotNull(favicon)).distinctBy { it.url to it.maskable }.sortedWith(rank)
    }

    /**
     * The name the dialog starts with. On a site's front page its app name (the manifest's short
     * name, its name, the page's app title), as Chrome; on any other page its title, since the
     * shortcut opens that page, not the site. Then the site.
     */
    fun name(pageUrl: String, tabTitle: String, page: Page?, manifest: Manifest?): String {
        val appNames = listOf(manifest?.shortName, manifest?.name, page?.appName)
        val titles = listOf(page?.title, tabTitle.oneLine())
        val order = if (isFrontPage(pageUrl)) appNames + titles else titles + appNames
        return order.firstOrNull { !it.isNullOrBlank() && it != pageUrl }?.take(MAX_NAME) ?: site(pageUrl)
    }

    const val MAX_NAME = 80

    /**
     * Whether a tab on [tabUrl] is already the shortcut's page, so launching it shows that tab
     * (Chrome's "reuse the tab on this URL, else a new one"). http/https, www., a final slash and
     * the #fragment aside: the site's own redirect from one to the other is still the page.
     */
    fun samePage(shortcutUrl: String, tabUrl: String): Boolean {
        fun key(u: String) = u.trim().substringBefore('#').replaceFirst(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "").removeSuffix("/").lowercase()
        return tabUrl.isNotBlank() && key(shortcutUrl) == key(tabUrl)
    }

    fun isFrontPage(url: String): Boolean = runCatching {
        val u = URI(url)
        (u.rawPath.isNullOrEmpty() || u.rawPath == "/") && u.rawQuery.isNullOrEmpty()
    }.getOrDefault(false)

    /** The site's host, as people read it: no www., Unicode letters for punycode. */
    fun site(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: return url
        return runCatching { IDN.toUnicode(host) }.getOrDefault(host)
    }

    /** The letter of a shortcut with no usable icon, as Chrome's: the site's first letter or digit. */
    fun monogram(url: String): String {
        val host = site(url)
        val i = host.indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return "?"
        val cp = host.codePointAt(i)
        return String(Character.toChars(cp)).uppercase()
    }

    /** A CSS colour as ARGB (#rgb, #rgba, #rrggbb, #rrggbbaa, rgb()/rgba(), white, black); null for others. */
    fun parseColor(css: String): Int? {
        val s = css.trim().lowercase()
        if (s == "white") return 0xFFFFFFFF.toInt()
        if (s == "black") return 0xFF000000.toInt()
        if (s.startsWith("#")) {
            val h = s.substring(1)
            if (h.any { Character.digit(it, 16) < 0 }) return null
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            val rgb = full.substring(0, 6).toLong(16).toInt()
            val a = if (full.length == 8) full.substring(6).toInt(16) else 255
            return (a shl 24) or rgb
        }
        val m = Regex("""rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})(?:\s*[,/]\s*([\d.]+%?))?\s*\)""").matchEntire(s) ?: return null
        val (r, g, b) = m.destructured.toList().take(3).map { it.toInt().coerceIn(0, 255) }
        val alpha = m.groupValues[4].let { v ->
            when {
                v.isEmpty() -> 255
                v.endsWith("%") -> (v.dropLast(1).toDoubleOrNull() ?: 100.0).coerceIn(0.0, 100.0).times(2.55).toInt()
                else -> (v.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0).times(255).toInt()
            }
        }
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A colour for the letter's background when the site names none: the same one for a site each time. */
    fun fallbackColor(url: String): Int = PALETTE[Math.floorMod(site(url).hashCode(), PALETTE.size)]

    /** Relative luminance (0 black, 1 white) of an ARGB colour, by WCAG. */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** "16x16 32x32" → 32; "any" and junk → 0. */
    fun largestSize(sizes: String): Int = sizes.lowercase().split(Regex("\\s+")).mapNotNull { s ->
        val p = s.split('x')
        if (p.size != 2) null else {
            val w = p[0].toIntOrNull()
            val h = p[1].toIntOrNull()
            if (w == null || h == null) null else minOf(w, h)
        }
    }.maxOrNull() ?: 0

    // Android can't draw SVG into a bitmap; data: icons are decoded in place.
    private fun usable(url: String, type: String): Boolean {
        if (type.contains("svg")) return false
        val lower = url.lowercase()
        if (lower.startsWith("data:")) return !lower.startsWith("data:image/svg")
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        return !lower.substringBefore('?').substringBefore('#').endsWith(".svg")
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

    // Material 600s: white letters read on each.
    private val PALETTE = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(),
        0xFF00897B.toInt(), 0xFFF4511E.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
    )
}
