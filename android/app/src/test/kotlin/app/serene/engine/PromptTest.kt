package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptTest {
    @Test
    fun labelIsKnownOnceItsQuoteCloses() {
        assertNull(Prompt.closedLabel("{\"la"))
        assertNull(Prompt.closedLabel("{\"label\": \""))
        assertNull(Prompt.closedLabel("{\"label\": \"cookie_con"))
        assertEquals("cookie_consent", Prompt.closedLabel("{\"label\": \"cookie_consent\","))
        assertEquals("content", Prompt.closedLabel("{\"label\":\"content\""))
        // What Qwen3 writes on LiteRT-LM without a constraint: sometimes fenced, sometimes its own label.
        assertEquals("app_install", Prompt.closedLabel("```json\n{\"label\": \"app_install\", \"target\": 1}"))
        assertEquals("cart-drawer", Prompt.closedLabel("{\"label\": \"cart-drawer\", "))
    }
}
