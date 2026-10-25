package app.serene.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTest {
    private val ddg = "https://duckduckgo.com/?q=%s"

    @Test fun urlBarInput() {
        assertEquals("https://example.com", resolveInput("example.com", ddg))
        assertEquals("https://example.com/a?b=1", resolveInput("example.com/a?b=1", ddg))
        assertEquals("http://localhost:8080/", resolveInput("http://localhost:8080/", ddg))
        assertEquals("https://duckduckgo.com/?q=cookie+banners", resolveInput("cookie banners", ddg))
        assertEquals("https://duckduckgo.com/?q=kotlin", resolveInput("kotlin", ddg))
        assertEquals("about:blank", resolveInput("  ", ddg))
    }
}
