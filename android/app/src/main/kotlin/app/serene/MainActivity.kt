package app.serene

import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.view.WindowManager
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.serene.browser.FilePickerHost
import app.serene.browser.TabDiscard
import app.serene.browser.PermissionHost
import app.serene.browser.SitePermission
import app.serene.browser.TabManager
import app.serene.browser.TabPreviews
import app.serene.download.DownloadService
import app.serene.ui.BookmarksScreen
import app.serene.ui.BrowserScreen
import app.serene.ui.AndroidBlockedDialog
import app.serene.ui.ClosedTabBar
import app.serene.ui.CoverFlowTabs
import app.serene.ui.DownloadsScreen
import app.serene.ui.HistoryScreen
import app.serene.ui.SereneTheme
import app.serene.ui.SettingsScreen
import app.serene.ui.LocalSystemBars
import app.serene.ui.SystemBars
import app.serene.ui.cardTitle
import app.serene.ui.applyNightMode
import app.serene.ui.modelNote
import app.serene.ui.nightUiMode
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

enum class Screen { Browser, Tabs, Settings, Downloads, History, Bookmarks }

class MainActivity : ComponentActivity(), FilePickerHost, PermissionHost {
    @VisibleForTesting lateinit var tabs: TabManager
        private set
    @VisibleForTesting var screen by mutableStateOf(Screen.Browser)
        private set
    /** Where the page is on screen, in the root's coordinates: the tab switcher's cards grow from and into it. */
    @VisibleForTesting var pageBounds by mutableStateOf(Rect.Zero)
        private set
    private var openingTabs = false
    private lateinit var batterySaver: BatterySaverNotice
    /** "Closed <page>", with Undo, and the model notes (Settings › Flags). */
    @VisibleForTesting val snackbar = SnackbarHostState()
    /** A page's video in fullscreen, over everything. */
    @VisibleForTesting val fullscreen = FullscreenHost(this)
    private val systemBars = SystemBars(this)

    // The Theme setting this activity was created with, for API < 31 (see attachBaseContext).
    private var startTheme = ThemeMode.SYSTEM

