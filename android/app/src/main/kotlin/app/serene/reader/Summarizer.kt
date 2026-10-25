package app.serene.reader

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A summary as it fills in. [writing] is the points of the reply the model is writing right now. */
data class SummaryState(
    val points: List<String> = emptyList(),
    val writing: List<String> = emptyList(),
    val step: Summary.Step = Summary.Step.PART,
    val part: Int = 0,
    val parts: Int = 0,
    val skippedParts: Int = 0,
    /** The model is not in memory yet: the first call loads it (a few seconds). */
    val loading: Boolean = false,
    val backend: String = "",
    val ms: Long = 0,
    val error: String? = null,
) {
    val done: Boolean get() = step == Summary.Step.DONE || error != null
}

/**
 * Page summaries with the downloaded model on LiteRT-LM (Summary has the prompts). Its own
 * engine, separate from the overlay classifier's: a 4096-token window instead of 1024, so a news
 * story fits in one call. [beforeLoad] unloads the classifier's copy so two models are not in
 * memory at once; the classifier loads again on its next uncertain overlay. Unloads after
 * [idleMs] without calls.
 */
class Summarizer(
    val modelFile: File,
    private val cacheDir: File,
    private val beforeLoad: suspend () -> Unit = {},
    private val idleMs: Long = 60_000,
    private val backends: List<() -> Backend> = listOf({ Backend.GPU() }, { Backend.CPU() }),
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var backendName = ""
    private var unloadJob: Job? = null
    // Finished summaries by page URL, so reopening the sheet does not run the model again.
    private val done = object : LinkedHashMap<String, SummaryState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SummaryState>) = size > 20
    }

    val loaded: Boolean get() = engine != null

    private suspend fun load(): Engine {
        engine?.let { return it }
        beforeLoad()
        var err: Throwable? = null
        for (make in backends) {
            val backend = make()
            try {
                val t0 = System.nanoTime()
                val e = Engine(EngineConfig(modelPath = modelFile.path, backend = backend, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
                e.initialize()
                Log.i(TAG, "summary model loaded on ${backend.name} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                backendName = backend.name
                engine = e
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "backend ${backend.name} failed: ${t.message}")
                err = t
            }
        }
        throw IllegalStateException("model failed to load: ${err?.message}", err)
    }

    /** One model call in a fresh conversation; [onText] gets the reply so far as it streams in. */
    suspend fun generate(prompt: String, onText: suspend (String) -> Unit = {}): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            unloadJob?.cancel()
            try {
                val e = load()
                val conversation = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(Summary.SYSTEM),
                        // Greedy, as the desktop eval (engine/test/summary-eval.mjs) ran it.
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        maxOutputToken = Summary.MAX_OUTPUT_TOKENS,
                    ),
                )
                conversation.use { c ->
                    var acc = ""
                    try {
                        c.sendMessageAsync(prompt).collect { chunk ->
                            val t = chunk.toString()
                            // Chunks may be deltas or the text so far; handle both.
                            acc = if (t.startsWith(acc)) t else acc + t
                            onText(acc)
                        }
                    } finally {
                        runCatching { c.cancelProcess() }
                    }
                    acc
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    /** The summary of [page], filling in as the model writes. Ends with a done state. */
    fun summarize(page: ReaderPage): Flow<SummaryState> = channelFlow {
        synchronized(done) { done[page.url] }?.let { send(it); return@channelFlow }
        val t0 = System.nanoTime()
        var state = SummaryState(loading = !loaded)
        send(state)
        try {
            Summary.summarize(
                page.title,
                page.text,
                run = { prompt ->
                    generate(prompt) { partial ->
                        state = state.copy(writing = Summary.points(partial), loading = false, backend = backendName)
                        send(state)
                    }
                },
                onUpdate = { u ->
                    state = state.copy(points = u.points, writing = emptyList(), step = u.step, part = u.part, parts = u.parts, skippedParts = u.skippedParts, backend = backendName)
                    if (u.step == Summary.Step.DONE) state = state.copy(ms = (System.nanoTime() - t0) / 1_000_000)
                    send(state)
                },
            )
            if (state.points.isNotEmpty()) synchronized(done) { done[page.url] = state }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "summary failed", t)
            send(state.copy(writing = emptyList(), loading = false, error = t.message ?: t.javaClass.simpleName))
        }
    }

    private fun scheduleUnload() {
        unloadJob = scope.launch {
            delay(idleMs)
            unload()
        }
    }

    suspend fun unload() = mutex.withLock {
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "SereneSummary"
        /** The model files' context window (litert-community/Qwen3-0.6B: 4096 for INT8 and INT4). */
        const val MAX_TOKENS = 4096
    }
}
