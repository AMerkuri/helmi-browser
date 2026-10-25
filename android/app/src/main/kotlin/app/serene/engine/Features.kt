package app.serene.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON as the content script (engine/src/content/serene.js) sends it. Unknown keys are ignored. */
val EngineJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** The content script's keyword and structure score for a candidate (`heuristics()` in serene.js). */
@Serializable
data class Heuristic(
    val label: String = "content",
    val score: Double = 0.0,
    val structure: Double = 0.0,
    val rejectIdx: Int = -1,
)

/** Features of one overlay candidate (`extract()` in serene.js). */
@Serializable
data class Features(
    val sig: String = "",
    val tag: String = "div",
    val role: String = "",
    val ariaModal: Boolean = false,
    val ariaLabel: String = "",
    val tokens: String = "",
    val text: String = "",
    val textLen: Int = 0,
    val buttons: List<String> = emptyList(),
    val hasEmail: Boolean = false,
    val hasPassword: Boolean = false,
    val hasVideo: Boolean = false,
    val iframeOrigins: List<String> = emptyList(),
    val coverage: Double = 0.0,
    val edge: String = "center",
    val zIndex: Double = 0.0,
    val scrollLocked: Boolean = false,
    val userInitiated: Boolean = false,
    val isBackdrop: Boolean = false,
    val looksLikeLayout: Boolean = false,
    val url: String = "",
    val heuristic: Heuristic = Heuristic(),
)
