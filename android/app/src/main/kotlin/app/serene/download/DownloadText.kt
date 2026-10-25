package app.serene.download

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.ceil

/** File names and progress lines for downloads. Plain Kotlin, so unit tests cover them. */
object DownloadText {
    /**
     * The file name a server asks for in Content-Disposition: `filename*=UTF-8''…` (RFC 6266)
     * wins over `filename="…"`. Null when there is none.
     */
    fun fromContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;\s]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].replace("+", "%2B"), charset) }.getOrNull()
                ?.let(::clean)?.let { return it }
        }
        Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            clean(m.groupValues[1].replace(Regex("""\\(.)"""), "$1"))?.let { return it }
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            clean(m.groupValues[1].trim())?.let { return it }
        }
        return null
    }

    /**
     * Content-Disposition, else the last path segment of the URL, else "download"; with an
     * extension from the MIME type when the name has none. [extFor] maps a MIME type to an
     * extension (MimeTypeMap in the app).
     */
    fun fileName(url: String, contentDisposition: String?, mimeType: String?, extFor: (String) -> String?): String {
        val fromUrl = if (url.startsWith("http", ignoreCase = true)) runCatching {
            URI(url).rawPath?.substringAfterLast('/')?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        }.getOrNull()?.let(::clean) else null
        val name = fromContentDisposition(contentDisposition) ?: fromUrl ?: "download"
        if (name.substringAfterLast('.', "").isNotEmpty() && name.lastIndexOf('.') > 0) return name
        val ext = mimeType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }?.let(extFor)
        return if (ext.isNullOrEmpty()) name else "$name.$ext"
    }

    /** No folders, no control characters, no leading dots; at most 200 characters. */
    fun clean(name: String): String? {
        val s = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trim().trimStart('.').trim()
        if (s.isEmpty()) return null
        if (s.length <= 200) return s
        val ext = s.substringAfterLast('.', "").take(16)
        return if (ext.isEmpty()) s.take(200) else s.take(200 - ext.length - 1) + "." + ext
    }

    /** "20.39 MB", like the system's file sizes (1000-based since Android 8). */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1000) return "$bytes B"
        val units = listOf("kB", "MB", "GB", "TB")
        var v = bytes / 1000.0
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return String.format(locale, if (v < 100) "%.2f %s" else "%.1f %s", v, units[i])
    }

    /** "4 secs left", "1 min left", "2 hours left". */
    fun timeLeft(seconds: Long): String {
        val s = seconds.coerceAtLeast(1)
        fun n(v: Long, one: String, many: String) = if (v == 1L) "1 $one left" else "$v $many left"
        return when {
            s < 60 -> n(s, "sec", "secs")
            s < 3600 -> n((s + 30) / 60, "min", "mins")
            s < 86_400 -> n((s + 1800) / 3600, "hour", "hours")
            else -> n((s + 43_200) / 86_400, "day", "days")
        }
    }

    /** The line under the file name: "20.39 MB / 39.58 MB • 4 secs left". */
    fun progress(received: Long, total: Long, bytesPerSec: Double, locale: Locale = Locale.getDefault()): String {
        val done = size(received, locale)
        if (total <= 0) return done
        val left = if (bytesPerSec > 0 && total > received) " • " + timeLeft(ceil((total - received) / bytesPerSec).toLong()) else ""
        return "$done / ${size(total, locale)}$left"
    }
}
