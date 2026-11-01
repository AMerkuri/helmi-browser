package app.serene.browser

import app.serene.bookmarks.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun visit(url: String, title: String = "", daysAgo: Int = 0, count: Int = 1) = Visit(url, title, now - daysAgo * day, count)
    private fun bookmark(url: String, title: String, id: Long = 1) = Bookmark(id, url, title, now - 30 * day)

    @Test
    fun anAddressThatStartsWithTheTypingComesFirst() {
        val visits = listOf(
            visit("https://news.example/github-story", "All about GitHub"),
            visit("https://github.com/AMerkuri/helmi-browser", "helmi-browser", daysAgo = 3),
        )
        val pages = Suggestions.pages("git", visits, emptyList(), now)
        assertEquals(listOf("https://github.com/AMerkuri/helmi-browser", "https://news.example/github-story"), pages.map { it.url })
    }

    @Test
    fun aPageIsOnceWithItsVisitsAddedUpAndItsBookmarkName() {
        val visits = listOf(
            visit("https://a.example/page", "Page", count = 2),
            visit("https://a.example/page", "Page", daysAgo = 1, count = 3),
        )
        val pages = Suggestions.pages("page", visits, listOf(bookmark("https://a.example/page/", "My page")), now)
        assertEquals(1, pages.size)
        assertEquals(5, pages[0].visits)
        assertTrue(pages[0].bookmarked)
        assertEquals("My page", pages[0].title)
    }

    @Test
    fun everyWordMustMatchAndBookmarksAloneAreSuggested() {
        val visits = listOf(visit("https://a.example/", "Weather in Tallinn"), visit("https://b.example/", "Weather in Tartu"))
        val bookmarks = listOf(bookmark("https://c.example/", "Tallinn bus times"))
        assertEquals(listOf("https://a.example/"), Suggestions.pages("weather tallinn", visits, bookmarks, now).map { it.url })
        assertEquals(setOf("https://a.example/", "https://c.example/"), Suggestions.pages("tallinn", visits, bookmarks, now).map { it.url }.toSet())
        assertEquals(emptyList<PageSuggestion>(), Suggestions.pages("  ", visits, bookmarks, now))
    }

    @Test
    fun frequentPagesAndBookmarksRankAboveAOneOffVisit() {
        val visits = listOf(
            visit("https://once.example/recipes", "Recipes once"),
            visit("https://often.example/recipes", "Recipes often", daysAgo = 2, count = 20),
        )
        val bookmarks = listOf(bookmark("https://saved.example/recipes", "Recipes saved"))
        assertEquals(
            listOf("https://often.example/recipes", "https://saved.example/recipes", "https://once.example/recipes"),
            Suggestions.pages("recipes", visits, bookmarks, now).map { it.url },
        )
    }

    @Test
    fun completesToTheSiteThenThePath() {
        val pages = Suggestions.pages(
            "git",
            listOf(visit("https://github.com/AMerkuri/helmi-browser", "helmi", count = 2), visit("https://www.github.com/explore", "Explore")),
            emptyList(), now,
        )
        assertEquals(Completion("github.com", "https://github.com"), Suggestions.completion("git", pages))
        // What was typed stays as typed; the rest comes from the address.
        assertEquals("GitHub.com", Suggestions.completion("GitH", pages)?.text)
        assertEquals("https://github.com", Suggestions.completion("https://git", pages)?.text)
        assertEquals("www.github.com", Suggestions.completion("www.git", pages)?.text)
        val deeper = Suggestions.pages("github.com/am", listOf(visit("https://github.com/AMerkuri/helmi-browser", count = 3)), emptyList(), now)
        assertEquals("github.com/amerkuri/helmi-browser", Suggestions.completion("github.com/am", deeper)?.text)
        // The rest of a path keeps its case.
        assertEquals(Completion("github.com/AMerkuri/helmi-browser", "https://github.com/AMerkuri/helmi-browser"), Suggestions.completion("github.com/AM", deeper))
        assertEquals("github.com/aMerkuri/helmi-browser", Suggestions.completion("github.com/a", deeper)?.text)
    }

    @Test
    fun noCompletionForAOneOffVisitASpaceOrTheWholeSite() {
        val once = Suggestions.pages("exa", listOf(visit("https://example.com/a")), emptyList(), now)
        assertNull(Suggestions.completion("exa", once)?.text) // visited once: could be anything
        val saved = listOf(PageSuggestion("https://example.com/a", "", bookmarked = true, visits = 0, lastAt = now))
        assertEquals("example.com", Suggestions.completion("exa", saved)?.text) // a bookmark is on purpose
        assertNull(Suggestions.completion("example.com", saved)?.text) // nothing to add
        assertNull(Suggestions.completion("exa mple", saved)?.text)
        assertNull(Suggestions.completion("", saved)?.text)
        assertNull(Suggestions.completion("ww", saved)?.text) // every site starts with www
    }

    @Test
    fun anHttpOnlySiteStaysHttp() {
        val pages = Suggestions.pages("rout", listOf(visit("http://www.router.example/admin", count = 4)), emptyList(), now)
        assertEquals(Completion("router.example", "http://www.router.example"), Suggestions.completion("rout", pages))
        val local = Suggestions.pages("192", listOf(visit("http://192.168.1.1/", count = 2)), emptyList(), now)
        assertEquals(Completion("192.168.1.1", "http://192.168.1.1"), Suggestions.completion("192", local))
    }

    @Test
    fun theSiteVisitedMostWins() {
        val pages = listOf(
            PageSuggestion("https://example.com/", "", false, 3, now),
            PageSuggestion("https://example.org/a", "", false, 2, now),
            PageSuggestion("https://example.org/b", "", false, 2, now),
        )
        assertEquals("example.org", Suggestions.completion("ex", pages)?.text)
    }

    @Test
    fun readsOpenSearchReplies() {
        val body = """["helmi",["helmi","Helmi browser","helminth",42,"helmi browser"],[],{"google:suggesttype":[]}]"""
        assertEquals(listOf("Helmi browser", "helminth"), Suggestions.parseSearchSuggestions(body, "helmi"))
        assertEquals(emptyList<String>(), Suggestions.parseSearchSuggestions("", "helmi"))
        assertEquals(emptyList<String>(), Suggestions.parseSearchSuggestions("<html>429</html>", "helmi"))
        assertEquals(emptyList<String>(), Suggestions.parseSearchSuggestions("""{"q":"x"}""", "x"))
        assertEquals(listOf("a", "b"), Suggestions.parseSearchSuggestions("""["q",["a","b","c"]]""", "q", limit = 2))
    }
}
