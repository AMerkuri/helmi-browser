package app.serene.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors the summary tests in engine/test/reader.test.mjs.
class SummaryTest {
    @Test
    fun chunksStayUnderTheBudgetAndKeepEveryWord() {
        fun para(n: Int, w: String) = (0 until n).joinToString(" ") { "$w sentence number $it is here." }
        val text = listOf("## One", para(150, "alpha"), "## Two", para(150, "beta"), "## Three", para(10, "gamma"), "中文句子。".repeat(3000)).joinToString("\n\n")
        val parts = Summary.chunk(text)
        assertTrue(parts.size > 3)
        for (p in parts) assertTrue("${Summary.estimateTokens(p)} tokens", Summary.estimateTokens(p) <= Summary.CHUNK_TOKENS)
        val ws = Regex("\\s")
        assertEquals(text.replace(ws, ""), parts.joinToString("").replace(ws, ""))
        for (p in parts) assertFalse("a part ends with a heading", Regex("## \\w+$").containsMatchIn(p))
        // Chinese text is cut at sentence ends and joined back without spaces.
        assertTrue(parts.any { it.startsWith("中文句子。中文句子。") })
    }

    @Test
    fun datelinesAndCreditsAreDropped() {
        val t = listOf("## Venezuela", "4 hours ago", "Reuters", "Machado returned home on Sunday after ten months in exile.", "她周日回到了委内瑞拉，结束了十个月的流亡。").joinToString("\n\n")
        assertEquals(
            listOf("## Venezuela", "Machado returned home on Sunday after ten months in exile.", "她周日回到了委内瑞拉，结束了十个月的流亡。"),
            Summary.clean(t).split("\n\n"),
        )
    }

    @Test
    fun prompts() {
        val one = Summary.partPrompt("Ferry returns", "Body.")
        assertTrue(one, one.startsWith("This is a web page.\nTitle: Ferry returns\n\nBody.\n\nWrite the 3 to 6 most important points"))
        assertTrue(one.endsWith("\n/no_think"))
        assertTrue(Summary.partPrompt("", "Body.", 2, 3).startsWith("This is part 2 of 3 of a web page.\n\nBody.\n\nWrite the 2 to 4"))
        assertTrue(Summary.mergePrompt("T", listOf("a.", "b.")).contains("\n- a.\n- b.\n\nMerge them into the 5 most important points"))
    }

    @Test
    fun pointsFromWhatQwen3Writes() {
        assertEquals(listOf("One fact.", "Two fact.", "Three: fact."), Summary.points("<think>\n\n</think>\n\nHere are the points:\n- One fact.\n* Two fact.\n1. **Three**: fact.\n- One fact.\n"))
        // Merges sometimes come back quoted and double-marked.
        assertEquals(
            listOf("Eva helped a client.", "The polygon() function.", "\"I am here,\" she wrote."),
            Summary.points("- \"- Eva helped a client.\"\n- - \"The polygon() function.\"\n- \"I am here,\" she wrote."),
        )
        // The reply ran out of tokens in the middle of its last point.
        assertEquals(listOf("First.", "Second."), Summary.points("- First.\n- Second.\n- Riigieelarve eelnõus jättis valits"))
        // A model that writes no full stops keeps its last point.
        assertEquals(listOf("First", "Second", "Third"), Summary.points("- First\n- Second\n- Third"))
        // Code the model copied out of a tutorial is not a point.
        assertEquals(listOf("Use clip-path for shapes."), Summary.points("- Use clip-path for shapes.\n- 50% 0 /* (1) */\n- 79% 90%, 2% 35%"))
        // No list at all: sentences.
        assertEquals(listOf("The ferry is back.", "It cost 1.2 million euros."), Summary.points("The ferry is back. It cost 1.2 million euros."))
    }

    @Test
    fun oneCallForAShortPagePartsAndAMergeForALongOne() = runTest {
        val prompts = mutableListOf<String>()
        val short = Summary.summarize("T", "The ferry runs again from Monday morning.", run = { prompts += it; "- A point.\n- Another point.\n- A third point." })
        assertEquals(1, prompts.size)
        assertEquals(1, short.parts)
        assertEquals(listOf("A point.", "Another point.", "A third point."), short.points)

        prompts.clear()
        val long = (0 until 12).joinToString("\n\n") { "Paragraph $it: " + "words in a long essay. ".repeat(280) }
        val steps = mutableListOf<Summary.Step>()
        var n = 0
        val r = Summary.summarize("T", long, run = { prompts += it; n++; "- Point ${n}a.\n- Point ${n}b." }, onUpdate = { steps += it.step })
        assertEquals(Summary.MAX_CHUNKS, r.parts)
        assertTrue(r.skippedParts > 0)
        assertEquals(Summary.MAX_CHUNKS + 1, prompts.size)
        assertTrue(prompts.last().startsWith("These are notes on a web page"))
        assertEquals(listOf("Point ${n}a.", "Point ${n}b."), r.points)
        assertEquals(Summary.Step.DONE, steps.last())
        assertEquals(1, steps.count { it == Summary.Step.MERGE })
    }
}
