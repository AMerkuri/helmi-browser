package app.serene.bookmarks

/**
 * The Netscape bookmarks file (bookmarks.html) that Chrome, Firefox, Safari, Edge and Samsung
 * Internet all export and import: `<DT><A HREF=… ADD_DATE=…>title</A>` lines in nested `<DL>`
 * lists, each folder a `<DT><H3>name</H3>` before its `<DL>`.
 */
object BookmarksHtml {
    /** A bookmark in a file: [added] in ms when the file says, [folder] outermost first. */
    data class Entry(val url: String, val title: String, val added: Long? = null, val folder: List<String> = emptyList())

    private val token = Regex("""<h3\b[^>]*>(.*?)</h3\s*>|<dl\b[^>]*>|</dl\s*>|<a\s([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attr = Regex("""([a-z_][\w-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>")

    /** The bookmarks in [html], in file order. Anything that isn't a bookmark is ignored. */
    fun parse(html: String): List<Entry> {
        val out = ArrayList<Entry>()
        // One element per open <DL>: its folder's name, or null for a list that isn't a folder's.
        val folders = ArrayList<String?>()
        var pending: String? = null
        for (m in token.findAll(html)) {
            val t = m.value
            when {
                t.startsWith("<h3", true) -> pending = text(m.groupValues[1])
                t.startsWith("</dl", true) -> { if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex) }
                t.startsWith("<dl", true) -> { folders += pending; pending = null }
                else -> {
                    val attrs = attr.findAll(m.groupValues[2]).associate { a ->
                        a.groupValues[1].lowercase() to decode(a.groupValues[2].ifEmpty { a.groupValues[3] }.ifEmpty { a.groupValues[4] })
                    }
                    val url = attrs["href"]?.trim().orEmpty()
                    if (url.isEmpty()) continue
                    out += Entry(url, text(m.groupValues[3]), attrs["add_date"]?.let(::toMillis), folders.filterNotNull())
                }
            }
        }
        return out
    }

    /** [items] as a bookmarks file, in folders by their [Bookmark.folder]. */
    fun export(items: List<Bookmark>): String {
        val root = Folder("")
        for (b in items) {
            var f = root
            for (name in b.folder) f = f.children.getOrPut(name) { Folder(name) }
            f.bookmarks += b
        }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n")
        writeList(sb, root, 0)
        return sb.toString()
    }

    private class Folder(val name: String) {
        val children = LinkedHashMap<String, Folder>()
        val bookmarks = ArrayList<Bookmark>()
    }

    private fun writeList(sb: StringBuilder, f: Folder, depth: Int) {
        val pad = "    ".repeat(depth)
        sb.append(pad).append("<DL><p>\n")
        for (c in f.children.values) {
            sb.append(pad).append("    <DT><H3>").append(escape(c.name)).append("</H3>\n")
            writeList(sb, c, depth + 1)
        }
        for (b in f.bookmarks) {
            sb.append(pad).append("    <DT><A HREF=\"").append(escape(b.url)).append("\" ADD_DATE=\"").append(b.added / 1000).append("\">")
                .append(escape(b.title)).append("</A>\n")
        }
        sb.append(pad).append("</DL><p>\n")
    }

    /** ADD_DATE is in seconds; some exporters wrote ms or µs. */
    private fun toMillis(s: String): Long? {
        val n = s.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        return when {
            n > 100_000_000_000_000 -> n / 1000
            n > 100_000_000_000 -> n
            else -> n * 1000
        }
    }

    private fun text(html: String) = decode(html.replace(tag, "")).replace(Regex("\\s+"), " ").trim()

    private val entity = Regex("&(#x[0-9a-f]+|#[0-9]+|[a-z]+);", RegexOption.IGNORE_CASE)
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    internal fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        val code = when {
            e.startsWith("#x", true) -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null && Character.isValidCodePoint(code) -> String(Character.toChars(code))
            code == null -> named[e.lowercase()] ?: m.value
            else -> m.value
        }
    }

    internal fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