    private var pickDone: ((Array<Uri>?) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val uris = when {
            r.resultCode != RESULT_OK -> null
            data?.clipData != null -> data.clipData!!.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } }
            else -> WebChromeClient.FileChooserParams.parseResult(r.resultCode, data)
        }
        pickDone?.invoke(uris)
        pickDone = null
    }

    override fun pickFiles(params: WebChromeClient.FileChooserParams, done: (Array<Uri>?) -> Unit): Boolean {
        pickDone?.invoke(null) // a page may only have one picker open
        val intent = params.createIntent()
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        return try {
            picker.launch(intent)
            pickDone = done
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    // Android's own camera, microphone and location permissions, asked for when a site the user
    // allowed needs one. One system dialog at a time: the others wait their turn.
    private val permissionQueue = ArrayDeque<Pair<Set<SitePermission>, (Boolean) -> Unit>>()
    private var permissionAsking: Pair<Set<SitePermission>, (Boolean) -> Unit>? = null
    /** Android blocks these for Helmi for good ("Don't allow" twice): the way to its settings. */
    @VisibleForTesting var blockedByAndroid by mutableStateOf<Set<SitePermission>?>(null)
        private set
    private val androidPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (kinds, done) = permissionAsking ?: return@registerForActivityResult
        permissionAsking = null
        done(kinds.all(::hasAndroidPermission))
        nextAndroidPermission()
    }

    private fun hasAndroidPermission(kind: SitePermission) =
        kind.android.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun requestAndroidPermissions(kinds: Set<SitePermission>, done: (Boolean) -> Unit) {
        if (kinds.all(::hasAndroidPermission)) return done(true)
        permissionQueue.addLast(kinds to done)
        if (permissionAsking == null) nextAndroidPermission()
    }

    private fun nextAndroidPermission() {
        val next = permissionQueue.removeFirstOrNull() ?: return
        val (kinds, done) = next
        val missing = kinds.filterNot(::hasAndroidPermission)
        if (missing.isEmpty()) { done(true); return nextAndroidPermission() }
        val settings = (application as SereneApp).settings
        // Asked before and no rationale to show: Android answers no without a dialog. Say where
        // to turn it on instead of failing silently.
        val forGood = missing.filter { k -> k.android.all { it in settings.askedAndroidPermissions && !shouldShowRequestPermissionRationale(it) } }
        if (forGood.isNotEmpty()) {
            blockedByAndroid = forGood.toSet()
            done(false)
            return nextAndroidPermission()
        }
        val perms = missing.flatMap { it.android }
        settings.askedAndroidPermissions += perms
        permissionAsking = next
        androidPermissions.launch(perms.toTypedArray())
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        startTheme = (newBase.applicationContext as SereneApp).settings.theme
        // Before API 31 there is no per-app night mode: override this activity's own configuration.
        if (Build.VERSION.SDK_INT < 31) {
            val night = nightUiMode(startTheme) ?: return
            val uiMode = newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
            applyOverrideConfiguration(Configuration().also { it.uiMode = uiMode })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemBars.apply()
        val app = application as SereneApp
        tabs = TabManager(this, app)
        batterySaver = BatterySaverNotice(app)
        tabs.restore()
        TabPreviews.restore(this, tabs.tabs)
        // Private tabs open when the app was last killed: their profile goes now.
        app.privacy.cleanUp()
        handleIntent(intent)

        setContent {
            // The tab switcher and the reader asking for their own bar icons, or going.
            LaunchedEffect(Unit) { snapshotFlow { systemBars.asked }.collect { systemBars.apply() } }
            // Changes only: SereneApp applied the setting at start.
            LaunchedEffect(Unit) {
                snapshotFlow { app.settings.theme }.drop(1).collect { theme ->
                    if (Build.VERSION.SDK_INT >= 31) applyNightMode(this@MainActivity, theme)
                    else if (theme != startTheme) recreate()
                }
            }
            val darkSites = app.settings.darkSites
            LaunchedEffect(darkSites) { tabs.tabs.forEach { it.applyDarkening() } }
            // As in Chrome: the closed tab can come back until its bar goes, or the next one closes.
            val closed = tabs.closed
            LaunchedEffect(closed) {
                if (closed == null) return@LaunchedEffect
                val r = snackbar.showSnackbar("Closed ${cardTitle(closed.tab)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) tabs.reopen(closed) else tabs.forget(closed)
            }
            // Settings › Flags › Show model decisions: a note per model verdict on the tab in front.
            // Shown from this scope, so the next page change does not cancel a note on screen.
            val tab = tabs.current
            val version = tab?.version
            val notes = rememberCoroutineScope()
            LaunchedEffect(tab, version) {
                val s = tab?.session ?: return@LaunchedEffect
                val recs = s.takeModelNotices()
                if (!app.settings.showModelDecisions) return@LaunchedEffect
                recs.forEach { rec ->
                    notes.launch {
                        val r = snackbar.showSnackbar(modelNote(rec), actionLabel = if (rec.acted) "Undo" else "Hide", duration = SnackbarDuration.Short)
                        if (r != SnackbarResult.ActionPerformed) return@launch
                        if (rec.acted) s.undo(rec) else s.hide(rec)
                        tab.reload()
                    }
                }
            }
            // A page's video (or other element) in fullscreen: over every screen until it exits.
            val fullTab = tabs.tabs.firstOrNull { it.fullscreen != null }
            val full = fullTab?.fullscreen
            DisposableEffect(full) {
                full?.let(fullscreen::show)
                onDispose { fullscreen.hide() }
            }
            val video = fullTab?.fullscreenVideo
            LaunchedEffect(full, video) { fullscreen.orient(video) }
            // A private tab on screen, or private cards in the tab switcher: no screenshots or
            // screen recording, and a blank picture in the recent apps, as Chrome's Incognito.
            val secure = when (screen) {
                Screen.Browser -> tabs.current?.isPrivate == true
                Screen.Tabs -> tabs.tabs.any { it.isPrivate }
                else -> false
            }
            LaunchedEffect(secure) {
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            SereneTheme {
                CompositionLocalProvider(LocalSystemBars provides systemBars) {
                    BackHandler(enabled = screen != Screen.Browser) { screen = Screen.Browser }
                    Box(Modifier.fillMaxSize()) {
                        when (screen) {
                            // The tab switcher is drawn over the browser, which shows through as a card
                            // grows into its page.
                            Screen.Browser, Screen.Tabs -> Box(Modifier.fillMaxSize()) {
                                BrowserScreen(app, tabs, onOpen = ::open, onPageBounds = { pageBounds = it; tabs.sizeHidden(it.width.toInt(), it.height.toInt()) }, covered = screen == Screen.Tabs)
                                if (screen == Screen.Tabs) CoverFlowTabs(app, tabs, pageBounds, onDone = { screen = Screen.Browser })
                            }
                            Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.Browser })
                            Screen.Downloads -> DownloadsScreen(app.downloads, onBack = { screen = Screen.Browser })
                            Screen.History -> HistoryScreen(app, onBack = { screen = Screen.Browser }, onOpen = ::openPage)
                            Screen.Bookmarks -> BookmarksScreen(app.bookmarks, onOpen = ::openPage, onBack = { screen = Screen.Browser })
                        }
                        // Over every screen, so its timer runs out (and the tab is let go) wherever you are.
                        // Just above the bottom bar (with the bar's own 12 dp), and the tab switcher's buttons.
                        SnackbarHost(
                            snackbar,
                            Modifier
                                .align(Alignment.BottomCenter)
                                .widthIn(max = 600.dp)
                                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                                .padding(bottom = 52.dp),
                        ) { ClosedTabBar(it) }
                    }
                    blockedByAndroid?.let { kinds ->
                        AndroidBlockedDialog(kinds, onDismiss = { blockedByAndroid = null }, onSettings = {
                            blockedByAndroid = null
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        })
                    }
                }
            }
        }
    }

    /** Opens [to]. The tab switcher once the page's picture is taken, so its card starts as the page. */
    @VisibleForTesting fun open(to: Screen) {
        val tab = tabs.current
        if (to != Screen.Tabs || screen != Screen.Browser || tab == null) {
            screen = to
            return
        }
        if (openingTabs) return
        openingTabs = true
        TabPreviews.capture(tab) {
            openingTabs = false
            if (screen == Screen.Browser) screen = Screen.Tabs
        }
    }

    /** A page from the history or the bookmarks: in the tab in front (a new one for [newTab]), as Chrome does. */
    private fun openPage(url: String, newTab: Boolean) {
        val tab = tabs.current
        if (newTab || tab == null) tabs.open(url) else tab.load(url)
        screen = Screen.Browser
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Links opened from other apps (Serene can be the default browser). */
    private fun handleIntent(intent: Intent?) {
        // The download notification.
        if (intent?.action == DownloadService.ACTION_SHOW) {
            screen = Screen.Downloads
            return
        }
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return
        tabs.tabs.forEach { it.exitFullscreen() }
        tabs.open(url)
        screen = Screen.Browser
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is handled in place (see the manifest): the bar icons for the new theme, unless the
        // screen on top has its own. (enableEdgeToEdge also does this itself after a configuration
        // change; this keeps it from depending on that.)
        systemBars.apply()
        // Pages follow the theme too; a WebView that isn't on screen misses the change.
        tabs.tabs.forEach { it.applyDarkening() }
        fullscreen.onConfigurationChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen.onWindowFocused()
    }

    override fun onStart() {
        super.onStart()
        batterySaver.start(this)
        idleTabs.run()
    }

    // While on screen: background tabs that have been idle long enough let their page go.
    private val main = Handler(Looper.getMainLooper())
    private val idleTabs = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            tabs.discardIdle()
            main.postDelayed(this, 60_000)
        }
    }

    // Out of sight and next in line to be killed (BACKGROUND, every Android version), or low on
    // memory while in use (the RUNNING_ levels, before Android 14): tabs let their pages go before
    // Android takes the renderer, and every page with it, or the whole app.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> tabs.discardIdle(TabDiscard.Pressure.LOW)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> tabs.discardIdle(TabDiscard.Pressure.BACKGROUND)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> tabs.discardIdle(TabDiscard.Pressure.LOW)
        }
    }

    override fun onPause() {
        super.onPause()
        // The page as it was left, for its card after a restart.
        if (screen == Screen.Browser) tabs.current?.let { TabPreviews.capture(it) }
    }

    override fun onStop() {
        super.onStop()
        batterySaver.stop(this)
        main.removeCallbacks(idleTabs)
        tabs.save()
        TabPreviews.save(this, tabs.tabs)
        (application as SereneApp).stats.save()
        (application as SereneApp).history.save()
    }

    override fun onDestroy() {
        // Private tabs don't outlive the activity: their profile is deleted with them.
        tabs.destroyAll()
        super.onDestroy()
    }
}
