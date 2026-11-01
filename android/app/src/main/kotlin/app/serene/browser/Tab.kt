package app.serene.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import app.serene.browser.HistorySwipe.Companion.BACK
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.UserAgentMetadata
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.SereneApp
import app.serene.download.Download
import app.serene.engine.EngineJson
import app.serene.engine.FrameRef
import app.serene.engine.PageSession
import app.serene.engine.siteOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
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
 * [openedByPage]: a page opened it (window.open, a target=_blank link) and WebView loads its page:
 * no start page or keyboard while that starts, and an about:blank popup is the page's own.
 */
@SuppressLint("SetJavaScriptEnabled")
class Tab(context: Context, private val app: SereneApp, val id: Long, val openedByPage: Boolean = false) {
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
    /** Counts the page loads started in this tab (not same-document steps): what ends a find in page. */
    var pageStarts by mutableIntStateOf(0)
        private set
    /** The latest download from this tab; a bar over the page shows it until dismissed. */
    var downloadBar by mutableStateOf<Download?>(null)
    /** The link or picture the user long-pressed: the context menu shows it until dismissed. */
    var contextMenu by mutableStateOf<Pressed?>(null)
    /**
     * The tab whose page opened this one (window.open, a target=_blank link). Its page is this
     * page's window.opener: sign-in popups (Google, Apple) answer it and close themselves.
     */
    var opener by mutableStateOf<Tab?>(null)
    /** Camera, microphone and location requests from this tab's pages; the prompt shows the first. */
    val permissions = PermissionRequests(app.sitePermissions, pageUrl = { webView.url }) { webView.context as? PermissionHost }
    /** The page's element in fullscreen (a video, usually): the activity shows it over everything. */
    var fullscreen by mutableStateOf<Fullscreen?>(null)
        private set
    /** The fullscreen video's size, from the frame that plays it; null until it says, or for no video. */
    var fullscreenVideo by mutableStateOf<IntSize?>(null)
        private set
    /** Opens the windows this tab's pages ask for, as tabs; set by the tab manager. */
    internal var manager: TabManager? = null

    // The user agent of a blob: download while its data comes back from the page.
    @Volatile private var blobUserAgent: String? = null

    // Created when the main-frame request goes out, promoted when the new document says hello.
    @Volatile private var pending: PageSession? = null
    @Volatile private var current: PageSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val changeQueued = AtomicBoolean(false)

    /** Swiping back and forward through the history, and pulling down to reload. */
    val swipe = HistorySwipe(this)
    val pull = PullRefresh(this)

    /**
     * The tab's page. A new one when the page's renderer went away (a crash, or Android killed it
     * for memory, see [Unloaded]): a WebView is dead for good once its renderer is. A new one too
     * when Helmi [discard]s the page to save memory.
     */
    var webView: SereneWebView by mutableStateOf(newWebView(context))
        private set

