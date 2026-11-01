package app.serene.browser

import app.serene.Settings
import androidx.webkit.UserAgentMetadata
import org.json.JSONObject

/**
 * ⋮ › Desktop site, as Chrome sends it: the reduced user agent of Chrome on desktop Linux, with
 * the WebView's Chrome major version. Without the "Android", "Mobile" and "wv" tokens sites
 * stop sending their phone pages (Chrome's own desktop mode sends this string too).
 * [mobile] is the WebView's own user agent; without a Chrome version in it, a recent one.
 */
/** The platform in [desktopUserAgent]: no phone's user agent has it. */
internal const val DESKTOP_PLATFORM = "X11; Linux x86_64"

fun desktopUserAgent(mobile: String): String {
    val major = Regex("""Chrome/(\d+)""").find(mobile)?.groupValues?.get(1) ?: "141"
    return "Mozilla/5.0 ($DESKTOP_PLATFORM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
}

/**
 * The client hints (Sec-CH-UA-Mobile, Sec-CH-UA-Platform, navigator.userAgentData) that go with
 * [desktopUserAgent]: the same browser brands and version, on a desktop. Sites like Google read
 * these rather than the user agent string.
 */
fun desktopUserAgentMetadata(mobile: UserAgentMetadata): UserAgentMetadata =
    UserAgentMetadata.Builder(mobile)
        .setPlatform("Linux")
        .setPlatformVersion("")
        .setModel("")
        .setMobile(false)
        .setArchitecture("x86")
        .setBitness(64)
        .build()

/**
 * The page's viewport, rewritten in its <meta name=viewport> before it lays out (a document-start
 * script in the top frame, built from the settings; the tab registers it again when they change,
 * and runs it on the page that is open). A page is a desktop one when it came with the desktop
 * user agent, so the width always goes with what the site was asked with. Run again, it puts back what it changed and applies the
 * settings as they are now.
 *
 * - ⋮ › Desktop site: the page is laid out as wide as a desktop window (980 px, WebView's wide
 *   viewport) and shown zoomed out to fit (overview mode), also when it asks for the phone's
 *   width. Without that, a site that adapts to the screen (most of them) would look as on a phone.
 * - Settings › Accessibility › "Override sites' zoom block" (Chrome's "Force enable zoom"): a page
 *   that blocks pinch zoom (user-scalable=no, maximum-scale=1) can be zoomed all the same.
 *
 * The rewriting itself is [rewriteViewport]'s, in JavaScript as [VIEWPORT_JS].
 */
fun viewportScript(s: Settings): String {
    return """(() => {
        if (window.top !== window) return;
        const key = Symbol.for('serene.viewport');
        const st = window[key] || (window[key] = { orig: new WeakMap(), wrote: new WeakMap() });
        // A desktop page: one that came with the desktop user agent (the tab chose it for this
        // page, by its site or as it was in the history).
        st.desktop = navigator.userAgent.includes(${JSONObject.quote(DESKTOP_PLATFORM)});
        st.force = ${s.forceZoom};
        $VIEWPORT_JS
        const fix = (m) => {
            if (!st.orig.has(m)) st.orig.set(m, m.getAttribute('content') || '');
            const want = (st.desktop || st.force) ? rewrite(st.orig.get(m), st.desktop, st.force) : st.orig.get(m);
            if ((m.getAttribute('content') || '') === want) return;
            st.wrote.set(m, want);
            m.setAttribute('content', want);
        };
        const all = () => { for (const m of document.querySelectorAll('meta[name=viewport i]')) fix(m); };
        all();
        if (st.watching || !(st.desktop || st.force)) return;
        st.watching = true;
        const isViewport = (n) => n.nodeName === 'META' && (n.getAttribute('name') || '').toLowerCase() === 'viewport';
        new MutationObserver((records) => {
            for (const r of records) {
                if (r.type === 'attributes') {
                    if (!isViewport(r.target)) continue;
                    // The page set it again (not this script): that is what it asks for now.
                    const now = r.target.getAttribute('content') || '';
                    if (st.wrote.get(r.target) !== now) st.orig.set(r.target, now);
                    fix(r.target);
                } else for (const n of r.addedNodes) {
                    if (isViewport(n)) fix(n);
                    else if (n.nodeName === 'HEAD' || n.nodeName === 'HTML') all();
                }
            }
        }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['content', 'name'] });
    })();"""
}

/**
 * `rewrite(content, desktop, force)`: the viewport [content] with the desktop width and/or zoom
 * allowed. Desktop: width=980 in place of the page's width, and no initial or minimum scale, so
 * overview mode zooms out to the whole width. Force, and desktop too (a phone page's zoom block
 * would leave the zoomed-out page unreadable): user-scalable=yes and a maximum scale of at least 5
 * (Chrome's default). Other keys (viewport-fit, interactive-widget) stay as they are.
 * [rewriteViewport] is the same in Kotlin, for the unit tests.
 */
internal const val VIEWPORT_JS = """const rewrite = (content, desktop, force) => {
            let parts = content.split(/[,;]/).map(p => p.trim()).filter(Boolean).map(p => {
                const i = p.indexOf('=');
                return i < 0 ? [p.toLowerCase(), ''] : [p.slice(0, i).trim().toLowerCase(), p.slice(i + 1).trim()];
            });
            if (desktop) parts = parts.filter(([k]) => k !== 'width' && k !== 'initial-scale' && k !== 'minimum-scale').concat([['width', '980']]);
            if (force || desktop) {
                const max = parseFloat((parts.find(([k]) => k === 'maximum-scale') || [])[1]);
                parts = parts.filter(([k]) => k !== 'user-scalable' && k !== 'maximum-scale')
                    .concat([['user-scalable', 'yes'], ['maximum-scale', String(max > 5 ? max : 5)]]);
            }
            return parts.map(([k, v]) => v === '' ? k : k + '=' + v).join(', ');
        };"""

/** [VIEWPORT_JS]'s rewrite, step for step. */
internal fun rewriteViewport(content: String, desktop: Boolean, force: Boolean): String {
    var parts = content.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
        val i = p.indexOf('=')
        if (i < 0) p.lowercase() to "" else p.substring(0, i).trim().lowercase() to p.substring(i + 1).trim()
    }
    if (desktop) parts = parts.filter { (k) -> k != "width" && k != "initial-scale" && k != "minimum-scale" } + ("width" to "980")
    if (force || desktop) {
        val max = parts.firstOrNull { it.first == "maximum-scale" }?.second?.toDoubleOrNull()
        parts = parts.filter { (k) -> k != "user-scalable" && k != "maximum-scale" } +
            listOf("user-scalable" to "yes", "maximum-scale" to (if (max != null && max > 5) jsNumber(max) else "5"))
    }
    return parts.joinToString(", ") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
}

// String(n) in JavaScript: 10, not 10.0.
private fun jsNumber(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

/** Settings › Accessibility › Text size, Chrome's text scaling: 50% to 300%, in steps of 5. */
object TextScale {
    const val MIN = 50
    const val MAX = 300
    const val STEP = 5
    const val DEFAULT = 100

    /**
     * WebView's textZoom for [scale] percent. WebView starts from the phone's font size (its
     * default textZoom is 100 times the font scale), and so does the scale here: 100% is what
     * pages had before, and the phone's font size still counts on top, as in Chrome.
     */
    fun textZoom(scale: Int, fontScale: Float): Int = Math.round(scale.coerceIn(MIN, MAX) * fontScale)

    /** The slider's value, put on a step. */
    fun snap(value: Float): Int = (Math.round(value / STEP) * STEP).coerceIn(MIN, MAX)
}
