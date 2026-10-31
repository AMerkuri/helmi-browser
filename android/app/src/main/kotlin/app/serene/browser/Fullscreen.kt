package app.serene.browser

import android.content.pm.ActivityInfo
import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.ui.unit.IntSize

/**
 * A page's element in fullscreen (a video, usually) as WebView hands it over in
 * onShowCustomView. The activity shows [view] over everything until the page leaves fullscreen
 * (onHideCustomView) or [exit] asks WebView to (back, the tab going away).
 */
class Fullscreen(val view: View, private val callback: WebChromeClient.CustomViewCallback) {
    private var exited = false

    fun exit() {
        if (exited) return
        exited = true
        callback.onCustomViewHidden()
    }
}

/**
 * The orientation a fullscreen video turns the screen to, as Chrome does on phones: landscape for
 * a landscape video, portrait for a portrait one (Shorts, Reels), whichever way the phone is held.
 * Not on a big screen (smallest width 600 dp and up: a tablet, the Fold's inner screen), where
 * turning the device gains little; Chrome only does it on phones, and Android 16 ignores it there.
 * A video of unknown size (a fullscreen game, a video in a frame that said nothing) or a square
 * one leaves the screen as it is.
 */
fun fullscreenOrientation(video: IntSize?, smallestWidthDp: Int): Int = when {
    video == null || smallestWidthDp >= 600 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    video.width > video.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    video.height > video.width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/**
 * In every frame: when the frame's document goes fullscreen, the size of the video in it (the
 * element itself, or the biggest video inside it, as on YouTube where a player box goes
 * fullscreen), and again when that video's size changes (an ad, then the film). A frame whose
 * fullscreen element is a frame says nothing; the frame inside it does.
 */
internal const val FULLSCREEN_JS = """(() => {
    const bridge = window.SereneBridge;
    if (!bridge || window.__helmiFullscreen) return;
    window.__helmiFullscreen = true;
    const watched = new WeakSet();
    const videoIn = el => {
        if (el instanceof HTMLVideoElement) return el;
        const all = [...el.querySelectorAll('video'), ...(el.shadowRoot ? el.shadowRoot.querySelectorAll('video') : [])];
        let best = null, area = -1;
        for (const v of all) {
            const r = v.getBoundingClientRect();
            if (r.width * r.height > area) { best = v; area = r.width * r.height; }
        }
        return best;
    };
    const say = () => {
        const el = document.fullscreenElement || document.webkitFullscreenElement;
        const v = el && videoIn(el);
        if (!v) return;
        if (!watched.has(v)) {
            watched.add(v);
            v.addEventListener('loadedmetadata', say);
            v.addEventListener('resize', say);
        }
        if (v.videoWidth && v.videoHeight) bridge.postMessage(JSON.stringify({ type: 'helmi-fullscreen', w: v.videoWidth, h: v.videoHeight }));
    };
    document.addEventListener('fullscreenchange', say);
    document.addEventListener('webkitfullscreenchange', say);
})()"""
