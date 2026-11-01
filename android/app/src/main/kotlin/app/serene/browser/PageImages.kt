package app.serene.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder
import java.util.Base64

/** A picture from a page, fetched into the cache for the context menu to copy or share. */
class PageImage(val file: File, val mimeType: String)

/**
 * Pictures from pages, for the context menu. WebView has no API to hand out a picture it has
 * drawn, so Serene fetches it again, as the page did: the WebView's cookies for each hop, its user
 * agent, the page as referrer. A data: picture is decoded in place.
 */
object PageImages {
    private const val TAG = "Serene"
    private const val MAX_BYTES = 25 shl 20
    private const val MAX_REDIRECTS = 5
    // Not AVIF: few apps take one pasted or shared.
    private const val ACCEPT = "image/webp,image/apng,image/*,*/*;q=0.8"

    /**
     * Off the main thread. Null when the picture can't be had, or isn't one. [cookies]: the tab's
     * (a private tab has its own), or null for WebView's normal ones.
     */
    fun fetch(context: Context, src: String, userAgent: String, referer: String?, cookies: CookieManager? = null): PageImage? = try {
        val (bytes, type) = if (src.startsWith("data:", ignoreCase = true)) decodeData(src) else download(src, userAgent, referer, cookies ?: CookieManager.getInstance())
        // A server that doesn't say (or says octet-stream): the bytes, then the address, tell.
        val mime = type.takeIf { it.startsWith("image/") }
            ?: URLConnection.guessContentTypeFromStream(ByteArrayInputStream(bytes))
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(src).lowercase())
        if (mime == null || !mime.startsWith("image/")) {
            Log.w(TAG, "not a picture ($type): ${src.take(100)}")
            null
        } else {
            val dir = File(context.cacheDir, "images").apply { mkdirs() }
            // The latest few only: a share may still be reading the one before.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            val file = File(dir, "image-${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)
            PageImage(file, mime)
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't fetch the picture ${src.take(100)}: $e")
        null
    }

    /** A small version for the top of the menu; null for what Android can't decode (SVG). */
    fun thumbnail(image: PageImage, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(image.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A data: URL's bytes and media type. */
    fun decodeData(url: String): Pair<ByteArray, String> {
        val comma = url.indexOf(',')
        if (comma < 0) throw IOException("not a data: URL")
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val type = meta.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        val bytes = if (meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }) {
            Base64.getMimeDecoder().decode(URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8"))
        } else {
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        }
        return bytes to type
    }

    /** Follows redirects itself, so each hop gets only its own cookies (as Downloads does). */
    private fun download(src: String, userAgent: String, referer: String?, cookies: CookieManager): Pair<ByteArray, String> {
        var url = URL(src)
        repeat(MAX_REDIRECTS) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", ACCEPT)
                referer?.let { setRequestProperty("Referer", it) }
                runCatching { cookies.getCookie(url.toString()) }.getOrNull()?.let { setRequestProperty("Cookie", it) }
            }
            try {
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    url = URL(url, location)
                    if (url.protocol != "https" && url.protocol != "http") throw IOException("redirected to a ${url.protocol}: link")
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("picture over ${MAX_BYTES shr 20} MB")
                    }
                }
                return out.toByteArray() to c.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
