package app.serene.reader

import kotlin.math.ceil
import kotlin.math.max

// Page summaries with the on-device model: prompts, chunking and reply cleanup. Port of
// engine/src/host/summary.mjs (SummaryTest mirrors engine/test/reader.test.mjs), where
// test/summary-eval.mjs runs the same prompts on the real model.
//
// A 0.6B model reads a long article badly in one go, so a page is cut into parts that fit its
// window comfortably, each part becomes a few bullet points, and a last call merges them when
// there are too many.
object Summary {
    const val SYSTEM = "You summarize web pages for someone reading on a phone. You only use facts that are in the text you are given. You answer with a short bulleted list and nothing else."

    /**
     * Page text per model call, in estimated tokens: one call for a typical news story. The
     * summarizer's engine has a 4096-token window: this, the prompt around it (~100) and the reply
     * fit with room to spare for a low estimate.
     */
    const val CHUNK_TOKENS = 2000
    /** Parts beyond this are left out (the start of a page carries most of it); ~10 calls at most. */
    const val MAX_CHUNKS = 8
    /** A summary longer than this many bullets gets merged by one more call. */
    const val MERGE_ABOVE = 7
    const val MAX_OUTPUT_TOKENS = 220

    private val WIDE = Regex("[\\u2E80-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val SENTENCE = Regex("[^.!?。！？]*[.!?。！？]+\\s*|[^.!?。！？]+$")

    /**
     * A high guess of Qwen3's token count: about 3 characters a token for text in Latin or
     * Cyrillic script (English is nearer 4, Estonian 3), one a token for Chinese, Japanese and Korean.
     */
    fun estimateTokens(s: String): Int {
        val wide = WIDE.findAll(s).count()
        return ceil((s.length - wide) / 3.0 + wide).toInt()
    }

    /**
     * Cuts text into parts of at most [max] estimated tokens, at paragraph ends where it can, then
     * at sentence ends. A heading stays with the paragraph after it.
     */
    fun chunk(text: String, max: Int = CHUNK_TOKENS): List<String> {
        val pieces = mutableListOf<String>()
        for (p in text.split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (estimateTokens(p) <= max) { pieces += p; continue }
            var cur = ""
            // Sentences keep their trailing space, so they join back as they were.
            for (sentence in SENTENCE.findAll(p).map { it.value }) {
                // A sentence too long by itself is cut anywhere.
                val step = max(1, (sentence.length.toLong() * max / max(1, estimateTokens(sentence))).toInt())
                var i = 0
                while (i < sentence.length) {
                    val s = sentence.substring(i, minOf(sentence.length, i + step))
                    if (cur.isNotEmpty() && estimateTokens(cur) + estimateTokens(s) > max) { pieces += cur.trim(); cur = "" }
                    cur += s
                    i += step
                }
            }
            if (cur.isNotBlank()) pieces += cur.trim()
        }
        val chunks = mutableListOf<String>()
        var cur = mutableListOf<String>()
        var tokens = 0
        for (p in pieces) {
            val t = estimateTokens(p)
            if (cur.isNotEmpty() && tokens + 1 + t > max) {
                // A heading moves on with the paragraph it belongs to.
                val carry = if (cur.last().startsWith("## ") && cur.size > 1) mutableListOf(cur.removeAt(cur.lastIndex)) else mutableListOf()
                chunks += cur.joinToString("\n\n")
                cur = carry
                tokens = carry.firstOrNull()?.let(::estimateTokens) ?: 0
            }
            tokens += (if (cur.isNotEmpty()) 1 else 0) + t
            cur += p
        }
        if (cur.isNotEmpty()) chunks += cur.joinToString("\n\n")
        return chunks
    }

    /**
     * The page text without the lines that only add noise for the model: datelines, photo credits,
     * share buttons ("4 hours ago", "Reuters"). Headings stay, and so does a short paragraph in a
     * script without spaces between words.
     */
    fun clean(text: String): String = text.split(PARAGRAPH_BREAK).map { it.trim() }
        .filter { p -> p.startsWith("## ") || WORD.findAll(p).count() >= 4 || estimateTokens(p) >= 12 }
        .joinToString("\n\n")

    /** The user message for one part of the page (or the whole page, when part is 1 of 1). */
    fun partPrompt(title: String, text: String, part: Int = 1, parts: Int = 1): String {
        val what = if (parts > 1) "This is part $part of $parts of a web page." else "This is a web page."
        val bullets = if (parts > 1) "2 to 4" else "3 to 6"
        return listOfNotNull(
            what,
            title.takeIf { it.isNotEmpty() }?.let { "Title: $it" },
            "",
            text,
            "",
            "Write the $bullets most important points of this text as a list. Start each point with \"- \" and keep it to one short sentence. Use only what the text says. Write in the language of the text.",
            "/no_think",
        ).joinToString("\n")
    }

    /** The user message that merges the parts' points into one short list. */
    fun mergePrompt(title: String, points: List<String>): String = listOfNotNull(
        "These are notes on a web page, in the order of the page.",
        title.takeIf { it.isNotEmpty() }?.let { "Title: $it" },
        "",
        points.joinToString("\n") { "- $it" },
        "",
        "Merge them into the 5 most important points. Start each point with \"- \" and keep it to one short sentence. Use only what the notes say. Write in the language of the notes.",
        "/no_think",
    ).joinToString("\n")

    private val THINK = Regex("<think>[\\s\\S]*?(</think>|$)")
    private val ITEM = Regex("^\\s*(?:[-*•‣–]|\\d{1,2}[.)])\\s+(.*\\S)\\s*$")
    private val BOLD_LEAD = Regex("^\\*\\*(.+?)\\*\\*:?\\s*")
    private val MARKER = Regex("^[-*•]\\s+")
    private val QUOTED = Regex("^[\"“]\\s*(?:[-*•]\\s+)?([^\"“”]*?)\\s*[\"”]?$")
    private val ENDED = Regex("[.!?。！？…\"”')\\]]$")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    private val LETTER = Regex("\\p{L}")
    private val SPACE = Regex("\\s")

    private fun norm(s: String) = s.lowercase().replace(NOT_WORD, " ").trim()

    // Mostly letters: a line of code or a row of numbers is not a point.
    private fun prose(s: String) = LETTER.findAll(s).count() >= s.replace(SPACE, "").length * 0.5

    /**
     * The points in a model reply: list items without their markers, without a <think> block or a
     * preamble, and without repeats (a small model can loop on one sentence).
     */
    fun points(reply: String?): List<String> {
        val text = (reply ?: "").replace(THINK, "")
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (line in text.split('\n')) {
            val m = ITEM.find(line) ?: continue
            // Bold lead-ins lose their stars; a point wrapped in quotes or with a second marker loses those.
            var p = m.groupValues[1].replace(BOLD_LEAD, "$1: ").replace("**", "").trim()
            p = p.replace(MARKER, "").replace(QUOTED, "$1").trim()
            val key = norm(p)
            if (key.length < 3 || !prose(p) || !seen.add(key)) continue
            out += p
        }
        // A last point cut off by the reply's token limit: no full stop where the others have one.
        fun ended(p: String) = ENDED.containsMatchIn(p)
        if (out.size > 1 && !ended(out.last()) && out.dropLast(1).count(::ended) * 2 >= out.size - 1) out.removeAt(out.lastIndex)
        // A reply with no list at all: its sentences are the points.
        if (out.isEmpty()) {
            for (s in text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 2 }) {
                if (seen.add(norm(s))) out += s
            }
        }
        return out
    }

