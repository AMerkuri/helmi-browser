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
        // An address on the network or this phone: http, as in Chrome.
        assertEquals("http://192.168.1.1", resolveInput("192.168.1.1", ddg))
        assertEquals("http://10.0.2.2:8080/a", resolveInput("10.0.2.2:8080/a", ddg))
        assertEquals("http://localhost:3000", resolveInput("localhost:3000", ddg))
        assertEquals("https://duckduckgo.com/?q=3.14", resolveInput("3.14", ddg))
    }
}
