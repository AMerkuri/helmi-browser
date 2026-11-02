package app.serene.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.serene.MainActivity
import app.serene.browser.HomeShortcutData.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * ⋮ › Add to home screen, as Chrome's: a launcher shortcut to the page, named and drawn from what
 * the site says about itself (HomeShortcutData), that opens the page in Helmi. The icon is an
 * adaptive one, so the launcher gives it the same shape as the others.
 */
object HomeShortcut {
    private const val TAG = "Serene"

    /** On a shortcut's intent: Helmi shows the tab already on its page rather than opening another. */
    const val EXTRA_SHORTCUT = "app.helmi.browser.extra.HOME_SHORTCUT"

    /** The launcher's full adaptive icon is 108 dp, of which the middle 72 dp show. */
    const val ICON_DP = 108
    private const val VISIBLE = 72f / 108
    // A maskable icon's safe zone (the middle 80%) on the adaptive icon's (66 of 108 dp).
    private const val MASKABLE = (66f / 108) / 0.8f
    // A logo on a transparent background: its square inside the safe zone's circle.
    private const val LOGO = 0.44f
    private const val LETTER = 0.30f
    /** Icons smaller than this (dp) are drawn as the site's letter instead, as Chrome does a 16 px favicon. */
    private const val MIN_ICON_DP = 32
    private const val MAX_ICON_BYTES = 2 shl 20
    private const val MAX_MANIFEST_BYTES = 256 shl 10
    private const val MAX_TRIES = 6
    // Past this the letter it is: the dialog shouldn't wait on a slow site.
    private const val DEADLINE_MS = 8_000L
    private val DARK = Color.rgb(0x20, 0x21, 0x24)

    /** Ready to pin: [source] says where the icon came from, null for the letter. */
    class Prepared(val url: String, val name: String, val icon: Bitmap, val source: Source?)

