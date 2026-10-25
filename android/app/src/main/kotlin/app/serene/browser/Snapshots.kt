package app.serene.browser

import android.graphics.Bitmap
import android.util.LruCache

/** A picture of a page as it was last on screen, for swiping back or forward to it. */
class Snapshot(val url: String, val bitmap: Bitmap)

/**
 * Snapshots of the pages in each tab's history, by history index. Half the screen's resolution,
 * which is enough for the moment of a swipe, and a fixed budget across all tabs: the least
 * recently used go first.
 */
object Snapshots {
    private const val BUDGET = 48 * 1024 * 1024

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
}
