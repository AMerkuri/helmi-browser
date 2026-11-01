package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePrintTest {
    @Test
    fun theJobIsTheTitleOnOneLine() {
        assertEquals("Harbour ferry: winter timetable", PagePrint.jobName("  Harbour ferry:\n winter\ttimetable ", "https://news.example/ferry"))
    }

    @Test
    fun aPageWithNoTitleIsNamedAfterItsSite() {
        assertEquals("news.example", PagePrint.jobName(" ", "https://www.news.example/ferry?x=1"))
        assertEquals("Helmi page", PagePrint.jobName("", "data:text/html,hello"))
        assertEquals("Helmi page", PagePrint.jobName("", "not a url"))
    }

    @Test
    fun aLongTitleIsCutWithoutSplittingAnEmoji() {
        val name = PagePrint.jobName("a".repeat(119) + "🚢 ferry", "https://news.example/")
        assertEquals("a".repeat(119), name)
        val long = PagePrint.jobName("word ".repeat(60), "https://news.example/")
        assertTrue(long.length <= 120)
        assertEquals(long.trim(), long)
    }
}