    /**
     * The shortcut for [tab]'s page, from the main thread: the page asked for its manifest, title
     * and icons, then those fetched off it. [density]: the screen's, for the icon's size.
     */
    suspend fun prepare(tab: Tab, density: Float): Prepared {
        val url = tab.url
        val title = tab.title
        val ua = tab.userAgent
        val cookies = tab.cookies
        val page = HomeShortcutData.parsePage(evaluate(tab.webView, HomeShortcutData.PAGE_INFO_JS))
        val size = (ICON_DP * density).roundToInt().coerceIn(ICON_DP, 4 * ICON_DP)
        return withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val manifest = page?.manifestUrl?.takeIf { it.isNotEmpty() }?.let { m ->
                runCatching {
                    val (bytes, _) = PageImages.download(m, ua, url, cookies, MAX_MANIFEST_BYTES, "application/manifest+json,application/json,*/*;q=0.5", 5_000, 5_000)
                    HomeShortcutData.parseManifest(bytes.toString(Charsets.UTF_8), m)
                }.onFailure { Log.w(TAG, "no manifest from ${m.take(100)}: $it") }.getOrNull()
            }
            val name = HomeShortcutData.name(url, title, page, manifest)
            val minPx = (MIN_ICON_DP * density).roundToInt()
            var small: Bitmap? = null
            for (c in HomeShortcutData.candidates(url, page, manifest, (size * VISIBLE).roundToInt()).take(MAX_TRIES)) {
                ensureActive()
                if (SystemClock.elapsedRealtime() - start > DEADLINE_MS) break
                val b = fetchBitmap(c.url, ua, url, cookies, size) ?: continue
                if (minOf(b.width, b.height) >= minPx) return@withContext Prepared(url, name, draw(b, c.maskable, size), c.source)
                if (small == null || b.width > small.width) small = b
            }
            val themed = listOf(manifest?.themeColor, page?.themeColor).firstNotNullOfOrNull { css ->
                css?.let(HomeShortcutData::parseColor)?.takeIf { Color.alpha(it) >= 128 }?.let { it or 0xFF000000.toInt() }
            }
            val color = themed ?: small?.let(::dominantColor) ?: HomeShortcutData.fallbackColor(url)
            Prepared(url, name, letter(HomeShortcutData.monogram(url), color, size), null)
        }
    }

    /** Whether this phone's launcher takes pinned shortcuts. */
    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to pin the shortcut; it confirms with the user itself. False when it
     * can't take one.
     */
    fun pin(context: Context, url: String, name: String, icon: Bitmap): Boolean {
        if (!supported(context)) return false
        val label = name.trim().ifEmpty { HomeShortcutData.site(url) }
        val intent = intent(context, url)
        val info = ShortcutInfoCompat.Builder(context, "page-${UUID.randomUUID()}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
            .onFailure { Log.w(TAG, "couldn't pin a shortcut: $it") }
            .getOrDefault(false)
    }

    /** What the shortcut starts: the page, in Helmi. */
    fun intent(context: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(context, MainActivity::class.java).putExtra(EXTRA_SHORTCUT, true)

    private suspend fun evaluate(web: WebView, js: String): String? = suspendCancellableCoroutine { k ->
        runCatching { web.evaluateJavascript(js) { if (k.isActive) k.resume(it) } }.onFailure { if (k.isActive) k.resume(null) }
    }

    private fun fetchBitmap(src: String, ua: String, referer: String, cookies: CookieManager, size: Int): Bitmap? = try {
        val bytes = if (src.startsWith("data:", ignoreCase = true)) PageImages.decodeData(src).first
        else PageImages.download(src, ua, referer, cookies, MAX_ICON_BYTES, "image/webp,image/png,image/*,*/*;q=0.8", 5_000, 5_000).first
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        Log.w(TAG, "no icon from ${src.take(100)}: $e")
        null
    }

    /**
     * The site's icon on a [size] px adaptive icon. A maskable icon's safe zone on the launcher's;
     * a square tile (an apple-touch-icon: opaque corners) fills the shown part; a logo on a
     * transparent background goes small on white (dark for a white logo), as Chrome draws them.
     */
    fun draw(icon: Bitmap, maskable: Boolean, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val share = when {
            maskable -> { canvas.drawColor(edgeColor(icon)); MASKABLE }
            opaqueCorners(icon) -> { canvas.drawColor(edgeColor(icon)); VISIBLE }
            else -> { canvas.drawColor(if (lightLogo(icon)) DARK else Color.WHITE); LOGO }
        }
        // Its longer side on the share, keeping its shape.
        val side = size * share
        val scale = side / maxOf(icon.width, icon.height)
        val w = icon.width * scale
        val h = icon.height * scale
        val dst = RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        canvas.drawBitmap(icon, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** The site's letter on [color], in white or (on a light colour) near-black. */
    fun letter(letter: String, color: Int, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (HomeShortcutData.luminance(color) > 0.5) DARK else Color.WHITE
            textSize = size * LETTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val m = paint.fontMetrics
        canvas.drawText(letter, size / 2f, size / 2f - (m.ascent + m.descent) / 2, paint)
        return out
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val dx = (b.width * 0.04f).toInt()
        val dy = (b.height * 0.04f).toInt()
        val points = listOf(dx to dy, b.width - 1 - dx to dy, dx to b.height - 1 - dy, b.width - 1 - dx to b.height - 1 - dy)
        return points.all { (x, y) -> Color.alpha(b.getPixel(x, y)) >= 250 }
    }

    /** The average of the icon's outermost pixels: what its background is. */
    private fun edgeColor(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        fun add(c: Int) {
            if (Color.alpha(c) < 128) return
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        for (x in 0 until b.width) { add(b.getPixel(x, 0)); add(b.getPixel(x, b.height - 1)) }
        for (y in 0 until b.height) { add(b.getPixel(0, y)); add(b.getPixel(b.width - 1, y)) }
        return if (n == 0L) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun lightLogo(b: Bitmap): Boolean {
        val seen = pixels(b).filter { Color.alpha(it) >= 128 }
        return seen.isNotEmpty() && seen.sumOf { HomeShortcutData.luminance(it) } / seen.size > 0.85
    }

    /** A small favicon's colour, for the letter drawn instead: its average, leaving out white and see-through. */
    private fun dominantColor(b: Bitmap): Int? {
        val seen = pixels(b).filter { Color.alpha(it) >= 128 && HomeShortcutData.luminance(it) < 0.85 }
        if (seen.isEmpty()) return null
        return Color.rgb(seen.sumOf { Color.red(it) } / seen.size, seen.sumOf { Color.green(it) } / seen.size, seen.sumOf { Color.blue(it) } / seen.size)
    }
}
