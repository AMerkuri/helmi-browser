package app.serene.browser

import android.content.pm.ActivityInfo
import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/**
 * A page's element in fullscreen (a video, usually) as WebView hands it over in
 * onShowCustomView. The activity shows [view] over everything until the page leaves fullscreen
 * (onHideCustomView) or [exit] asks WebView to (back, the tab going away).
 */
class Fullscreen(val view: View, private val callback: WebChromeClient.CustomViewCallback) {
    private var exited = false

    /**
     * The page asked for picture-in-picture (video.requestPictureInPicture(), [PIP_JS]): this
     * fullscreen is only the way there, and the video goes into a small window at once.
     */
    var pictureInPicture by mutableStateOf(false)

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
 * The fullscreen video as its frame tells it: its size, whether it plays, and whether the page
 * lets it go picture-in-picture (no disablePictureInPicture on it).
 */
data class FullscreenMedia(val size: IntSize, val playing: Boolean, val pipAllowed: Boolean)

/**
 * Whether leaving the app (home, recent apps) takes the fullscreen video along in a small
 * window, as Chrome does: only a video that plays, and that its page doesn't keep out of
 * picture-in-picture. A paused video, a fullscreen game or a slide show stays behind.
 */
fun autoPictureInPicture(media: FullscreenMedia?): Boolean = media != null && media.playing && media.pipAllowed

/**
 * The picture-in-picture window's shape for a video, as width to height. Android takes nothing
 * wider than 2.39:1 or taller than 1:2.39 (it throws), so a video beyond that is cut to the
 * limit; a video of unknown size gets 16:9.
 */
fun pipAspect(video: IntSize?): Pair<Int, Int> {
    if (video == null || video.width <= 0 || video.height <= 0) return 16 to 9
    val r = video.width.toDouble() / video.height
    return when {
        r > MAX_PIP_ASPECT -> 238 to 100
        r < 1 / MAX_PIP_ASPECT -> 100 to 238
        else -> video.width to video.height
    }
}

/** A bit inside Android's 2.39, so rounding never takes a ratio over it. */
private const val MAX_PIP_ASPECT = 2.38

/**
 * Where a video of [video]'s shape is drawn in a [box] (in the box's coordinates): as big as
 * fits, in the middle, black bars on the other sides. The window grows out of it on its way to
 * picture-in-picture.
 */
fun letterbox(box: IntRect, video: IntSize): IntRect {
    if (box.width <= 0 || box.height <= 0 || video.width <= 0 || video.height <= 0) return box
    val scale = minOf(box.width.toDouble() / video.width, box.height.toDouble() / video.height)
    val w = (video.width * scale).toInt().coerceIn(1, box.width)
    val h = (video.height * scale).toInt().coerceIn(1, box.height)
    val left = box.left + (box.width - w) / 2
    val top = box.top + (box.height - h) / 2
    return IntRect(left, top, left + w, top + h)
}

/**
 * In every frame: when the frame's document goes fullscreen, the video in it (the element
 * itself, or the biggest video inside it, as on YouTube where a player box goes fullscreen): its
 * size, whether it plays, and whether it may go picture-in-picture. Again when any of that
 * changes (an ad, then the film; play, pause). A frame whose fullscreen element is a frame says
 * nothing; the frame inside it does.
 * The app answers that frame with {type: 'helmi-media', play} from the picture-in-picture
 * window's play and pause buttons, and to pause the video when the window closes (by then it
 * may have left fullscreen: the last fullscreen video is the one).
 */
internal const val FULLSCREEN_JS = """(() => {
    const bridge = window.SereneBridge;
    if (!bridge || window.__helmiFullscreen) return;
    window.__helmiFullscreen = true;
    const post = bridge.postMessage.bind(bridge);
    const watched = new WeakSet();
    let last = null;
    const fullscreenElement = () => document.fullscreenElement || document.webkitFullscreenElement;
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
    const fullscreenVideo = () => {
        const el = fullscreenElement();
        return el && videoIn(el);
    };
    const say = () => {
        const v = fullscreenVideo();
        if (!v) return;
        last = v;
        if (!watched.has(v)) {
            watched.add(v);
            for (const t of ['loadedmetadata', 'resize', 'play', 'pause', 'ended']) v.addEventListener(t, say);
        }
        if (v.videoWidth && v.videoHeight) {
            post(JSON.stringify({ type: 'helmi-fullscreen', w: v.videoWidth, h: v.videoHeight, playing: !v.paused && !v.ended, pip: !v.disablePictureInPicture }));
        }
    };
    document.addEventListener('fullscreenchange', say);
    document.addEventListener('webkitfullscreenchange', say);
    if (typeof bridge.addEventListener === 'function') {
        bridge.addEventListener('message', e => {
            let m;
            try { m = JSON.parse(e.data); } catch (x) { return; }
            if (!m || m.type !== 'helmi-media') return;
            const v = fullscreenVideo() || last;
            if (!v) return;
            if (m.play) v.play().catch(() => {}); else v.pause();
        });
    }
})()"""

/**
 * The Picture-in-Picture API for pages (video.requestPictureInPicture(), a player's
 * picture-in-picture button), which WebView has but always refuses: document.pictureInPictureEnabled
 * is false. Where it is, in a secure context (as the API is), requestPictureInPicture() puts the
 * video in fullscreen and tells the app first ({type: 'helmi-pip'}), and the app takes the
 * fullscreen video into Android's picture-in-picture window. Back in the app the video leaves
 * fullscreen and plays in the page again, as Chrome's "back to tab".
 * Needs a tap or a key press, as the real one does when no video is in picture-in-picture yet
 * (and as fullscreen does anyway). The page sees enterpictureinpicture and leavepictureinpicture
 * on the video, document.pictureInPictureElement, and a PictureInPictureWindow-like object with
 * the window's size. Only on a phone that has picture-in-picture (see Tab).
 */
internal const val PIP_JS = """(() => {
    const bridge = window.SereneBridge;
    if (!bridge || !window.isSecureContext || document.pictureInPictureEnabled || window.__helmiPip) return;
    if (typeof Element.prototype.requestFullscreen !== 'function') return;
    window.__helmiPip = true;
    const post = bridge.postMessage.bind(bridge);
    const stringify = JSON.stringify;
    const fullscreenElement = () => document.fullscreenElement || document.webkitFullscreenElement;
    let pip = null;
    let win = null;
    const makeWindow = () => {
        const w = new EventTarget();
        Object.defineProperties(w, {
            width: { get: () => innerWidth, enumerable: true },
            height: { get: () => innerHeight, enumerable: true },
            onresize: { value: null, writable: true, enumerable: true },
        });
        return w;
    };
    const fire = (v, type) => {
        const e = new Event(type, { bubbles: true });
        Object.defineProperty(e, 'pictureInPictureWindow', { value: win, enumerable: true });
        v.dispatchEvent(e);
    };
    const leave = () => {
        if (!pip) return;
        const v = pip;
        pip = null;
        fire(v, 'leavepictureinpicture');
        win = null;
    };
    const changed = () => { if (pip && fullscreenElement() !== pip) leave(); };
    document.addEventListener('fullscreenchange', changed);
    document.addEventListener('webkitfullscreenchange', changed);
    addEventListener('resize', () => {
        if (win && typeof win.onresize === 'function') try { win.onresize(new Event('resize')); } catch (e) {}
        if (win) win.dispatchEvent(new Event('resize'));
    });
    const methods = {
        requestPictureInPicture() {
            const v = this;
            if (!(v instanceof HTMLVideoElement)) return Promise.reject(new TypeError('Illegal invocation'));
            if (v.disablePictureInPicture) return Promise.reject(new DOMException('"disablePictureInPicture" attribute is present.', 'InvalidStateError'));
            if (v.readyState === 0) return Promise.reject(new DOMException('Metadata for the video element are not loaded yet.', 'InvalidStateError'));
            if (pip === v) return Promise.resolve(win);
            const ua = navigator.userActivation;
            if (ua && !ua.isActive) return Promise.reject(new DOMException('Must be handling a user gesture to request picture in picture.', 'NotAllowedError'));
            post(stringify({ type: 'helmi-pip' }));
            let asked;
            try { asked = v.requestFullscreen({ navigationUI: 'hide' }); } catch (e) { return Promise.reject(e); }
            return Promise.resolve(asked).then(() => {
                if (pip && pip !== v) leave();
                pip = v;
                win = makeWindow();
                fire(v, 'enterpictureinpicture');
                return win;
            });
        },
        exitPictureInPicture() {
            if (!pip) return Promise.reject(new DOMException('There is no Picture-in-Picture element in this document.', 'InvalidStateError'));
            return document.exitFullscreen();
        },
    };
    const value = v => ({ value: v, writable: true, enumerable: true, configurable: true });
    Object.defineProperty(HTMLVideoElement.prototype, 'requestPictureInPicture', value(methods.requestPictureInPicture));
    Object.defineProperty(Document.prototype, 'exitPictureInPicture', value(methods.exitPictureInPicture));
    Object.defineProperty(Document.prototype, 'pictureInPictureEnabled', { get() { return true; }, enumerable: true, configurable: true });
    Object.defineProperty(Document.prototype, 'pictureInPictureElement', { get() { return this === document ? pip : null; }, enumerable: true, configurable: true });
})()"""
