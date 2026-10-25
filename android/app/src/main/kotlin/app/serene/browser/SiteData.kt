package app.serene.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import app.serene.engine.siteOf
import java.net.URI

/**
 * Clearing what sites stored, so a page can be tested as on a first visit (consent banners
 * only show to visitors without a consent cookie). WebView has no per-site delete, so cookies
 * are expired one by one for the page's host and its registrable domain.
 */
object SiteData {
    /** Cookies, local storage, IndexedDB and caches for [url]'s site (eTLD+1). Calls [done] on the main thread. */
    fun clear(url: String, done: () -> Unit) {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return done()
        val site = siteOf(url)
        val cm = CookieManager.getInstance()
        val hosts = buildSet {
            add(host)
            add(site)
            // Every level between the host and the site: a.b.example.com -> b.example.com.
            var h = host
            while (h != site && h.contains('.')) { h = h.substringAfter('.'); add(h) }
        }
        for (h in hosts) {
            val cookies = cm.getCookie("https://$h/") ?: continue
            for (c in cookies.split(';')) {
                val name = c.substringBefore('=').trim()
                if (name.isEmpty()) continue
                // A cookie is only replaced by one with the same domain and path, so try the usual ones.
                for (domain in listOf(null, h, ".$h")) {
                    for (path in listOf("/", null)) {
                        val attrs = listOfNotNull("$name=", "Expires=Thu, 01 Jan 1970 00:00:00 GMT", "Max-Age=0", domain?.let { "Domain=$it" }, path?.let { "Path=$it" })
                        cm.setCookie("https://$h/", attrs.joinToString("; "))
                    }
                }
            }
        }
        cm.flush()
        val storage = WebStorage.getInstance()
        storage.getOrigins { origins ->
            // A map of origin string to WebStorage.Origin.
            for (o in (origins as? Map<*, *>)?.values.orEmpty()) {
                val origin = (o as? WebStorage.Origin)?.origin ?: continue
                val oh = runCatching { URI(origin).host }.getOrNull()?.lowercase() ?: continue
                if (oh == site || oh.endsWith(".$site")) storage.deleteOrigin(origin)
            }
            done()
        }
    }

    /** Everything every site stored. */
    fun clearAll(done: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }
}
