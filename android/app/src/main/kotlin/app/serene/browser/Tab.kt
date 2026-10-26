package app.serene.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.SereneApp
import app.serene.download.Download
import app.serene.engine.EngineJson
import app.serene.engine.FrameRef
import app.serene.engine.PageSession
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One browser tab: a WebView with the Serene engine wired in.
 *   addDocumentStartJavaScript → the content script, in every frame, before page scripts
 *   addWebMessageListener      → the SereneBridge object the content script talks to
 *   shouldInterceptRequest     → network rules
 * (engine/harness/browser.mjs is the Playwright stand-in for the same three hooks.)
 */
@SuppressLint("SetJavaScriptEnabled")
class Tab(context: Context, private val app: SereneApp, val id: Long) {
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    /** Bumped whenever the page session changes, so the shield badge and site sheet recompose. */
    var version by mutableIntStateOf(0)
        private set
    var session by mutableStateOf<PageSession?>(null)
        private set
    /**
     * The tab was opened for a file: its only navigation turned into a download, so the WebView
     * has nothing to draw (it shows solid black). The download page stands in for it.
     */
    var downloadPage by mutableStateOf(false)
        private set
    /** A navigation has started showing something. Until then the WebView would draw black. */
    var hasPage by mutableStateOf(false)
        private set
    /** The latest download from this tab; a bar over the page shows it until dismissed. */
    var downloadBar by mutableStateOf<Download?>(null)

    // The user agent of a blob: download while its data comes back from the page.
    @Volatile private var blobUserAgent: String? = null

    // Created when the main-frame request goes out, promoted when the new document says hello.
    @Volatile private var pending: PageSession? = null
    @Volatile private var current: PageSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val changeQueued = AtomicBoolean(false)

