package app.serene.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.PixelCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * A picture of each tab's page as it was last on screen, for the tab switcher's cards. Taken
 * with PixelCopy (draw() of a WebView is blank on phones) when the switcher opens and when the
 * user leaves a tab, at half the screen's resolution each way, with a fixed budget across tabs.
 * Kept on disk over a restart, by URL, so restored tabs have their pictures too.
 */
object TabPreviews {
    private const val BUDGET = 64 * 1024 * 1024
    private const val SCALE = 0.5f
    private const val DIR = "tab-previews"

    private val cache = object : LruCache<Long, Bitmap>(BUDGET) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    // Taken since the last save, so only those are written again.
    private val unsaved = mutableSetOf<Long>()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped when a picture changes, so the switcher redraws. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Something is drawn over the page (the switcher): a capture now would take that instead,
     * so captures are skipped and the pictures kept.
     */
    var covered = false

    fun get(tab: Tab): Bitmap? = cache.get(tab.id)

    fun remove(tab: Tab) {
        if (cache.remove(tab.id) != null) version++
        unsaved -= tab.id
    }

    /**
     * Takes [tab]'s picture as it is on screen now; [done] runs on the main thread when it is
     * taken, or straight away when there is nothing to take. A tab on the start or download page
     * has no picture: its card shows its title instead.
     */
    fun capture(tab: Tab, done: () -> Unit = {}) {
        if (tab.url.isEmpty() || tab.url == "about:blank" || tab.downloadPage || !tab.hasPage) {
            remove(tab)
            return done()
        }
        val v = tab.webView
        val window = activityOf(v.context)?.window
        if (covered || window == null || !v.isShown || v.width == 0 || v.height == 0) return done()
        var finished = false
        val once = { if (!finished) { finished = true; done() } }
        val xy = IntArray(2).also { v.getLocationInWindow(it) }
        val bmp = Bitmap.createBitmap((v.width * SCALE).toInt().coerceAtLeast(1), (v.height * SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height), bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    cache.put(tab.id, bmp)
                    unsaved += tab.id
                    version++
                }
                once()
            }, main)
        } catch (e: IllegalArgumentException) { // window not attached to a surface yet
            return once()
        }
        // PixelCopy answers within a frame or two; don't hold the switcher if it doesn't.
        main.postDelayed(once, 300)
    }

    /** Writes the pictures of [tabs] that changed, and deletes those of tabs gone. Off the main thread. */
    fun save(context: Context, tabs: List<Tab>) {
        val dir = File(context.cacheDir, DIR)
        val keep = tabs.mapNotNull { t -> cache.get(t.id)?.let { fileName(t.url) to (t to it) } }.toMap()
        val write = keep.filter { (name, v) -> v.first.id in unsaved || !File(dir, name).exists() }
        unsaved.clear()
        thread(name = "tab-previews") {
            dir.mkdirs()
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            for ((name, v) in write) runCatching {
                val tmp = File(dir, "$name.tmp")
                tmp.outputStream().use { v.second.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                tmp.renameTo(File(dir, name))
            }
        }
    }

    /** Reads the saved pictures of restored [tabs], by URL. */
    fun restore(context: Context, tabs: List<Tab>) {
        val dir = File(context.cacheDir, DIR)
        val wanted = tabs.map { it to File(dir, fileName(it.url)) }
        thread(name = "tab-previews") {
            val read = wanted.mapNotNull { (t, f) -> if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { t to it } else null }
            main.post {
                // A tab that has been on screen since has a newer picture.
                for ((t, b) in read) if (cache.get(t.id) == null) cache.put(t.id, b)
                if (read.isNotEmpty()) version++
            }
        }
    }

    private fun fileName(url: String) = "%08x.jpg".format(url.hashCode())

    private tailrec fun activityOf(c: Context?): Activity? = when (c) {
        is Activity -> c
        is ContextWrapper -> activityOf(c.baseContext)
        else -> null
    }
}
