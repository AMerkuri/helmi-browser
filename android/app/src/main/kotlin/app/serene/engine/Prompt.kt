package app.serene.engine

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// Prompt and output contract for the overlay classifier. Port of engine/src/host/classifier.mjs:
// the same text goes to Qwen3-0.6B here (LiteRT-LM) and in the prototype (llama.cpp).
object Prompt {
    val LABELS = listOf(
        "cookie_consent", "newsletter", "notification_prompt", "paywall_teaser",
        "app_install", "login_wall", "chat_widget", "survey", "content",
    )

    val SYSTEM = """
        You label overlays that appear on web pages in a mobile browser.
        Labels:
        cookie_consent: cookie, privacy, tracking or consent notice, also "accept or pay" walls
        newsletter: offers emails, updates or a discount for signing up, often with an email field
        notification_prompt: asks to enable push notifications or alerts
        paywall_teaser: says you reached a free article limit, or must subscribe or pay to keep reading
        app_install: asks to open or install an app: "open in app", "get the app", App Store, Google Play
        login_wall: blocks the page until you sign in or create an account
        chat_widget: support chat bubble
        survey: feedback or rating request
        content: anything else the reader wants: site header, navigation, menus, players, carts, filters, article text
        target: index of the button that rejects, declines or closes WITHOUT agreeing or subscribing, else -1.
        Answer with JSON only: {"label": "...", "target": N}
    """.trimIndent()

    /** JSON schema for constrained decoding, so the reply cannot be malformed. */
    val OUTPUT_SCHEMA = """
        {"type":"object","properties":{"label":{"enum":[${LABELS.joinToString(",") { "\"$it\"" }}]},"target":{"type":"integer","minimum":-1,"maximum":7}},"required":["label","target"]}
    """.trimIndent()

    /** Compact text form of the features, aimed at ~100-200 tokens. */
    fun build(f: Features): String {
        val where = mapOf(
            "full" to "covers the whole screen", "bottom" to "banner at the bottom",
            "top" to "bar at the top", "center" to "box in the middle",
        )[f.edge] ?: f.edge
        val lines = mutableListOf(
            "Overlay: $where, ${(f.coverage * 100).roundToInt()}% of screen" +
                (if (f.scrollLocked) ", page scrolling locked" else "") +
                (if (f.ariaModal) ", modal dialog" else ""),
        )
        if (f.tokens.isNotEmpty() || f.role.isNotEmpty()) {
            lines += "Element: <${f.tag}${if (f.role.isNotEmpty()) " role=${f.role}" else ""}> ${f.tokens}".trim()
        }
        if (f.iframeOrigins.isNotEmpty()) lines += "Iframe from: ${f.iframeOrigins.joinToString(", ")}"
        val inputs = listOfNotNull(
            "email field".takeIf { f.hasEmail }, "password field".takeIf { f.hasPassword }, "video".takeIf { f.hasVideo },
        )
        if (inputs.isNotEmpty()) lines += "Contains: ${inputs.joinToString(", ")}"
        lines += "Text: \"${f.text.take(300)}\""
        lines += "Buttons: " + if (f.buttons.isEmpty()) "none" else f.buttons.mapIndexed { i, b -> "[$i] $b" }.joinToString(" ")
        return lines.joinToString("\n") + "\n/no_think"
    }

    data class Reply(val label: String, val target: Int)

    private val LABEL_SO_FAR = Regex(""""label"\s*:\s*"([a-z_]+)""")

    /**
     * The label from the start of a reply, once it is certain: the label string is closed, or
     * what has been written so far starts exactly one label. All nine labels start with a
     * different word, so this is usually known one token into the label.
     */
    fun labelPrefix(partial: String): String? {
        val m = LABEL_SO_FAR.find(partial) ?: return null
        val so = m.groupValues[1]
        val closed = partial.length > m.range.last + 1 && partial[m.range.last + 1] == '"'
        if (closed) return so.takeIf { it in LABELS }
        return LABELS.filter { it.startsWith(so) }.singleOrNull()?.takeIf { so.length >= 3 }
    }

    /** Validate a raw model reply. Returns null when it is unusable. */
    fun parse(raw: String?, nButtons: Int): Reply? {
        val m = Regex("""\{[\s\S]*\}""").find(raw ?: return null) ?: return null
        val o = runCatching { EngineJson.parseToJsonElement(m.value).jsonObject }.getOrNull() ?: return null
        val label = runCatching { o["label"]?.jsonPrimitive?.content }.getOrNull()
        if (label !in LABELS) return null
        var target = runCatching { o["target"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: -1
        if (target >= nButtons) target = -1
        return Reply(label!!, target)
    }
}
