package app.serene.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TabHistoryTest {
    private val state = mapOf("WEBVIEW_CHROMIUM_STATE" to byteArrayOf(0, 1, 2, -1, 127, -128))

    @Test
    fun savedAndReadBack() {
        val file = File(Files.createTempDirectory("tab-history").toFile(), "tab-history.json")
        assertEquals(emptyList<TabHistory.Saved>(), TabHistory.load(file))
        TabHistory.save(file, listOf(TabHistory.Saved.of("https://a.example/2", state), TabHistory.Saved.of("https://b.example/", emptyMap())))
        val read = TabHistory.load(file)
        assertEquals(listOf("https://a.example/2", "https://b.example/"), read.map { it.url })
        assertArrayEquals(state.getValue("WEBVIEW_CHROMIUM_STATE"), read[0].bytes().getValue("WEBVIEW_CHROMIUM_STATE"))
        assertEquals(emptyMap<String, ByteArray>(), read[1].bytes())
    }

    @Test
    fun unreadableFileIsNoHistory() {
        val file = File(Files.createTempDirectory("tab-history").toFile(), "tab-history.json")
        file.writeText("{not json")
        assertEquals(emptyList<TabHistory.Saved>(), TabHistory.load(file))
        assertEquals(emptyMap<String, ByteArray>(), TabHistory.Saved("https://a.example/", mapOf("k" to "not base64!")).bytes())
    }

    @Test
    fun historyGoesToTheTabItWasSavedFor() {
        val a = TabHistory.Saved.of("https://a.example/", state)
        val b = TabHistory.Saved.of("https://b.example/", state)
        val blank = TabHistory.Saved.of("https://c.example/", emptyMap())
        val got = TabHistory.forTabs(listOf("https://a.example/", "https://x.example/", "https://c.example/", "https://d.example/"), listOf(a, b, blank))
        assertEquals(a, got[0])
        // Written at another time than the URLs: that tab loads its URL alone.
        assertNull(got[1])
        assertNull(got[2])
        assertNull(got[3])
    }
}
