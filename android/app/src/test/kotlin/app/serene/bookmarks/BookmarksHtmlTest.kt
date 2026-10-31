package app.serene.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarksHtmlTest {
    /** Laid out as Chrome writes it: unclosed <DT> and <p>, the bar as a folder. */
    private val chrome = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <!-- This is an automatically generated file. -->
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1700000000" LAST_MODIFIED="0" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
            <DL><p>
                <DT><A HREF="https://news.example/?a=1&amp;b=2" ADD_DATE="1700000001" ICON="data:image/png;base64,AAA=">News &amp; views</A>
                <DT><H3>Tools &lt;dev&gt;</H3>
                <DL><p>
                    <DT><A HREF="https://tools.example/" ADD_DATE="1700000002">Tools &#8211; &#x263A;</A>
                </DL><p>
            </DL><p>
            <DT><A HREF="https://loose.example/">  Loose
              one </A>
            <DT><A HREF='https://single.example/' add_date=1700000003><b>Bold</b></A>
            <DT><A>no address</A>
        </DL><p>
    """.trimIndent()

    @Test
    fun parsesChromeExport() {
        assertEquals(
            listOf(
                BookmarksHtml.Entry("https://news.example/?a=1&b=2", "News & views", 1_700_000_001_000, listOf("Bookmarks bar")),
                BookmarksHtml.Entry("https://tools.example/", "Tools – ☺", 1_700_000_002_000, listOf("Bookmarks bar", "Tools <dev>")),
                BookmarksHtml.Entry("https://loose.example/", "Loose one", null, emptyList()),
                BookmarksHtml.Entry("https://single.example/", "Bold", 1_700_000_003_000, emptyList()),
            ),
            BookmarksHtml.parse(chrome),
        )
    }

    @Test
    fun addDateInMillisOrMicros() {
        val html = """<DL><DT><A HREF="https://a.example/" ADD_DATE="1700000000123">a</A><DT><A HREF="https://b.example/" ADD_DATE="1700000000123456">b</A></DL>"""
        assertEquals(listOf(1_700_000_000_123L, 1_700_000_000_123L), BookmarksHtml.parse(html).map { it.added })
    }

    @Test
    fun exportReadsBackTheSame() {
        val items = listOf(
            Bookmark(1, "https://a.example/?x=1&y=\"2\"", "A <page> & more", 1_700_000_000_000),
            Bookmark(2, "https://b.example/", "B", 1_700_000_001_000, listOf("Bookmarks bar")),
            Bookmark(3, "https://c.example/", "C", 1_700_000_002_000, listOf("Bookmarks bar", "Deep")),
            Bookmark(4, "https://d.example/", "D", 1_700_000_003_000, listOf("Bookmarks bar")),
        )
        val html = BookmarksHtml.export(items)
        val back = BookmarksHtml.parse(html)
        assertEquals(
            items.sortedBy { it.folder.size }.map { BookmarksHtml.Entry(it.url, it.title, it.added, it.folder) }.toSet(),
            back.toSet(),
        )
        assertEquals(4, back.size)
        assert(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
    }
}
