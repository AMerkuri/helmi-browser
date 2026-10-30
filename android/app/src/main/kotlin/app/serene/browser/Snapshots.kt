package app.serene.browser

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.abs

/** A picture of a page as it was last on screen, for swiping back or forward to it. */
class Snapshot(val url: String, val bitmap: Bitmap)

/**
 * Snapshots of the pages in each tab's history, by history index. Full resolution, so the picture
 * is as sharp as the page that replaces it, and a fixed budget across all tabs (about eight
 * phone screens): the least recently used go first.
 */
object Snapshots {
    private const val BUDGET = 96 * 1024 * 1024

    private val cache = object : LruCache<String, Snapshot>(BUDGET) {
        override fun sizeOf(key: String, value: Snapshot) = value.bitmap.allocationByteCount
    }

    private fun key(tabId: Long, index: Int) = "$tabId/$index"

    fun put(tabId: Long, index: Int, snapshot: Snapshot) {
        cache.put(key(tabId, index), snapshot)
    }

    /** The snapshot at [index], if it is still of [url]: history after a new navigation is different pages. */
    fun get(tabId: Long, index: Int, url: String): Snapshot? = cache.get(key(tabId, index))?.takeIf { it.url == url }

    fun clear(tabId: Long) {
        cache.snapshot().keys.filter { it.startsWith("$tabId/") }.forEach { cache.remove(it) }
    }

    /**
     * The picture is one flat colour: a page that drew nothing but its background (WebView's
     * back/forward cache can leave a page like that). Such a picture is no picture of the page.
     * A grid of points, leaving out the right edge, where the scrollbar is.
     */
    fun isFlat(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return false
        val xs = (0 until GRID).map { (w * (0.02f + 0.88f * it / (GRID - 1))).toInt() }
        val ys = (0 until GRID).map { (h * (0.02f + 0.96f * it / (GRID - 1))).toInt() }
        return oneColour(IntArray(GRID * GRID) { bitmap.getPixel(xs[it % GRID], ys[it / GRID]) })
    }

    private const val GRID = 12
}

/** All [pixels] (ARGB) are the same colour, give or take dithering. */
fun oneColour(pixels: IntArray): Boolean {
    if (pixels.isEmpty()) return false
    val first = pixels[0]
    return pixels.all { p -> (0..16 step 8).all { s -> abs((p shr s and 0xff) - (first shr s and 0xff)) <= 4 } }
}
