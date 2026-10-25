package app.serene

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.browser.TabManager
import app.serene.ui.BrowserScreen
import app.serene.ui.SereneTheme
import app.serene.ui.SettingsScreen
import app.serene.ui.TabsScreen

enum class Screen { Browser, Tabs, Settings }

class MainActivity : ComponentActivity() {
    @VisibleForTesting lateinit var tabs: TabManager
        private set
    private var screen by mutableStateOf(Screen.Browser)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SereneApp
        tabs = TabManager(this, app)
        tabs.restore()
        handleIntent(intent)

        setContent {
            SereneTheme {
                BackHandler(enabled = screen != Screen.Browser) { screen = Screen.Browser }
                when (screen) {
                    Screen.Browser -> BrowserScreen(app, tabs, onOpen = { screen = it })
                    Screen.Tabs -> TabsScreen(tabs, onDone = { screen = Screen.Browser })
                    Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.Browser })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Links opened from other apps (Serene can be the default browser). */
    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return
        tabs.open(url)
        screen = Screen.Browser
    }

    override fun onStop() {
        super.onStop()
        tabs.save()
    }

    override fun onDestroy() {
        tabs.tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}
