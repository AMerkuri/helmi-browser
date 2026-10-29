package app.serene.browser

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import app.serene.Settings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Settings › Theme › "Dim bright pictures": in the dark theme, a picture much brighter than the
 * dark page around it (a chart or drawing on white) is a glaring spot at night. Pictures are never
 * inverted, so such a picture is dimmed instead (CSS brightness(), colors kept).
 *
 * Which pictures are bright is read off the screen, not the picture's data: most pictures come
 * from another origin, and a canvas can't read those. [JS] (top document) lists the pictures on
 * screen once scrolling rests, with their places; [onMessage] takes the page as drawn (PixelCopy,
 * a quarter of its size) and answers which to dim. Taking it off the screen also says whether the
 * page around a picture is dark, whatever made it dark: the site's own dark theme, or WebView's.
 * A light page (the site's dark theme switched off in ⋮) keeps its pictures as they are.
 */
internal class BrightPictures(
    private val webView: WebView,
    private val settings: Settings,
    private val main: Handler,
    private val window: () -> Window?,
    /** False while something else is on the page (a swipe, the tab switcher): try later. */
    private val canTake: () -> Boolean,
) {
    // The top document's bridge, for [reset]. A new document replaces it with its first message.
    private var reply: JavaScriptReplyProxy? = null

    fun onMessage(data: String, reply: JavaScriptReplyProxy) {
        this.reply = reply
        val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (!settings.dimPictures) return answer(reply, JSONObject().put("off", true))
        val window = window()
        val w = webView.width
        val h = webView.height
        val vw = msg.optDouble("w")
        if (window == null || !canTake() || !webView.isAttachedToWindow || w == 0 || h == 0 || !(vw > 0)) return answer(reply, RETRY)
        val pics = msg.optJSONArray("pics") ?: return
        val bw = max(1, w / 4)
        val bh = max(1, h / 4)
        // Bitmap px per CSS px of the visual viewport, whatever the zoom.
        val f = bw / vw
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val xy = IntArray(2).also { webView.getLocationInWindow(it) }
        val scrollX = webView.scrollX
        val scrollY = webView.scrollY
        try {
            PixelCopy.request(window, Rect(xy[0], xy[1], xy[0] + w, xy[1] + h), bmp, { result ->
                // A scroll that started meanwhile moved the pictures away from where the page said.
                if (result != PixelCopy.SUCCESS || webView.scrollX != scrollX || webView.scrollY != scrollY || !canTake()) {
                    answer(reply, RETRY)
                } else {
                    val px = IntArray(bw * bh).also { bmp.getPixels(it, 0, bw, 0, 0, bw, bh) }
                    answer(reply, judge(px, bw, bh, pics, f))
                }
                bmp.recycle()
            }, main)
        } catch (e: IllegalArgumentException) { // window not attached to a surface yet
            answer(reply, RETRY)
        }
    }

    /** The setting, its level or the page's darkness changed: undim everything and look again. */
    fun reset() {
        reply?.let { answer(it, JSONObject().put("type", "serene-pics-reset")) }
    }

    private fun judge(px: IntArray, bw: Int, bh: Int, pics: JSONArray, f: Double): JSONObject {
        fun box(p: JSONObject, x: String, y: String, w: String, h: String) = PictureLight.Box(
            (p.optDouble(x) * f).roundToInt(), (p.optDouble(y) * f).roundToInt(),
            ((p.optDouble(x) + p.optDouble(w)) * f).roundToInt(), ((p.optDouble(y) + p.optDouble(h)) * f).roundToInt(),
        )
        val list = (0 until pics.length()).mapNotNull { pics.optJSONObject(it) }
        val seen = list.map { box(it, "x", "y", "w", "h") }
        val whole = list.map { box(it, "fx", "fy", "fw", "fh") }
        val page = PictureLight.pageLuma(px, bw, bh, whole)
        val dim = JSONArray()
        val keep = JSONArray()
        list.forEachIndexed { i, p ->
            val bright = PictureLight.dims(px, bw, bh, seen[i], whole[i], page, ring = max(2, (12 * f).roundToInt()))
            (if (bright) dim else keep).put(p.optInt("id"))
        }
        return JSONObject().put("dim", dim).put("keep", keep)
    }

    private fun answer(reply: JavaScriptReplyProxy, msg: JSONObject) {
        if (!msg.has("type")) msg.put("type", "serene-pics-done").put("level", settings.dimLevel / 100.0)
        try { reply.postMessage(msg.toString()) } catch (e: Exception) { Log.w("BrightPictures", "reply dropped: ${e.message}") }
    }

    companion object {
        private val RETRY get() = JSONObject().put("retry", true)

        /**
         * Top document only (the places are the top window's). Lists the pictures that are on
         * screen and not looked at yet, after scrolling (or a picture loading) rests; dims the
         * ones the app answers are bright. Says nothing in a light theme.
         */
        const val JS = """(() => {
            if (window !== window.top || !window.SereneBridge || !window.visualViewport) return;
            const bridge = window.SereneBridge;
            const dark = matchMedia('(prefers-color-scheme: dark)');
            const MIN = 64;
            const ATTR = 'data-serene-dim';
            let sheet;
            try {
                sheet = new CSSStyleSheet();
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            } catch (e) { return; }
            const setLevel = (l) => sheet.replaceSync('img[' + ATTR + '] { filter: brightness(' + l + ') !important; transition: filter .3s !important; }');
            // Each picture looked at, with the address it showed then (a lazy one changes it).
            let seen = new WeakMap();
            const tries = new WeakMap();
            let asked = null;
            let off = false;
            let timer = 0;
            const later = (ms) => { clearTimeout(timer); timer = setTimeout(look, ms); };
            const look = () => {
                if (asked && performance.now() - asked.at < 3000) return;
                asked = null;
                if (off || !dark.matches || document.visibilityState !== 'visible') return;
                const vv = visualViewport;
                const W = vv.width, H = vv.height, ox = vv.offsetLeft, oy = vv.offsetTop;
                const pics = [], imgs = new Map();
                for (const img of document.images) {
                    if (pics.length >= 20) break;
                    if (seen.get(img) === img.currentSrc || !img.complete || !img.naturalWidth) continue;
                    // Dimmed for the picture it showed before: undim, and look once that is drawn.
                    if (img.hasAttribute(ATTR)) { img.removeAttribute(ATTR); seen.delete(img); later(400); continue; }
                    const r = img.getBoundingClientRect();
                    if (r.width < MIN || r.height < MIN) continue;
                    const x0 = Math.max(r.left - ox, 0), y0 = Math.max(r.top - oy, 0);
                    const x1 = Math.min(r.right - ox, W), y1 = Math.min(r.bottom - oy, H);
                    // Mostly on screen, or a big enough part of a tall one.
                    if (x1 - x0 < MIN || y1 - y0 < MIN) continue;
                    if ((x1 - x0) * (y1 - y0) < Math.min(0.5 * r.width * r.height, 200 * 200)) continue;
                    const cs = getComputedStyle(img);
                    if (cs.visibility !== 'visible' || +cs.opacity < 0.9) continue;
                    const id = pics.length + 1;
                    imgs.set(id, { img, src: img.currentSrc, top: r.top, left: r.left });
                    pics.push({ id, x: x0, y: y0, w: x1 - x0, h: y1 - y0, fx: r.left - ox, fy: r.top - oy, fw: r.width, fh: r.height });
                }
                if (!pics.length) return;
                asked = { at: performance.now(), imgs, top: vv.pageTop, left: vv.pageLeft, scale: vv.scale };
                bridge.postMessage(JSON.stringify({ type: 'serene-pics', w: W, h: H, pics }));
            };
            const reset = () => {
                for (const img of document.querySelectorAll('img[' + ATTR + ']')) img.removeAttribute(ATTR);
                seen = new WeakMap();
                asked = null;
                off = false;
                later(400);
            };
            bridge.addEventListener('message', (ev) => {
                let msg;
                try { msg = JSON.parse(ev.data); } catch (e) { return; }
                if (msg.type === 'serene-pics-reset') return reset();
                if (msg.type !== 'serene-pics-done' || !asked) return;
                const a = asked;
                asked = null;
                if (msg.off) { off = true; return; }
                if (msg.retry) return later(1000);
                setLevel(msg.level);
                const vv = visualViewport;
                // The page moved since: the answer is about other pixels. Look again.
                if (vv.pageTop !== a.top || vv.pageLeft !== a.left || vv.scale !== a.scale) return later(400);
                for (const [id, p] of a.imgs) {
                    const r = p.img.getBoundingClientRect();
                    // It moved (a carousel): ask again, and leave it be if it never rests.
                    if (Math.abs(r.top - p.top) > 1 || Math.abs(r.left - p.left) > 1) {
                        const n = (tries.get(p.img) || 0) + 1;
                        tries.set(p.img, n);
                        if (n >= 3) seen.set(p.img, p.src);
                        continue;
                    }
                    seen.set(p.img, p.src);
                    if (msg.dim.includes(id)) p.img.setAttribute(ATTR, '');
                }
                later(400);
            });
            for (const t of ['scroll', 'resize']) addEventListener(t, () => later(350), { passive: true });
            visualViewport.addEventListener('resize', () => later(350));
            visualViewport.addEventListener('scroll', () => later(350));
            addEventListener('load', (e) => { if (e.target instanceof HTMLImageElement) later(350); }, true);
            document.addEventListener('visibilitychange', () => later(350));
            dark.addEventListener('change', reset);
            later(500);
        })();"""
    }
}

/** Reading a page's pixels (a bitmap of the WebView) for [BrightPictures]. */
object PictureLight {
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        fun has(x: Int, y: Int) = x in l until r && y in t until b
    }

    /** Light: about the brightness of white paper on a phone (luma 200 of 255 and up). */
    private const val LIGHT = 200
    /** The page around a picture is dark up to this luma: dark themes' greys, not light ones. */
    private const val DARK_PAGE = 80

    /** 0..255, Rec. 709 weights on the sRGB values. */
    fun luma(c: Int): Int = (54 * ((c shr 16) and 0xff) + 183 * ((c shr 8) and 0xff) + 19 * (c and 0xff)) shr 8

    /** Median luma of the screen outside the pictures: what the page is, when a picture has no edge around it. */
    fun pageLuma(px: IntArray, w: Int, h: Int, pictures: List<Box>): Int? {
        val hist = IntArray(256)
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (pictures.any { it.has(x, y) }) continue
            hist[luma(px[y * w + x])]++
            n++
        }
        return median(hist, n)
    }

    /**
     * Whether a picture is a bright spot: the part on screen ([seen]) is mostly light, on a dark
     * page. The page is a [ring] px band around the whole picture ([whole]), or else [page] (all
     * of the screen that isn't a picture). A photo with some sky in it stays as it is.
     */
    fun dims(px: IntArray, w: Int, h: Int, seen: Box, whole: Box, page: Int?, ring: Int): Boolean {
        val s = Box(max(seen.l, 0), max(seen.t, 0), min(seen.r, w), min(seen.b, h))
        if (s.r <= s.l || s.b <= s.t) return false
        var light = 0
        var sum = 0L
        var n = 0
        for (y in s.t until s.b) for (x in s.l until s.r) {
            val l = luma(px[y * w + x])
            if (l >= LIGHT) light++
            sum += l
            n++
        }
        val bright = light >= n * 0.4 || sum >= n * 166L
        if (!bright) return false
        val around = ringLuma(px, w, h, whole, ring) ?: page ?: return false
        return around <= DARK_PAGE
    }

    // Median luma of a band just outside [b], on screen. Null when too little of it is.
    private fun ringLuma(px: IntArray, w: Int, h: Int, b: Box, ring: Int): Int? {
        val hist = IntArray(256)
        var n = 0
        for (y in max(b.t - ring, 0) until min(b.b + ring, h)) for (x in max(b.l - ring, 0) until min(b.r + ring, w)) {
            if (b.has(x, y)) continue
            hist[luma(px[y * w + x])]++
            n++
        }
        return if (n >= 40) median(hist, n) else null
    }

    private fun median(hist: IntArray, n: Int): Int? {
        if (n == 0) return null
        var seen = 0
        hist.forEachIndexed { l, c -> seen += c; if (seen * 2 >= n) return l }
        return null
    }
}
