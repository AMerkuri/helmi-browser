package app.serene.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.SereneApp
import app.serene.engine.VerdictCache

/**
 * Private tabs, Chrome's Incognito. Their pages run in a WebView profile of their own (cookies,
 * storage, cache, service workers apart from normal tabs'), which is deleted when the last private
 * tab closes, along with everything Helmi kept for them in memory: what the engine learned about
 * their sites ([cache]), the camera, microphone and location answers ([permissions]) and their
 * downloads' place in the downloads list (the files stay, as in Chrome).
 *
 * Nothing about them is written: no history, no saved tabs (they don't come back after a
 * restart), no card pictures on disk, no start page totals. Each run of private tabs gets a
 * profile with a new name, so one whose deletion has to wait (WebView still letting go of it)
 * never carries over into the next; one left by an app that was killed with private tabs open is
 * deleted at the next start ([cleanUp]).
 *
 * Needs WebView's multi-profile support (WebView 110 and later); without it there are no private
 * tabs, and the menus don't offer them.
 */
class PrivateBrowsing(private val app: SereneApp) {
    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private val main = Handler(Looper.getMainLooper())

    // The profile of the private tabs open now; null when there are none.
    private var name: String? = null

    /** What the engine learns in private tabs: a layer over the normal cache, in memory only. */
    @Volatile var cache = VerdictCache(under = app.engine.cache)
        private set

    /** The site permission answers given in private tabs, in memory only. */
    var permissions = SitePermissions.inMemory(app.sitePermissions)
        private set

    /** The profile name for a private tab's WebView, created with the first. Main thread. */
    fun profile(): String = name ?: "$PREFIX${System.currentTimeMillis()}".also {
        ProfileStore.getInstance().getOrCreateProfile(it)
        name = it
        Log.i(TAG, "private profile $it")
    }

    /** The private tabs' cookies, for the requests Helmi makes for them (downloads, pictures). */
    val cookies: CookieManager get() = ProfileStore.getInstance().getOrCreateProfile(profile()).cookieManager

    /** The private tabs' local storage, IndexedDB and caches. */
    val storage: WebStorage get() = ProfileStore.getInstance().getOrCreateProfile(profile()).webStorage

    /** Puts [webView] in the private profile. Before anything else is done with it: a WebView's profile can't change once it is used. */
    fun use(webView: android.webkit.WebView) = WebViewCompat.setProfile(webView, profile())

    /**
     * The last private tab is gone (closed, and past its Undo): the profile is deleted, with its
     * cookies, storage and cache, and what Helmi kept for the private tabs goes too.
     */
    fun end() {
        val n = name ?: return
        name = null
        cache = VerdictCache(under = app.engine.cache)
        permissions = SitePermissions.inMemory(app.sitePermissions)
        app.downloads.forgetPrivate()
        delete(n, 0)
    }

    // A WebView lets go of its profile shortly after destroy(); until then deleting it throws.
    private fun delete(profile: String, attempt: Int) {
        main.post {
            try {
                ProfileStore.getInstance().deleteProfile(profile)
                Log.i(TAG, "private profile $profile deleted")
            } catch (e: IllegalStateException) {
                if (attempt < 6) main.postDelayed({ delete(profile, attempt + 1) }, 500L shl attempt)
                else Log.w(TAG, "private profile $profile still in use, deleted at the next start")
            }
        }
    }

    /** At start: deletes the profiles of private tabs that were open when the app was killed. */
    fun cleanUp() {
        if (!supported) return
        val store = ProfileStore.getInstance()
        for (p in store.allProfileNames) {
            if (!p.startsWith(PREFIX) || p == name) continue
            runCatching { store.deleteProfile(p) }.onSuccess { Log.i(TAG, "left-over private profile $p deleted") }
        }
    }

    private companion object {
        const val TAG = "SereneTabs"
        const val PREFIX = "private-"
    }
}