    // MATCH_PARENT, not the default WRAP_CONTENT: with a wrap_content height the page's
    // viewport is 0 px tall, so every vh-sized element (bottom sheets, consent boxes) collapses.
    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    init {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false) // target=_blank opens in this tab
            // Passkeys and security keys: WebView passes navigator.credentials to Credential Manager
            // with the page's origin, as a browser does. Needs CREDENTIAL_MANAGER_SET_ORIGIN in the
            // manifest, and the passkey provider must trust Serene as a browser (docs/passkeys.md).
            if (webAuthnSupported) {
                WebSettingsCompat.setWebAuthenticationSupport(this, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
            }
        }
        applyDarkening()
        if (documentStartSupported) WebViewCompat.addDocumentStartJavaScript(webView, KEEP_PICTURES, setOf("*"))
        installEngine()
        webView.webViewClient = Client()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: "" }
            // <input type=file>: GitHub's attach button, upload forms. The activity shows the picker.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                (view.context as? FilePickerHost)?.pickFiles(params) { callback.onReceiveValue(it) } ?: false
            // Page errors go in the page log ("What Serene did here", reports, e2e failures).
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    current?.note("console ${m.messageLevel().name.lowercase()}: ${m.message().take(200)} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                }
                return false
            }
        }
        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            onDownload(dlUrl, userAgent, contentDisposition, mimeType)
        }
    }

    private fun installEngine() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebView too old for WEB_MESSAGE_LISTENER: Serene runs network rules only")
            return
        }
        WebViewCompat.addWebMessageListener(webView, "SereneBridge", setOf("*")) { _, message: WebMessageCompat, _, isMainFrame, reply: JavaScriptReplyProxy ->
            val data = message.data ?: return@addWebMessageListener
            if (destroyed) return@addWebMessageListener
            if (isMainFrame && data.startsWith("{\"type\":\"serene-download\"")) {
                main.post { onBlobData(data) }
                return@addWebMessageListener
            }
            val frame = object : FrameRef {
                override val isTop = isMainFrame
                override fun send(json: String) {
                    if (Looper.myLooper() == Looper.getMainLooper()) post(json) else main.post { post(json) }
                }

                // Verdicts can arrive after the tab (or its frame) is gone; a dead proxy throws.
                private fun post(json: String) {
                    if (destroyed) return
                    try { reply.postMessage(json) } catch (e: Exception) { Log.w(TAG, "reply dropped: ${e.message}") }
                }
            }
            // A new document in the main frame is a new navigation: fresh page session.
            if (isMainFrame && data.startsWith("{\"type\":\"hello\"") && isTopHello(data)) newSession()
            current?.onMessage(data, frame)
        }
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, contentScript(webView.context), setOf("*"))
        }
    }

    // The content script says whether it runs in the top window; only that starts a new page.
    private fun isTopHello(data: String) = runCatching {
        EngineJson.parseToJsonElement(data).jsonObject["top"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)

    private fun newSession() {
        val s = pending ?: app.engine.newPage(::onSessionChange)
        pending = null
        current = s
        session = s
        version++
    }

    // Called from engine threads; coalesce into one main-thread bump per frame.
    private fun onSessionChange() {
        if (changeQueued.compareAndSet(false, true)) {
            main.postDelayed({ changeQueued.set(false); version++ }, 100)
        }
    }

    /**
     * Settings › Theme › "Apply dark theme to sites": while the app theme is dark, WebView darkens
     * pages that have no dark theme of their own (Chrome's auto dark mode); a page with one keeps
     * it, through prefers-color-scheme. Call again when the theme changes: setting it makes WebView
     * read the app theme again, which a WebView that isn't on screen would otherwise miss.
     */
    fun applyDarkening() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val on = app.settings.darkSites
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, !on)
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, on)
    }

    fun load(input: String) {
        val target = app.settings.searchEngine.let { resolveInput(input, it.template) }
        webView.loadUrl(target)
        url = target
    }

    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()
    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }

    @Volatile private var destroyed = false

    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val reqUrl = request.url.toString()
            if (request.isForMainFrame) {
                pending = app.engine.newPage(::onSessionChange).also { it.topUrl = reqUrl }
                return null
            }
            if (!reqUrl.startsWith("http")) return null
            val s = pending ?: current ?: return null
            val type = requestType(request)
            return if (s.shouldBlock(reqUrl, type)) EMPTY_RESPONSE() else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
            // intent:, mailto:, tel:, market: and app links go to other apps.
            return try {
                val intent = if (scheme == "intent") Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, request.url)
                intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.component = null
                intent.selector = null
                view.context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                true
            } catch (e: Exception) {
                true
            }
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            url = pageUrl
            // A new page: the download goes on in the notification and the downloads screen.
            hasPage = true
            downloadPage = false
            downloadBar = null
            // Old WebViews without DOCUMENT_START_SCRIPT: best effort, may lose the race.
            if (!documentStartSupported) {
                view.evaluateJavascript(KEEP_PICTURES, null)
                view.evaluateJavascript(contentScript(view.context), null)
            }
        }

        override fun onPageFinished(view: WebView, pageUrl: String) { updateNav(pageUrl) }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) { updateNav(pageUrl) }
    }

    private fun updateNav(pageUrl: String) {
        url = pageUrl
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun onDownload(dlUrl: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        // Nothing in the history: the tab was opened for this file (a link from another app).
        // Links on a page (target=_blank too, see setSupportMultipleWindows) keep the page.
        val blank = webView.copyBackForwardList().currentItem == null
        if (blank) downloadPage = true
        progress = 100
        if (dlUrl.startsWith("blob:")) return fetchBlob(dlUrl, userAgent)
        val referer = if (blank) null else Uri.parse(url).let { u -> if (u.scheme?.startsWith("http") == true) "${u.scheme}://${u.authority}/" else null }
        downloadBar = app.downloads.start(dlUrl, userAgent, contentDisposition, mimeType, referer, id)
    }

    /** A blob: URL only exists inside the page, so the page reads it and sends it back as data:. */
    private fun fetchBlob(blobUrl: String, userAgent: String) {
        blobUserAgent = userAgent
        val js = """(async () => {
            const post = m => window.SereneBridge && SereneBridge.postMessage(JSON.stringify(Object.assign({ type: 'serene-download' }, m)));
            try {
                const blob = await (await fetch(${JSONObject.quote(blobUrl)})).blob();
                const r = new FileReader();
                r.onload = () => post({ url: r.result });
                r.onerror = () => post({ error: String(r.error) });
                r.readAsDataURL(blob);
            } catch (e) { post({ error: String(e) }); }
        })()"""
        webView.evaluateJavascript(js, null)
    }

    private fun onBlobData(data: String) {
        // Only an answer to fetchBlob counts: the page can post to SereneBridge too.
        val ua = blobUserAgent ?: return
        blobUserAgent = null
        val msg = runCatching { EngineJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val dataUrl = msg["url"]?.jsonPrimitive?.contentOrNull
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            Log.w(TAG, "blob download failed: ${msg["error"]?.jsonPrimitive?.contentOrNull}")
            Toast.makeText(webView.context, "Serene couldn't save this file", Toast.LENGTH_SHORT).show()
            return
        }
        downloadBar = app.downloads.start(dataUrl, ua, null, null, null, id)
    }

    companion object {
        private const val TAG = "SereneTab"
        private var script: String? = null
        val documentStartSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webAuthnSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)

        /**
         * engine/dist/autoconsent.js, then engine/src/content/serene.js, both packaged as assets.
         * One script, so autoconsent is defined before serene.js starts it.
         */
        fun contentScript(context: Context): String = script ?: listOf("autoconsent.js", "serene.js")
            .joinToString("\n") { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
            .also { script = it }

        /**
         * Darkening a page leaves its pictures as they are. WebView would invert the ones it takes
         * for icons or drawings (few colors: logos, diagrams, scans); `color-scheme: only light`
         * opts an element out of forced darkening, and does nothing otherwise.
         */
        private const val KEEP_PICTURES = """(() => {
            try {
                const sheet = new CSSStyleSheet();
                sheet.replaceSync('img, picture, video, canvas, svg image { color-scheme: only light !important; }');
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            } catch (e) {}
        })();"""

        private fun EMPTY_RESPONSE() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}

/** The activity behind the tabs: shows the system file picker for a page's file input. */
interface FilePickerHost {
    /** Returns false when no picker could be shown; [done] gets null when the user backs out. */
    fun pickFiles(params: WebChromeClient.FileChooserParams, done: (Array<Uri>?) -> Unit): Boolean
}

/**
 * WebView does not say what kind of resource a request is, and adblock-rust's `$script`,
 * `$subdocument` etc. options need one. Guess from the Accept header and the file extension.
 * Chromium sends `*` + `/` + `*` for scripts and fetch(), so those count as scripts.
 */
fun requestType(request: WebResourceRequest): String {
    val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
    val path = request.url.path?.lowercase() ?: ""
    return when {
        accept.contains("text/html") -> "sub_frame"
        accept.contains("text/css") || path.endsWith(".css") -> "stylesheet"
        accept.startsWith("image/") || Regex("""\.(png|jpe?g|gif|webp|svg|avif|ico)$""").containsMatchIn(path) -> "image"
        path.endsWith(".js") || path.endsWith(".mjs") -> "script"
        Regex("""\.(woff2?|ttf|otf)$""").containsMatchIn(path) -> "font"
        Regex("""\.(mp4|webm|m3u8|mp3|m4a)$""").containsMatchIn(path) -> "media"
        accept.contains("application/json") -> "xmlhttprequest"
        accept.isEmpty() || accept == "*/*" -> "script"
        else -> "other"
    }
}

/** URL bar input: a URL as typed, a host without scheme, or a search. */
fun resolveInput(input: String, searchTemplate: String): String {
    val s = input.trim()
    if (s.isEmpty()) return "about:blank"
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) || s.startsWith("about:")) return s
    val looksLikeHost = !s.contains(' ') && (Regex("""^[^/\s]+\.[a-zA-Z]{2,}(:\d+)?(/.*)?$""").matches(s) || s.startsWith("localhost"))
    return if (looksLikeHost) "https://$s" else searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"))
}