    enum class Step { PART, MERGE, DONE }

    data class Update(val points: List<String>, val step: Step, val part: Int, val parts: Int, val skippedParts: Int = 0)

    /**
     * The calls a page needs: one per part, plus a merge when the parts' points add up to more
     * than [MERGE_ABOVE]. [run] asks the model and returns its reply. [onUpdate] gets the points so
     * far and what is running, for a view that fills in as it goes. Returns the last update.
     */
    suspend fun summarize(title: String, text: String, run: suspend (String) -> String, onUpdate: suspend (Update) -> Unit = {}): Update {
        val all = chunk(clean(text))
        val parts = all.take(MAX_CHUNKS)
        var pts = listOf<String>()
        for ((i, part) in parts.withIndex()) {
            onUpdate(Update(pts, Step.PART, i + 1, parts.size))
            pts = pts + points(run(partPrompt(title, part, i + 1, parts.size)))
        }
        if (pts.size > MERGE_ABOVE) {
            onUpdate(Update(pts, Step.MERGE, parts.size, parts.size))
            val merged = points(run(mergePrompt(title, pts)))
            if (merged.size >= 2) pts = merged
        }
        return Update(pts, Step.DONE, parts.size, parts.size, all.size - parts.size).also { onUpdate(it) }
    }
}
