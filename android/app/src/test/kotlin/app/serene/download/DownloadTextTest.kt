package app.serene.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DownloadTextTest {
    private val ext = mapOf("application/zip" to "zip", "application/pdf" to "pdf", "image/png" to "png")

    @Test fun contentDisposition() {
        assertEquals("serene-32-1f3d2f6.zip", DownloadText.fromContentDisposition("attachment; filename=serene-32-1f3d2f6.zip; filename*=UTF-8''serene-32-1f3d2f6.zip"))
        assertEquals("report 2026.pdf", DownloadText.fromContentDisposition("attachment; filename=\"report 2026.pdf\""))
        assertEquals("päevik.txt", DownloadText.fromContentDisposition("attachment; filename=\"fallback.txt\"; filename*=UTF-8''p%C3%A4evik.txt"))
        assertEquals("a b.txt", DownloadText.fromContentDisposition("attachment; filename=\"a \\\"b\\\".txt\""))
        assertEquals("passwd", DownloadText.fromContentDisposition("attachment; filename=\"../../etc/passwd\""))
        assertNull(DownloadText.fromContentDisposition("attachment"))
        assertNull(DownloadText.fromContentDisposition(null))
    }

    @Test fun fileName() {
        assertEquals("serene.zip", DownloadText.fileName("https://example.com/x/42", "attachment; filename=serene.zip", "application/zip", ext::get))
        assertEquals("a b.pdf", DownloadText.fileName("https://example.com/files/a%20b.pdf?sig=1", "", "application/pdf", ext::get))
        assertEquals("42.zip", DownloadText.fileName("https://github.com/o/r/actions/runs/1/artifacts/42", null, "application/zip", ext::get))
        assertEquals("download.png", DownloadText.fileName("data:image/png;base64,AAAA", null, "image/png", ext::get))
        assertEquals("download", DownloadText.fileName("https://example.com/", null, "application/octet-stream", ext::get))
        assertEquals("archive.tar.gz", DownloadText.fileName("https://example.com/archive.tar.gz", null, "application/zip", ext::get))
    }

    @Test fun cleanName() {
        assertNull(DownloadText.clean("..."))
        assertEquals("x.txt", DownloadText.clean(".x.txt"))
        assertEquals(200, DownloadText.clean("a".repeat(300) + ".zip")!!.length)
        assertEquals(".zip", DownloadText.clean("a".repeat(300) + ".zip")!!.takeLast(4))
    }

    @Test fun sizes() {
        val l = Locale.US
        assertEquals("512 B", DownloadText.size(512, l))
        assertEquals("20.39 MB", DownloadText.size(20_390_000, l))
        assertEquals("39.58 MB", DownloadText.size(39_580_000, l))
        assertEquals("613.7 MB", DownloadText.size(613_722_264, l))
        assertEquals("1.50 GB", DownloadText.size(1_500_000_000, l))
        assertEquals("20,39 MB", DownloadText.size(20_390_000, Locale.forLanguageTag("et")))
    }

    @Test fun timeLeft() {
        assertEquals("1 sec left", DownloadText.timeLeft(0))
        assertEquals("4 secs left", DownloadText.timeLeft(4))
        assertEquals("1 min left", DownloadText.timeLeft(80))
        assertEquals("3 mins left", DownloadText.timeLeft(170))
        assertEquals("2 hours left", DownloadText.timeLeft(7000))
        assertEquals("1 day left", DownloadText.timeLeft(90_000))
    }

    @Test fun progressLine() {
        val l = Locale.US
        assertEquals("20.39 MB / 39.58 MB • 4 secs left", DownloadText.progress(20_390_000, 39_580_000, 4_800_000.0, l))
        assertEquals("20.39 MB / 39.58 MB", DownloadText.progress(20_390_000, 39_580_000, 0.0, l))
        assertEquals("20.39 MB", DownloadText.progress(20_390_000, -1, 4_800_000.0, l))
    }
}
