package app.serene.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptTest {
    @Test
    fun labelPrefixIsKnownOnceItIsUnique() {
        assertNull(Prompt.labelPrefix("{\"la"))
        assertNull(Prompt.labelPrefix("{\"label\": \""))
        assertNull(Prompt.labelPrefix("{\"label\": \"co")) // cookie_consent or content
        assertEquals("cookie_consent", Prompt.labelPrefix("{\"label\": \"cookie"))
        assertEquals("content", Prompt.labelPrefix("{\"label\":\"content"))
        assertEquals("notification_prompt", Prompt.labelPrefix("{\"label\": \"notification_prompt\", \"target\": "))
    }

    @Test
    fun closedLabelMustBeALabel() {
        assertNull(Prompt.labelPrefix("{\"label\": \"cookies\", \"target\": 0}"))
        assertEquals("survey", Prompt.labelPrefix("{\"label\": \"survey\", \"target\": 1}"))
    }
}