    // MATCH_PARENT, not the default WRAP_CONTENT: with a wrap_content height the page's
    // viewport is 0 px tall, so every vh-sized element (bottom sheets, consent boxes) collapses.
    private fun newWebView(context: Context) = SereneWebView(context, swipe, pull).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        onScrolled = ::snapshotSoon
        // A tab whose page went while it was not on screen gets it back once it is (Chrome too
        // reloads such a tab when it is shown, not before).
        onVisible = { main.post { if (unloaded?.onScreen == false && webView.isAttachedToWindow) recover() } }
    }

    /**
     * The tab has no page now, only [url] and its back/forward list ([state], as [history] gives
     * it), and gets them back with [recover]. [why]: the page's renderer crashed, or Android killed
     * it (low memory: the model holds over 2 GB; one renderer serves every tab, so every tab loses
     * its page at once), or Helmi let the page go to save memory ([discard], or a tab restored after
     * a restart that has not been shown yet). [onScreen]: it was on screen, so it shows that the
     * page crashed until Reload (a page that crashes as it loads would otherwise crash over and
     * over); one that was not gets its page back when it is shown.
     */
    class Unloaded(val url: String, val state: Map<String, ByteArray>, val why: Why, val onScreen: Boolean) {
        val crash get() = why == Why.CRASH
    }

    enum class Why { CRASH, KILL, DISCARD }

    var unloaded by mutableStateOf<Unloaded?>(null)
        private set

    /**
     * A page that was [unloaded] while not on screen is loading again: the WebView stays hidden
     * until it draws, so the empty WebView doesn't flash white (or black) first.
     */
    var waking by mutableStateOf(false)
        private set

    /** When the tab was last on screen (SystemClock.elapsedRealtime), for [TabDiscard]; set by the tab manager. */
    internal var lastShown = SystemClock.elapsedRealtime()

    // Follows the Settings flags while the tab is open.
    private val flags: Job

    // Settings › Theme › "Dim pictures", for the documents to come.
    private var dimScript: ScriptHandler? = null
    // ⋮ › Desktop site's width and "Override sites' zoom block", for the documents to come.
    private var viewportHandler: ScriptHandler? = null

    // ⋮ › Desktop site: the WebView's own user agent and client hints, and whether it asks for
    // desktop pages now (see userAgentFor).
    private var mobileUa = ""
    private var mobileUaMetadata: UserAgentMetadata? = null
    /** Whether the tab asks for desktop pages now: what its page came with, once it has started. */
    var desktopUa by mutableStateOf(false)
        private set
    // Desktop or phone, as each page of this tab's history came (by address): back and forward
    // get a page as it was, as in Chrome, also after the site's choice changed. The newest 200.
    private val pageModes = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 200
    }
    // The page a history step (or a restore) is on its way to: it keeps its own mode (onPageStarted).
    private var historyTarget: String? = null

    init {
        setUp()
        flags = app.scope.launch(Dispatchers.Main.immediate) {
            launch {
                snapshotFlow { backForwardCache }.collect {
                    if (backForwardCacheSupported) WebSettingsCompat.setBackForwardCacheEnabled(webView.settings, it)
                    if (!it) Snapshots.clear(id)
                }
            }
            // Dimming and its level, and where it applies: the sites the dark theme darkens.
            launch {
                snapshotFlow { dimPicturesScript(app.settings) }.collect { js ->
                    if (documentStartSupported) {
                        dimScript?.remove()
                        dimScript = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
                    }
                    webView.evaluateJavascript(js, null)
                }
            }
            // Desktop width and pinch zoom.
            launch {
                snapshotFlow { viewportScript(app.settings) }.collect { js ->
                    if (documentStartSupported) {
                        viewportHandler?.remove()
                        viewportHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
                    }
                    webView.evaluateJavascript(js, null)
                }
            }
            launch { snapshotFlow { app.settings.textScale }.collect { applyTextZoom() } }
            // The setting, or this site's own choice from ⋮ › Dark theme, as it changes.
            snapshotFlow { app.settings.darkSite(siteOf(url)) }.collect { setDarkening(it) }
        }
    }

    /** Settings, scripts and clients of [webView]: at the start, and again for the new one after a crash. */
    private fun setUp() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            // window.open and target=_blank open a tab (onCreateWindow) that keeps its opener, as
            // in Chrome. Without it they replaced this page, and a sign-in popup that answers its
            // opener (LinkedIn's "Continue with Google") was left blank with no one to answer.
            // Windows only from a tap: javaScriptCanOpenWindowsAutomatically stays false.
            setSupportMultipleWindows(true)
            // navigator.geolocation, behind Helmi's own prompt (onGeolocationPermissionsShowPrompt).
            setGeolocationEnabled(true)
            // Passkeys and security keys: WebView passes navigator.credentials to Credential Manager
            // with the page's origin, as a browser does. Needs CREDENTIAL_MANAGER_SET_ORIGIN in the
            // manifest, and the passkey provider must trust Serene as a browser (docs/passkeys.md).
            if (webAuthnSupported) {
                WebSettingsCompat.setWebAuthenticationSupport(this, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
            }
            // Back/forward cache, as Chrome has: a page left is kept paused for a while, and back or
            // forward to it resumes it as it was instead of loading it again (no reload, no jump).
            // The page's engine session goes with it, see onDocMessage. A flag in Settings.
            if (backForwardCacheSupported) WebSettingsCompat.setBackForwardCacheEnabled(this, backForwardCache)
        }
        // Before any user agent of Helmi's own: the WebView's.
        mobileUa = webView.settings.userAgentString
        mobileUaMetadata = if (uaMetadataSupported) runCatching { WebSettingsCompat.getUserAgentMetadata(webView.settings) }.getOrNull() else null
        desktopUa = false
        userAgentFor(url)
        // The flags below follow changes; a new WebView starts from what they are now.
        applyTextZoom()
        if (documentStartSupported) dimScript = WebViewCompat.addDocumentStartJavaScript(webView, dimPicturesScript(app.settings), setOf("*"))
        if (documentStartSupported) viewportHandler = WebViewCompat.addDocumentStartJavaScript(webView, viewportScript(app.settings), setOf("*"))
        applyDarkening()
        if (documentStartSupported) WebViewCompat.addDocumentStartJavaScript(webView, KEEP_PICTURES, setOf("*"))
        installEngine()
        webView.webViewClient = Client()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: ""; app.history.titled(view.url, t) }
            // A new tab beside this one, on screen; WebView loads the window's page into it.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val m = manager ?: return false
                if (!isUserGesture || destroyed) return false
                val tab = m.openWindow(this@Tab)
                // The window's page loads with the new tab's user agent before it says where it
                // went: this one's, as it is usually this site's.
                tab.userAgentLike(this@Tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
                resultMsg.sendToTarget()
                return true
            }
            // The page called window.close(): only a window a page opened may close itself.
            override fun onCloseWindow(window: WebView) { manager?.closeWindow(this@Tab) }
            // requestFullscreen(): a video's fullscreen button, YouTube's player. Without these the
            // button did nothing (WebView has no fullscreen of its own).
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (destroyed) callback.onCustomViewHidden() else fullscreen = Fullscreen(view, callback)
            }
            override fun onHideCustomView() {
                fullscreen = null
                fullscreenVideo = null
            }
            // <input type=file>: GitHub's attach button, upload forms. The activity shows the picker.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                (view.context as? FilePickerHost)?.pickFiles(params) { callback.onReceiveValue(it) } ?: false
            // Camera, microphone and location: asked per site (SitePermissions).
            override fun onPermissionRequest(request: PermissionRequest) = permissions.onPermissionRequest(request)
            override fun onPermissionRequestCanceled(request: PermissionRequest) = permissions.onPermissionRequestCanceled(request)
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = permissions.onGeolocationPrompt(origin, callback)
            override fun onGeolocationPermissionsHidePrompt() = permissions.onGeolocationHidePrompt()
            // Page errors go in the page log ("What Helmi did here", reports, e2e failures).
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
        webView.setOnLongClickListener { onLongPress() }
    }

    // The long-pressed link's address and text, from WebView's last hit test. what is the hit
    // test's type, obj its address: the link's, or for a picture link the picture's.
    private val linkHref = Handler(Looper.getMainLooper()) { m ->
        val picture = m.what == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val href = (m.data.getString("url")?.takeIf { it.isNotBlank() } ?: (m.obj as? String).takeIf { !picture })?.takeIf { isWebLink(it) }
        val image = (m.obj as? String)?.takeIf { picture && isImageSrc(it) }
        if ((href != null || image != null) && !destroyed) {
            contextMenu = Pressed(href, m.data.getString("title").orEmpty().trim().replace(Regex("\\s+"), " "), image)
            image?.let(::lookUpAlt)
        }
        true
    }

    /**
     * A long press on a link or a picture opens the context menu, as in Chrome. Anything else
     * (text to select) stays WebView's. Web links only: a javascript: or mailto: link has nothing
     * to open in a tab. Pictures from the web or data: URLs; a blob: one only exists in its page.
     */
    private fun onLongPress(): Boolean {
        val hit = webView.hitTestResult
        val src = hit.extra ?: return false
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> if (!isWebLink(src)) return false
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> Unit
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (!isImageSrc(src)) return false
                contextMenu = Pressed(null, "", src)
                lookUpAlt(src)
                return true
            }
            else -> return false
        }
        // For a picture link the hit test has the picture's address; the link's comes with the message.
        webView.requestFocusNodeHref(linkHref.obtainMessage(hit.type, src))
        return true
    }

    /** The picture's alt text (or title) for the top of the menu. Pictures in the top frame only. */
    private fun lookUpAlt(src: String) {
        val js = "(() => { const s = ${JSONObject.quote(src)}; const i = [...document.images].find(i => i.currentSrc === s || i.src === s);" +
            " return i ? (i.alt || i.title || '').trim() : ''; })()"
        webView.evaluateJavascript(js) { json ->
            val alt = runCatching { JSONArray("[$json]").optString(0) }.getOrNull().orEmpty().replace(Regex("\\s+"), " ")
            val p = contextMenu
            if (alt.isNotEmpty() && p != null && p.image == src) contextMenu = p.copy(alt = alt)
        }
    }

    private fun installEngine() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebView too old for WEB_MESSAGE_LISTENER: Serene runs network rules only")
            return
        }
        WebViewCompat.addWebMessageListener(webView, "SereneBridge", setOf("*")) { _, message: WebMessageCompat, origin, isMainFrame, reply: JavaScriptReplyProxy ->
            val data = message.data ?: return@addWebMessageListener
            if (destroyed) return@addWebMessageListener
            if (isMainFrame && data.startsWith("{\"type\":\"serene-download\"")) {
                main.post { onBlobData(data) }
                return@addWebMessageListener
            }
            if (isMainFrame && data == SETTLED) {
                main.post { shown() }
                return@addWebMessageListener
            }
            if (isMainFrame && data.startsWith("{\"type\":\"serene-doc")) {
                onDocMessage(data)
                return@addWebMessageListener
            }
            if (data.startsWith("{\"type\":\"helmi-share\"")) {
                main.post { if (!destroyed) onWebShare(this@Tab, data, isMainFrame, origin) }
                return@addWebMessageListener
            }
            // From any frame: a video in a YouTube embed goes fullscreen in its own frame.
            if (data.startsWith("{\"type\":\"helmi-fullscreen\"")) {
                main.post { onFullscreenVideo(data) }
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
            // After the content script, so its hello (and the session it starts) comes first.
            WebViewCompat.addDocumentStartJavaScript(webView, DOC_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, FULLSCREEN_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, WEB_SHARE_JS, setOf("*"))
        }
    }

    private fun onFullscreenVideo(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val w = o.optInt("w")
        val h = o.optInt("h")
        if (w > 0 && h > 0 && !destroyed) fullscreenVideo = IntSize(w, h)
    }

    /** Leaves fullscreen: back, a link from another app, the tab closing. */
    fun exitFullscreen() {
        val f = fullscreen ?: return
        fullscreen = null
        fullscreenVideo = null
        f.exit()
    }

    // Each document's engine session, by an id the document makes up at its start.
    private val docSessions = object : LinkedHashMap<String, PageSession>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageSession>) = size > 50
    }

    /**
     * A top document says it started (right after its hello, so [current] is its session) or that
     * it came back from the back/forward cache. A restored document says no hello: without this
     * its requests and messages would go to the session of the page it replaced.
     */
    private fun onDocMessage(data: String) {
        val msg = runCatching { EngineJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return
        when (msg["type"]?.jsonPrimitive?.contentOrNull) {
            "serene-doc" -> current?.let { docSessions[id] = it }
            "serene-doc-restored" -> {
                docSessions[id]?.let { s ->
                    s.note("resumed from the back/forward cache")
                    pending = null
                    current = s
                    session = s
                    version++
                }
                // It is on screen as it was left: nothing to wait for.
                painting = false
                docLoading = false
                shown()
                snapshotSoon()
            }
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
     * A site can differ from the setting (⋮ › Dark theme, Settings.darkSite).
     */
    fun applyDarkening() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val on = !printing && app.settings.darkSite(siteOf(url))
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, !on)
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, on)
    }

    /**
     * Darkening for the page that started at [pageUrl]. The setting is one per WebView, so it is
     * set as the new page starts: not before, when the page being left would change with it.
     */
    private fun darkenFor(pageUrl: String) = setDarkening(app.settings.darkSite(siteOf(pageUrl)))

    private fun setDarkening(want: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val on = want && !printing
        if (WebSettingsCompat.isAlgorithmicDarkeningAllowed(webView.settings) != on) WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, on)
    }

    /**
     * While the print dialog has this tab's page (⋮ › Print…): the page isn't darkened, because
     * WebView prints what auto dark mode made of it, a black sheet of paper. The dialog covers the
     * tab meanwhile; the page darkens again when the dialog is done with it.
     */
    var printing = false
        set(value) {
            if (field == value || destroyed) return
            field = value
            applyDarkening()
        }

    fun load(input: String) {
        val target = app.settings.searchEngine.let { resolveInput(input, it.template) }
        // After a crash: the history first, so the new page goes after the lost one and back still goes there.
        recover()
        snapshot()
        userAgentFor(target)
        webView.loadUrl(target)
        url = target
    }

    /** This tab's back/forward list as WebView saves it (the Bundle's byte arrays), for [restore] after a restart. */
    fun history(): Map<String, ByteArray> = unloaded?.state ?: if (destroyed) emptyMap() else stateOf(webView)

    private fun stateOf(view: WebView): Map<String, ByteArray> {
        val b = Bundle()
        if (runCatching { view.saveState(b) }.getOrNull() == null) return emptyMap()
        return b.keySet().mapNotNull { k -> b.getByteArray(k)?.let { k to it } }.toMap()
    }

    /**
     * Back after a restart, on [pageUrl] with the [history] it had (see [history]), so back and the
     * swipes go on from where they were. WebView loads the page itself. Without a usable history,
     * the page alone.
     */
    fun restore(pageUrl: String, history: Map<String, ByteArray>?) {
        // After a crash, the page as it was; after a restart, its site's choice.
        if (isHttp(pageUrl)) {
            setDesktopUa(pageModes[pageUrl] ?: app.settings.isDesktopSite(siteOf(pageUrl)))
            historyTarget = pageUrl
        }
        val list = if (history.isNullOrEmpty()) null
        else runCatching { webView.restoreState(Bundle().apply { history.forEach { (k, v) -> putByteArray(k, v) } }) }.getOrNull()
        val page = list?.currentItem?.url
        if (page == null) return load(pageUrl)
        url = page
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    /** Reload; after a crash, the page comes back (with its history). */
    fun reload() { if (unloaded != null) recover() else webView.reload() }
    fun stop() = webView.stopLoading()
    fun goBack() = step(BACK)
    fun goForward() = step(HistorySwipe.FORWARD)

    /**
     * The back and forward buttons: the page slides as a swipe does, all the way. That also keeps
     * the steps apart: quick taps had WebView's back/forward cache restore one page while the next
     * step started, and the page came back drawing only its background, for good (err.ee on the
     * Fold). A tap while a slide is still on its way does nothing.
     */
    private fun step(dir: Int) {
        // After a crash: the history comes back, and the step is taken in it.
        if (unloaded != null) {
            recover()
            return go(dir)
        }
        if (swipe.active && !swipe.covering) return
        snapshot(beforeSwipe = true) // the page left, for the way back
        if (!swipe.play(dir)) go(dir)
    }

    /** One step through the history: [BACK] or [HistorySwipe.FORWARD]. */
    fun go(dir: Int) {
        if (!webView.canGoBackOrForward(dir)) return
        // The page as it came the last time, desktop or phone (when it loads again, not from the
        // back/forward cache); one this tab hasn't seen load, as its site's choice says.
        val list = webView.copyBackForwardList()
        list.getItemAtIndex(list.currentIndex + dir)?.url?.takeIf(::isHttp)?.let { to ->
            setDesktopUa(pageModes[to] ?: app.settings.isDesktopSite(siteOf(to)))
            historyTarget = to
        }
        webView.goBackOrForward(dir)
    }

    /**
     * ⋮ › Desktop site, Chrome's "Request desktop site": this site asks for desktop pages, or for
     * phone pages again, and the page loads again with it. Remembered per site (Chrome does too);
     * pages in the history keep what they came with (go).
     * A site that sent the phone to its phone pages (en.wikipedia.org to en.m.wikipedia.org) is
     * asked at the address first asked for, as Chrome does, or it would keep its phone pages.
     */
    fun setDesktopSite(on: Boolean) {
        val page = unloaded?.url?.takeIf { it.isNotEmpty() } ?: url
        app.settings.setDesktopSite(siteOf(page), on)
        if (unloaded != null) return recover() // its page comes back with the new user agent
        userAgentFor(page)
        // Only for a phone host (m.): a load adds a history entry, a reload doesn't, and a site
        // that only moved the page (techspot.com to www.techspot.com) gets the same page again.
        val first = webView.copyBackForwardList().currentItem?.originalUrl
        if (first != null && on && isHttp(first) && sentToPhonePages(first, page)) webView.loadUrl(first) else webView.reload()
    }

    /**
     * The user agent and client hints of [pageUrl]'s site: desktop ones (desktopUserAgent) if it
     * is switched to ⋮ › Desktop site, else the WebView's own. Set before a page is asked for: the
     * request goes out with what is set then. True if they changed. Web pages only: an
     * about:blank or a data: page keeps what the tab has.
     */
    private fun userAgentFor(pageUrl: String): Boolean {
        if (!isHttp(pageUrl)) return false
        return setDesktopUa(app.settings.isDesktopSite(siteOf(pageUrl)))
    }

    /** A new window's tab starts with the user agent of [opener], the tab whose page opened it. */
    internal fun userAgentLike(opener: Tab) { setDesktopUa(opener.desktopUa) }

    private fun setDesktopUa(desktop: Boolean): Boolean {
        if (desktop == desktopUa || destroyed) return false
        desktopUa = desktop
        with(webView.settings) {
            userAgentString = if (desktop) desktopUserAgent(mobileUa) else null // null: the WebView's own
            mobileUaMetadata?.let { m ->
                runCatching { WebSettingsCompat.setUserAgentMetadata(this, if (desktop) desktopUserAgentMetadata(m) else m) }
                    .onFailure { Log.w(TAG, "user agent metadata", it) }
            }
        }
        return true
    }

    /**
     * Settings › Accessibility › Text size, on top of the phone's font size (read each time: it
     * can change while Helmi runs, and a new page picks it up).
     */
    private fun applyTextZoom() {
        val zoom = TextScale.textZoom(app.settings.textScale, webView.resources.configuration.fontScale)
        if (webView.settings.textZoom != zoom) webView.settings.textZoom = zoom
    }

    /** Gets the page back that went with its renderer or was [discard]ed, with its back/forward list; WebView loads it. */
    fun recover() {
        val c = unloaded ?: return
        unloaded = null
        if (destroyed) return
        // The crashed page said so on screen; a page that went out of sight comes back quietly.
        waking = !c.onScreen
        restore(c.url, c.state)
    }

    /**
     * After a restart: the tab gets [pageUrl], its [title] and its [history] back, but loads its
     * page only once it is shown, so a restart doesn't load every open tab at once.
     */
    fun restoreLater(pageUrl: String, title: String, history: Map<String, ByteArray>?) {
        url = pageUrl
        this.title = title
        unloaded = Unloaded(pageUrl, history.orEmpty(), Why.DISCARD, onScreen = false)
    }

    /** When the tab opened (SystemClock.elapsedRealtime). */
    internal val createdAt = SystemClock.elapsedRealtime()

    /**
     * Could go now to save memory: has a web page, isn't on screen and isn't in fullscreen. The
     * tab manager also checks the opener links ([TabManager.discardIdle]), and [discard] that
     * nothing is playing or typed in.
     */
    val discardable: Boolean
        get() = !destroyed && unloaded == null && !waking && fullscreen == null && hasPage && !downloadPage &&
            url.startsWith("http") && !(webView.isAttachedToWindow && webView.isShown)

    /**
     * Lets the page go to save memory (Chrome's tab discarding), unless it is busy: media playing,
     * or a form someone typed in (it would be lost). The tab keeps its address, title, card
     * picture and back/forward list, and loads the page again when it is shown. [done] says
     * whether it went.
     */
    fun discard(done: (Boolean) -> Unit = {}) {
        if (!discardable) return done(false)
        webView.evaluateJavascript(BUSY_JS) { busy ->
            if (busy == "true" || !discardable) return@evaluateJavascript done(false)
            val state = stateOf(webView)
            Log.i(TAG, "discarding tab $id (${state.size} history keys)")
            // The swipe pictures are of pages that will load again: they would not match.
            Snapshots.clear(id)
            webView.stopLoading()
            replaceWebView(webView)
            unloaded = Unloaded(url, state, Why.DISCARD, onScreen = false)
            done(true)
        }
    }

    /**
     * WebView's onRenderProcessGone: the renderer of [dead] crashed ([crash]) or was killed. Not
     * handling it kills the app. [dead] can't be used again, so it goes, and a new WebView takes
     * its place; what it showed waits in [unloaded].
     */
    private fun renderGone(dead: WebView, crash: Boolean) {
        if (dead !== webView || destroyed) return
        val onScreen = dead.isAttachedToWindow && dead.isShown && dead.windowVisibility == View.VISIBLE
        val before = unloaded
        // The back/forward list lives on in the app's process, so the dead WebView still saves it.
        val state = before?.state ?: stateOf(dead)
        Log.w(TAG, "renderer ${if (crash) "crashed" else "was killed"}, tab $id (${if (onScreen) "on screen" else "not on screen"}, ${state.size} history keys)")
        replaceWebView(dead)
        // A start page or a download page had nothing to lose.
        val page = url.takeIf { it.isNotEmpty() && it != "about:blank" && !downloadPage }
        unloaded = when {
            page == null && state.isEmpty() -> null
            // A tab already without its page (discarded, say) keeps waiting to be shown.
            before != null && !onScreen -> before
            else -> Unloaded(page ?: "", state, if (crash) Why.CRASH else Why.KILL, onScreen = onScreen || before?.onScreen == true)
        }
    }

    /** [dead] goes, with everything about the page it showed, and a new WebView (with nothing in it) takes its place. */
    private fun replaceWebView(dead: WebView) {
        swipe.reset()
        pull.reset()
        main.removeCallbacks(snapshotLater)
        main.removeCallbacks(shownTimeout)
        onShown = null
        contextMenu = null
        permissions.cancelAll() // its prompts went with the page
        blobUserAgent = null
        pending = null
        current = null
        session = null
        docSessions.clear()
        docLoading = false
        painting = false
        progress = 100
        version++
        (dead.parent as? ViewGroup)?.removeView(dead)
        dimScript = null
        viewportHandler = null
        webView = newWebView(dead.context)
        setUp()
        waking = false
        dead.destroy()
    }

    @Volatile private var destroyed = false

    /** Pages left are kept in WebView's back/forward cache, and swipes show snapshots of them. */
    val backForwardCache get() = backForwardCacheSupported && app.settings.backForwardCache

    /** Closed, but Undo may still bring it back: what plays stops, and what can wait does. */
    fun pause() {
        exitFullscreen()
        webView.evaluateJavascript(PAUSE_MEDIA, null)
        webView.onPause()
    }

    /** Back from [pause]. What was playing stays paused, as after a stop. */
    fun resume() = webView.onResume()

    fun destroy() {
        if (destroyed) return
        exitFullscreen()
        destroyed = true
        main.removeCallbacksAndMessages(null)
        linkHref.removeCallbacksAndMessages(null)
        flags.cancel()
        swipe.reset()
        pull.reset()
        permissions.cancelAll()
        Snapshots.clear(id)
        webView.stopLoading()
        webView.destroy()
    }

    // A new document is loading (start to finish).
    private var docLoading = false
    // Between a new document's start and its first paint: the old page is still on screen.
    private var painting = false
    private var onShown: (() -> Unit)? = null
    private val shownTimeout = Runnable { shown() }

    /**
     * Calls [then] once the page a history step went to has loaded and settled (or after a while,
     * regardless). Not at its first paint, nor at a same-document step: a page that renders in
     * script (GitHub, news sites) first draws a half-built page, which flickered between the
     * snapshot and the finished page.
     */
    fun whenShown(then: () -> Unit) {
        onShown = then
        main.removeCallbacks(shownTimeout)
        main.postDelayed(shownTimeout, 3000)
    }

    /**
     * Has the page say when its DOM stops changing (no new nodes or text for 150 ms; 1.2 s at
     * most, for pages that never stop), then [shown]. Only while a history step waits for it.
     */
    private fun whenSettled() {
        if (onShown == null) return
        webView.evaluateJavascript(SETTLE_JS, null)
    }

    private fun shown() {
        val then = onShown ?: return
        onShown = null
        main.removeCallbacks(shownTimeout)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) return then()
        // The page is ready, but its pixels may be a frame or more behind (a page resumed from the
        // back/forward cache is the old page on screen until its first new frame): wait until what
        // the page is now has been drawn. Not forever: a WebView that does not draw never calls back.
        var done = false
        val once = { if (!done) { done = true; then() } }
        WebViewCompat.postVisualStateCallback(webView, 0) { webView.postOnAnimation(once) }
        main.postDelayed(once, 500)
    }

    private val snapshotLater = Runnable { snapshot() }

    /** The page moved (or a swipe has just shown it): take its snapshot once it has rested a moment. */
    fun snapshotSoon() {
        main.removeCallbacks(snapshotLater)
        main.postDelayed(snapshotLater, 400)
    }

    /**
     * A picture of the content area as it is now, for swiping back (or forward) to this page
     * later. PixelCopy, because draw() of a WebView comes out blank on phones. Not while a
     * swipe or pull is on screen, nor while the tab switcher covers the page, nor while a new page is replacing this one. Only with the
     * back/forward cache: without it the page reloads behind the picture and visibly changes when
     * it shows, so a swipe shows the plain title card instead.
     */
    fun snapshot(beforeSwipe: Boolean = false) {
        if (!backForwardCache) return
        if (destroyed || swipe.active || pull.active || painting || TabPreviews.covered || !webView.isAttachedToWindow) return
        val w = webView.width
        val h = webView.height
        if (w == 0 || h == 0) return
        val window = activityOf(webView.context)?.window ?: return
        val list = webView.copyBackForwardList()
        val item = list.currentItem ?: return
        val index = list.currentIndex
        val xy = IntArray(2).also { webView.getLocationInWindow(it) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(xy[0], xy[1], xy[0] + w, xy[1] + h), bmp, { result ->
                // A swipe that started meanwhile may be in the picture, unless it was asked for
                // just before the swipe (its first frame is the page as it is). A page the cache
                // brought back blank would make every swipe to it blank.
                if (result == PixelCopy.SUCCESS && (beforeSwipe || !swipe.active) && !destroyed && !Snapshots.isFlat(bmp)) Snapshots.put(id, index, Snapshot(item.url, bmp))
            }, main)
        } catch (e: IllegalArgumentException) { // window not attached to a surface yet
        }
    }

    private inner class Client : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            renderGone(view, detail.didCrash())
            return true
        }

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
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) {
                // Leaving this page (a link, a script): its picture for swiping back to it.
                if (request.isForMainFrame && !request.isRedirect) snapshot()
                // A link to a site with the other user agent (⋮ › Desktop site): the request would
                // go out with this one, so it is asked for again with the site's. GETs only: this
                // isn't called for POSTs. A redirect goes on as it is, onPageStarted catches it.
                if (request.isForMainFrame && !request.isRedirect && userAgentFor(request.url.toString())) {
                    val to = request.url.toString()
                    main.post { if (!destroyed && webView === view) view.loadUrl(to) }
                    return true
                }
                return false
            }
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
            pageStarts++
            darkenFor(pageUrl)
            applyTextZoom()
            // A page that came with the other user agent than its site's (a redirect to another
            // site, a window a page opened): once more, with the site's. Then it matches. Not a
            // page from the history: that one keeps what it had (go).
            val fromHistory = pageUrl == historyTarget
            historyTarget = null
            if (!fromHistory && userAgentFor(pageUrl)) main.post { if (!destroyed && webView === view && view.url == pageUrl) view.reload() }
            if (isHttp(pageUrl)) pageModes[pageUrl] = desktopUa
            // As in Chrome, a prompt goes with the page that asked (not the one starting now).
            permissions.pageStarted(pageUrl)
            docLoading = true
            painting = true
            // A new page: the download goes on in the notification and the downloads screen.
            hasPage = true
            downloadPage = false
            downloadBar = null
            // Old WebViews without DOCUMENT_START_SCRIPT: best effort, may lose the race.
            if (!documentStartSupported) {
                view.evaluateJavascript(KEEP_PICTURES, null)
                view.evaluateJavascript(dimPicturesScript(app.settings), null)
                view.evaluateJavascript(viewportScript(app.settings), null)
                view.evaluateJavascript(contentScript(view.context), null)
            }
        }

        override fun onPageCommitVisible(view: WebView, pageUrl: String) {
            painting = false
            waking = false
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            updateNav(pageUrl)
            docLoading = false
            painting = false
            waking = false
            whenSettled()
            pull.done()
            snapshotSoon()
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            updateNav(pageUrl)
            if (!isReload) app.history.visit(pageUrl, view.title.orEmpty())
            // A same-document step (#hash, history.pushState; single-page sites) has no load to
            // wait for, only the page's own redraw. A new document's load is waited for.
            main.postDelayed({ if (!docLoading) whenSettled() }, 50)
            // The picture of where this step went, once it has drawn (a script-made page has no load).
            snapshotSoon()
        }
    }

    private fun updateNav(pageUrl: String) {
        url = pageUrl
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun onDownload(dlUrl: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        // Nothing in the history: the tab was opened for this file (a link from another app).
        // Links on a page keep the page.
        val blank = webView.copyBackForwardList().currentItem == null
        // A target=_blank link to a file: the tab opened for it goes again and the download
        // belongs to the page with the link, as in Chrome.
        val from = manager?.takeIf { blank && !dlUrl.startsWith("blob:") }?.openerOf(this)
        if (from != null) {
            from.downloadBar = app.downloads.start(dlUrl, userAgent, contentDisposition, mimeType, from.referrer, from.id)
            main.post { manager?.closeWindow(this) }
            return
        }
        if (blank) downloadPage = true
        progress = 100
        if (dlUrl.startsWith("blob:")) return fetchBlob(dlUrl, userAgent)
        downloadBar = app.downloads.start(dlUrl, userAgent, contentDisposition, mimeType, if (blank) null else referrer, id)
    }

    /** What requests from this page send as Referer: its origin, as Chrome does across sites. */
    val referrer: String? get() = Uri.parse(url).let { u -> if (u.scheme?.startsWith("http") == true) "${u.scheme}://${u.authority}/" else null }

    val userAgent: String get() = webView.settings.userAgentString

    /** Saves a picture from the page (the context menu's Download image), as a download from this tab. */
    fun downloadImage(src: String) {
        downloadBar = app.downloads.start(src, userAgent, null, null, referrer, id)
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
            Toast.makeText(webView.context, "Helmi couldn't save this file", Toast.LENGTH_SHORT).show()
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
        val backForwardCacheSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)
        val uaMetadataSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)

        private fun isHttp(u: String) = u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

        /**
         * engine/dist/autoconsent.js, then engine/src/content/serene.js, both packaged as assets.
         * One script, so autoconsent is defined before serene.js starts it.
         */
        fun contentScript(context: Context): String = script ?: listOf("autoconsent.js", "serene.js")
            .joinToString("\n") { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
            .also { script = it }

        private tailrec fun activityOf(c: Context?): android.app.Activity? = when (c) {
            is android.app.Activity -> c
            is ContextWrapper -> activityOf(c.baseContext)
            else -> null
        }

        private const val SETTLED = "{\"type\":\"serene-settled\"}"
        private val SETTLE_JS = """(() => {
            const post = () => window.SereneBridge && SereneBridge.postMessage('$SETTLED');
            let quiet;
            const done = () => { o.disconnect(); clearTimeout(quiet); clearTimeout(most); post(); };
            const o = new MutationObserver(() => { clearTimeout(quiet); quiet = setTimeout(done, 150); });
            o.observe(document, { subtree: true, childList: true, characterData: true });
            quiet = setTimeout(done, 150);
            const most = setTimeout(done, 1200);
        })()"""

        /** Top documents only: an id, said at the start and again on coming back from the back/forward cache. */
        private val DOC_JS = """if (window === window.top && window.SereneBridge) {
            const id = Math.random().toString(36).slice(2);
            SereneBridge.postMessage(JSON.stringify({ type: 'serene-doc', id }));
            addEventListener('pageshow', e => { if (e.persisted) SereneBridge.postMessage(JSON.stringify({ type: 'serene-doc-restored', id })); });
        }"""

        private const val PAUSE_MEDIA = "document.querySelectorAll('video, audio').forEach(m => m.pause())"

        /**
         * True when letting the page go would lose something: sound or video playing (a video
         * call too), picture-in-picture, or a form field changed from what the page set (typed
         * text, a ticked box, a picked option). The top document only.
         */
        private const val BUSY_JS = """(() => {
            try {
                if (document.pictureInPictureElement) return true;
                const playing = [...document.querySelectorAll('video, audio')].some(m => !m.paused && !m.ended && !m.muted && m.readyState > 2);
                if (playing) return true;
                return [...document.querySelectorAll('input, textarea, select')].some(e => {
                    if (e.tagName === 'SELECT') return [...e.options].some(o => o.selected !== o.defaultSelected);
                    if (e.type === 'checkbox' || e.type === 'radio') return e.checked !== e.defaultChecked;
                    if (e.type === 'hidden' || e.type === 'submit' || e.type === 'button' || e.type === 'file') return false;
                    return e.value !== e.defaultValue;
                });
            } catch (e) { return false; }
        })()"""

        /**
         * Darkening a page leaves its pictures as they are. WebView would invert the ones it takes
         * for icons or drawings (few colors: logos, diagrams, scans); `color-scheme: only light`
         * opts an element out of forced darkening, and does nothing otherwise. They are dimmed
         * instead, if the user wants (dimPicturesScript).
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

/**
 * What a long press was on: a [link] with its [text] ("" for a picture link), a picture
 * ([image], its address, and [alt], its alt text), or a picture that is a link (both).
 */
data class Pressed(val link: String?, val text: String, val image: String?, val alt: String = "")

fun isWebLink(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

fun isImageSrc(url: String) = isWebLink(url) || url.startsWith("data:image/", ignoreCase = true)

/** URL bar input: a URL as typed, a host without scheme, or a search. */
fun resolveInput(input: String, searchTemplate: String): String {
    val s = input.trim()
    if (s.isEmpty()) return "about:blank"
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) || s.startsWith("about:")) return s
    val looksLikeHost = !s.contains(' ') && (Regex("""^[^/\s]+\.[a-zA-Z]{2,}(:\d+)?(/.*)?$""").matches(s) || s.startsWith("localhost"))
    return if (looksLikeHost) "https://$s" else searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"))
}
