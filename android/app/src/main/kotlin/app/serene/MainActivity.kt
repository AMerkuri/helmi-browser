package app.serene

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import app.serene.browser.FilePickerHost
import app.serene.browser.TabManager
import app.serene.browser.TabPreviews
import app.serene.download.DownloadService
import app.serene.ui.BrowserScreen
import app.serene.ui.CoverFlowTabs
import app.serene.ui.DownloadsScreen
import app.serene.ui.SereneTheme
import app.serene.ui.SettingsScreen
import app.serene.ui.applyNightMode
import app.serene.ui.nightUiMode
import kotlinx.coroutines.flow.drop

enum class Screen { Browser, Tabs, Settings, Downloads }

class MainActivity : ComponentActivity(), FilePickerHost {
    @VisibleForTesting lateinit var tabs: TabManager
        private set
    @VisibleForTesting var screen by mutableStateOf(Screen.Browser)
        private set
    /** Where the page is on screen, in the root's coordinates: the tab switcher's cards grow from and into it. */
    @VisibleForTesting var pageBounds by mutableStateOf(Rect.Zero)
        private set
    private var openingTabs = false

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
        enableEdgeToEdge()
        val app = application as SereneApp
        tabs = TabManager(this, app)
        tabs.restore()
        TabPreviews.restore(this, tabs.tabs)
        handleIntent(intent)

        setContent {
            // Changes only: SereneApp applied the setting at start.
            LaunchedEffect(Unit) {
                snapshotFlow { app.settings.theme }.drop(1).collect { theme ->
                    if (Build.VERSION.SDK_INT >= 31) applyNightMode(this@MainActivity, theme)
                    else if (theme != startTheme) recreate()
                }
            }
            val darkSites = app.settings.darkSites
            LaunchedEffect(darkSites) { tabs.tabs.forEach { it.applyDarkening() } }
            SereneTheme {
                BackHandler(enabled = screen != Screen.Browser) { screen = Screen.Browser }
                when (screen) {
                    // The tab switcher is drawn over the browser, which shows through as a card
                    // grows into its page.
                    Screen.Browser, Screen.Tabs -> Box(Modifier.fillMaxSize()) {
                        BrowserScreen(app, tabs, onOpen = ::open, onPageBounds = { pageBounds = it; tabs.sizeHidden(it.width.toInt(), it.height.toInt()) })
                        if (screen == Screen.Tabs) CoverFlowTabs(tabs, pageBounds, onDone = { screen = Screen.Browser })
                    }
                    Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.Browser })
                    Screen.Downloads -> DownloadsScreen(app.downloads, onBack = { screen = Screen.Browser })
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
        tabs.open(url)
        screen = Screen.Browser
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is handled in place (see the manifest), so the system bar icons picked in onCreate
        // keep the old theme when the system switches to dark: dark icons on a dark bar. Pick again.
        enableEdgeToEdge()
        // Pages follow the theme too; a WebView that isn't on screen misses the change.
        tabs.tabs.forEach { it.applyDarkening() }
    }

    override fun onPause() {
        super.onPause()
        // The page as it was left, for its card after a restart.
        if (screen == Screen.Browser) tabs.current?.let { TabPreviews.capture(it) }
    }

    override fun onStop() {
        super.onStop()
        tabs.save()
        TabPreviews.save(this, tabs.tabs)
    }

    override fun onDestroy() {
        tabs.tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}
