package app.serene.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.UTC
    private var now = at(2026, 10, 31, 10, 0)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun history() = History(null, TestScope(UnconfinedTestDispatcher()), { now }, { zone })

    @Test
    fun aPageShowsOnceADayNewestFirst() {
        val h = history()
        h.visit("https://a.example/")
        now += 60_000
        h.visit("https://b.example/")
        now += 60_000
        h.visit("https://a.example/") // again today: moves to the top
        assertEquals(listOf("https://a.example/", "https://b.example/"), h.entries.map { it.url })
        assertEquals(2, h.entries[0].count)
        assertEquals(now, h.entries[0].at)
        // The next day it is a new entry.
        now = at(2026, 11, 1, 9, 0)
        h.visit("https://a.example/")
        assertEquals(listOf("https://a.example/", "https://a.example/", "https://b.example/"), h.entries.map { it.url })
    }

    @Test
    fun onlyWebPagesAreRecorded() {
        val h = history()
        listOf("about:blank", "data:text/html,hi", "file:///android_asset/x.html", "javascript:void(0)", "").forEach { h.visit(it) }
        h.visit("HTTP://plain.example/")
        assertEquals(listOf("HTTP://plain.example/"), h.entries.map { it.url })
    }

    @Test
    fun theTitleComesAfterTheStep() {
        val h = history()
        // WebView's title before the page has one is its URL, maybe without the scheme.
        h.visit("https://news.example/story", "news.example/story")
        assertEquals("", h.entries[0].title)
        assertEquals("https://news.example/story", h.entries[0].label)
        h.titled("https://news.example/story", "A story")
        assertEquals("A story", h.entries[0].title)
        h.titled("https://news.example/story", "https://news.example/story") // not a title
        h.titled("https://other.example/", "Never visited")
        assertTrue(History.urlLike("www.news.example/story/", "https://news.example/story"))
        assertTrue(!History.urlLike("News", "https://news.example/story"))
        assertEquals(listOf("A story"), h.entries.map { it.title })
        // Visited again without a title yet: the old one stays.
        h.visit("https://news.example/story")
        assertEquals("A story", h.entries[0].title)
    }

    @Test
    fun searchMatchesEveryWordInTitleOrUrl() {
        val h = history()
        h.visit("https://shop.example/shoes", "Red running shoes")
        now += 1000
        h.visit("https://news.example/run", "Morning news")
        now = at(2026, 11, 1, 9, 0)
        h.visit("https://shop.example/shoes", "Red running shoes")
        assertEquals(listOf("https://shop.example/shoes", "https://news.example/run", "https://shop.example/shoes"), h.matching("").map { it.url })
        assertEquals(2, h.matching("RED shoes").size)
        assertEquals(listOf("https://news.example/run"), h.matching("news run").map { it.url })
        assertTrue(h.matching("red news").isEmpty())
        // Suggestions: each page once, its visits added up.
        val found = h.search("run")
        assertEquals(listOf("https://shop.example/shoes", "https://news.example/run"), found.map { it.url })
        assertEquals(2, found[0].count)
        assertEquals(1, h.search("example", limit = 1).size)
        assertTrue(h.search("  ").isEmpty())
    }

    @Test
    fun deleteOneOrATimeRange() {
        val h = history()
        h.visit("https://old.example/")
        now += 3_600_000
        h.visit("https://mid.example/")
        now += 3_600_000
        h.visit("https://new.example/")
        h.delete(h.entries.first { it.url == "https://mid.example/" })
        assertEquals(listOf("https://new.example/", "https://old.example/"), h.entries.map { it.url })
        h.deleteSince(now - 60_000) // last minute
        assertEquals(listOf("https://old.example/"), h.entries.map { it.url })
        h.clear()
        assertTrue(h.entries.isEmpty())
    }

    @Test
    fun oldPagesGo() {
        val h = history()
        h.visit("https://old.example/")
        now += java.util.concurrent.TimeUnit.DAYS.toMillis(History.KEEP_DAYS + 1)
        h.visit("https://new.example/")
        assertEquals(listOf("https://new.example/"), h.entries.map { it.url })
    }

    @Test
    fun keptOverARestart() {
        val file = tmp.newFile("history.json").also { it.delete() }
        val io = UnconfinedTestDispatcher()
        val first = History(file, TestScope(io), { now }, { zone }, io)
        first.visit("https://a.example/", "A")
        now += 1000
        first.visit("https://b.example/", "B")
        first.save()
        val second = History(file, TestScope(io), { now }, { zone }, io)
        assertEquals(listOf("B", "A"), second.entries.map { it.title })
        assertEquals(first.entries, second.entries)
        // A visit made before the file is read stays on top, and the same page today shows once.
        val slow = StandardTestDispatcher()
        val third = History(file, TestScope(slow), { now + 1000 }, { zone }, slow)
        third.visit("https://a.example/", "A again")
        slow.scheduler.runCurrent()
        assertEquals(listOf("A again", "B"), third.entries.map { it.title })
    }
}
