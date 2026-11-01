package app.serene.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookmarksTest {
    private fun file() = File(Files.createTempDirectory("bookmarks").toFile(), "bookmarks.json")

    @Test
    fun savedOnceAndReadBack() {
        val f = file()
        val b = Bookmarks(f)
        val a = b.add("https://a.example/", "A page", now = 1000)
        assertEquals(a, b.add("https://a.example", "Again", now = 2000)) // a trailing slash is the same page
        b.add("https://b.example/x", "  ", now = 3000)
        assertEquals(listOf("https://b.example/x", "https://a.example/"), b.items.map { it.url })
        assertEquals("https://b.example/x", b.items[0].title) // no title: the address
        assertTrue(b.isSaved("https://a.example"))
        Bookmarks.flush()
        assertEquals(b.items, Bookmarks(f).items)
    }

    @Test
    fun editRemoveRestore() {
        val b = Bookmarks(null)
        val a = b.add("https://a.example/", "A", now = 1)
        val c = b.add("https://c.example/", "C", now = 2)
        b.edit(a.id, " Renamed ", "https://a2.example/")
        assertEquals("Renamed", b.find("https://a2.example/")?.title)
        assertNull(b.find("https://a.example/"))
        b.edit(a.id, "", "https://a2.example/")
        assertEquals("https://a2.example/", b.find("https://a2.example/")?.title)
        b.remove(c.id)
        assertFalse(b.isSaved(c.url))
        b.restore(c, 0)
        assertEquals(listOf(c.id, a.id), b.items.map { it.id })
    }

    @Test
    fun unreadableFileIsNoBookmarks() {
        val f = file()
        f.writeText("{not json")
        assertEquals(emptyList<Bookmark>(), Bookmarks(f).items)
    }

    @Test
    fun importSkipsSavedDuplicatesAndNonWebAddresses() {
        val b = Bookmarks(null)
        b.add("https://a.example/", "A", now = 1)
        val r = b.import(
            listOf(
                BookmarksHtml.Entry("https://a.example", "A again"),
                BookmarksHtml.Entry("https://n.example/", "New", added = 5000, folder = listOf("Bar")),
                BookmarksHtml.Entry("https://n.example/", "New twice"),
                BookmarksHtml.Entry("javascript:alert(1)", "Bookmarklet"),
                BookmarksHtml.Entry("place:sort=8", "Firefox query"),
            ),
            now = 9000,
        )
        assertEquals(ImportResult(added = 1, skipped = 4), r)
        val n = b.find("https://n.example/")!!
        assertEquals(listOf("Bar"), n.folder)
        assertEquals(5000L, n.added)
        assertEquals(2, b.items.map { it.id }.toSet().size)
    }

    @Test
    fun searchRanksTypedAddressThenWordStartsThenAnyMatch() {
        val items = listOf(
            Bookmark(1, "https://news.example/", "Daily news", 1),
            Bookmark(2, "https://www.example.org/renewals", "Renewsletter", 2),
            Bookmark(3, "https://other.example/", "Weekly news digest", 3),
            Bookmark(4, "https://unrelated.example/", "Recipes", 4),
        )
        assertEquals(listOf(1L, 3L, 2L), Bookmarks.rank(items, "news").map { it.id })
        // What is typed into the address bar: no scheme, no www.
        assertEquals(listOf(2L), Bookmarks.rank(items, "example.org/re").map { it.id })
        assertEquals(listOf(3L), Bookmarks.rank(items, "Weekly NEWS").map { it.id })
        assertEquals(listOf(3L), Bookmarks.rank(items, "digest other").map { it.id })
        assertEquals(listOf(4L, 3L), Bookmarks.rank(items, "  ", limit = 2).map { it.id })
        assertEquals(listOf(1L), Bookmarks.rank(items, "news", limit = 1).map { it.id })
        assertEquals(emptyList<Long>(), Bookmarks.rank(items, "nothing").map { it.id })
    }
}
