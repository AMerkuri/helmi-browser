package app.serene.reader

/** A reader colour scheme: page, text, secondary text and the accent for links and rules. */
enum class ReaderTheme(val label: String, val background: Long, val text: Long, val muted: Long, val accent: Long, val dark: Boolean) {
    PAPER("Paper", 0xFFFBFAF6, 0xFF1F1E1B, 0xFF6B6860, 0xFF7A5C2E, false),
    SEPIA("Sepia", 0xFFF3E9D2, 0xFF4A3B2A, 0xFF7D6A55, 0xFF8A5A1E, false),
    NIGHT("Night", 0xFF141414, 0xFFCFCBC2, 0xFF8E8A82, 0xFFC9A46A, true),
}

data class ReaderStyle(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    /** CSS px; the reader page has a device-width viewport, so 1 px is 1 dp. */
    val textSize: Int = 19,
    val serif: Boolean = true,
    /** Justified and hyphenated, as in a book; off for ragged-right. */
    val justify: Boolean = true,
)

/**
 * The reader view's document: the extracted article in book typography. It is loaded into a
 * WebView with JavaScript off, and the Content-Security-Policy stops scripts, frames, plugins, forms and
 * any request other than images and media, whatever the page's HTML holds.
 */
object ReaderHtml {
    const val CSP = "default-src 'none'; img-src * data: blob:; media-src *; style-src 'unsafe-inline'; font-src data:; form-action 'none'; base-uri 'none'"

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun css(c: Long) = "#%06X".format(c and 0xFFFFFF)

    // A language tag as it may appear in lang=""; anything else is dropped.
    private fun langTag(s: String) = s.takeIf { Regex("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$").matches(it) } ?: ""

    fun build(page: ReaderPage, style: ReaderStyle): String {
        val t = style.theme
        val font = if (style.serif) "'Noto Serif', Georgia, 'Times New Roman', serif" else "Roboto, 'Noto Sans', system-ui, sans-serif"
        val lang = langTag(page.lang)
        val dir = page.dir.takeIf { it == "rtl" || it == "ltr" } ?: "auto"
        val meta = listOf(page.siteName, page.byline).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(" · ") + (if (page.siteName.isNotBlank() || page.byline.isNotBlank()) " · " else "") + "${page.minutes} min read"
        return """
            <!doctype html>
            <html${if (lang.isNotEmpty()) " lang=\"$lang\"" else ""} dir="$dir">
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$CSP">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="${if (t.dark) "dark" else "light"}">
            <style>
            html { -webkit-text-size-adjust: 100%; background: ${css(t.background)}; }
            body { margin: 0; background: ${css(t.background)}; color: ${css(t.text)}; font-family: $font; font-size: ${style.textSize}px; line-height: 1.6; overflow-wrap: break-word; }
            main { max-width: 34em; margin: 0 auto; padding: 20px 22px 120px; }
            .content { text-align: ${if (style.justify) "justify" else "start"}; hyphens: ${if (style.justify) "auto" else "manual"}; -webkit-hyphens: ${if (style.justify) "auto" else "manual"}; }
            header { margin-bottom: 1.6em; padding-bottom: 1.2em; border-bottom: 1px solid ${css(t.muted)}40; }
            h1.title { font-size: 1.55em; line-height: 1.25; margin: 0 0 .5em; text-align: start; }
            .meta { color: ${css(t.muted)}; font-size: .8em; font-family: Roboto, system-ui, sans-serif; }
            h1, h2, h3, h4, h5, h6 { line-height: 1.3; text-align: start; hyphens: manual; }
            .content h1 { font-size: 1.3em; } .content h2 { font-size: 1.2em; margin-top: 1.6em; } .content h3 { font-size: 1.08em; }
            p { margin: 0 0 1em; }
            a { color: inherit; text-decoration-color: ${css(t.accent)}; text-underline-offset: 2px; }
            img, video, picture { max-width: 100%; height: auto; display: block; margin: 1.2em auto; border-radius: 4px; }
            ${if (t.dark) "img, video { filter: brightness(.85); }" else ""}
            figure { margin: 1.2em 0; } figcaption { color: ${css(t.muted)}; font-size: .8em; line-height: 1.4; text-align: start; }
            blockquote { margin: 1.2em 0; padding: 0 0 0 1em; border-left: 3px solid ${css(t.accent)}; color: ${css(t.muted)}; }
            pre, code { font-family: 'Roboto Mono', monospace; font-size: .85em; }
            pre { white-space: pre-wrap; padding: .8em; background: ${css(t.muted)}1A; border-radius: 4px; text-align: start; }
            table { display: block; overflow-x: auto; border-collapse: collapse; font-size: .9em; text-align: start; }
            td, th { border: 1px solid ${css(t.muted)}40; padding: .3em .5em; }
            hr { border: 0; border-top: 1px solid ${css(t.muted)}40; margin: 2em 0; }
            ul, ol { padding-left: 1.4em; }
            </style>
            <title>${escape(page.title)}</title>
            </head>
            <body>
            <main>
            <header>
            <h1 class="title">${escape(page.title)}</h1>
            <div class="meta">${escape(meta)}</div>
            </header>
            <article class="content">
            ${page.html}
            </article>
            </main>
            </body>
            </html>
        """.trimIndent()
    }
}
