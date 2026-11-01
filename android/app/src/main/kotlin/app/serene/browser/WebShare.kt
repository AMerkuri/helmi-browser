package app.serene.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.net.URI

/**
 * Sharing a page: ⋮ › Share…, and the Web Share API (navigator.share), which WebView
 * leaves out. [WEB_SHARE_JS] defines navigator.share and navigator.canShare in every frame and
 * hands what the page shares to the app through SereneBridge; the app opens Android's share sheet
 * with it, as Chrome does. Text and links only: a page sharing files gets canShare() false and a
 * TypeError from share(), so it can fall back to its own buttons.
 */

private const val TAG = "Helmi/Share"

/** What a page shares; the strings are empty when the page left them out. */
data class SharedText(val title: String, val text: String, val url: String)

/**
 * The text the share sheet sends, as Chrome builds it: the page's text, then its link, unless
 * the text already has the link in it. Cut to [MAX_SHARE] characters: an intent bigger than
 * Binder's transaction limit fails to start.
 */
fun shareBody(s: SharedText): String {
    val text = s.text.trim()
    val url = s.url.trim()
    val body = when {
        url.isEmpty() -> text
        text.isEmpty() -> url
        text.contains(url) -> text
        else -> "$text $url"
    }
    return if (body.length > MAX_SHARE) body.take(MAX_SHARE) else body
}

internal const val MAX_SHARE = 100_000

/**
 * A frame may share when it is the page itself or has the page's origin, as the web-share
 * permissions policy allows by default (a cross-origin frame, an ad say, needs allow="web-share",
 * which this shim cannot see, so it is refused). [origin] is the frame's origin as WebView gives
 * it ("https://example.com"), [pageUrl] the tab's address.
 */
fun sameOrigin(origin: String, pageUrl: String): Boolean {
    fun key(s: String): Triple<String, String, Int>? = runCatching {
        val u = URI(s)
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = if (u.port != -1) u.port else when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
        Triple(scheme, host, port)
    }.getOrNull()
    val a = key(origin) ?: return false
    return a == key(pageUrl)
}

/**
 * How long a tap or key press on the page lets it share: Chrome's user activation lasts 5 s too.
 * Opening a share sheet uses it up, so a page cannot open another until the user does something.
 */
internal const val ACTIVATION_MS = 5_000L

/** The share sheet for [s], from the app (⋮ › Share) or from a page (navigator.share). */
fun openShareSheet(context: Context, s: SharedText) {
    val body = shareBody(s)
    if (body.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
    if (s.title.isNotBlank()) {
        // The chooser's preview line, and the subject when the app picked is a mail app.
        send.putExtra(Intent.EXTRA_TITLE, s.title.trim())
        send.putExtra(Intent.EXTRA_SUBJECT, s.title.trim())
    }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * A page called navigator.share. [json] is the shim's message from a frame of [tab]'s page.
 * The shim already checks the page's user activation, but any script on the page can post to
 * SereneBridge itself, so the app checks again: the frame has the page's origin, the tab is on
 * screen with Helmi in front, and the user tapped the page (or pressed a key) in the last 5 s.
 */
internal fun onWebShare(tab: Tab, json: String, isMainFrame: Boolean, origin: Uri) {
    val view = tab.webView
    if (!isMainFrame && !sameOrigin(origin.toString(), tab.url)) {
        Log.i(TAG, "share from a cross-origin frame refused")
        return
    }
    if (!view.isAttachedToWindow || !view.isShown) return
    val resumed = (view.context as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
    if (!resumed) return
    if (SystemClock.uptimeMillis() - view.lastInput > ACTIVATION_MS) {
        Log.i(TAG, "share without a tap refused")
        return
    }
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return
    val s = SharedText(o.optString("title"), o.optString("text"), o.optString("url"))
    // A link the page gives must be a web link (the shim resolved it against the page already).
    if (s.url.isNotEmpty() && !(s.url.startsWith("https://") || s.url.startsWith("http://"))) return
    if (shareBody(s).isEmpty()) return
    view.lastInput = 0L
    runCatching { openShareSheet(view.context, s) }.onFailure { Log.w(TAG, "share sheet: ${it.message}") }
}

/**
 * navigator.share and navigator.canShare for pages, in every frame, before page scripts. Only in
 * a secure context (https), as the API is, and only where WebView has none of its own.
 * share() resolves once the share sheet is asked for: Android does not say whether the user
 * went on to share, so it never rejects with AbortError as Chrome's can.
 */
internal const val WEB_SHARE_JS = """(() => {
    const bridge = window.SereneBridge;
    if (!bridge || !window.isSecureContext || 'share' in navigator || window.__helmiShare) return;
    window.__helmiShare = true;
    const post = bridge.postMessage.bind(bridge);
    const stringify = JSON.stringify;
    let allowed;
    try { allowed = window === window.top || window.top.location.origin === location.origin; } catch (e) { allowed = false; }
    const link = u => {
        try {
            const r = new URL(String(u), location.href);
            return r.protocol === 'https:' || r.protocol === 'http:' ? r.href : null;
        } catch (e) { return null; }
    };
    const str = v => v === undefined || v === null ? '' : String(v);
    const valid = d => {
        if (!d || typeof d !== 'object') return false;
        if (d.files !== undefined && d.files !== null && d.files.length) return false;
        if (d.title === undefined && d.text === undefined && d.url === undefined) return false;
        return d.url === undefined || link(d.url) !== null;
    };
    let busy = false;
    const methods = {
        canShare(data) { return allowed && valid(data); },
        async share(data) {
            if (busy) throw new DOMException('A share is already in progress.', 'InvalidStateError');
            if (!allowed) throw new DOMException('Sharing is not allowed in this frame.', 'NotAllowedError');
            const ua = navigator.userActivation;
            if (ua && !ua.isActive) throw new DOMException('Sharing needs a tap or a key press first.', 'NotAllowedError');
            if (!valid(data)) throw new TypeError('Nothing to share, or files, which this browser cannot share.');
            busy = true;
            setTimeout(() => { busy = false; }, 1000);
            post(stringify({ type: 'helmi-share', title: str(data.title), text: str(data.text), url: data.url === undefined ? '' : link(data.url) }));
        },
    };
    for (const name of ['share', 'canShare']) {
        Object.defineProperty(Navigator.prototype, name, { value: methods[name], writable: true, enumerable: true, configurable: true });
    }
})()"""
