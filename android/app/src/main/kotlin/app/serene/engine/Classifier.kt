package app.serene.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ClassifierResult(
    val label: String,
    val target: Int,
    val ms: Long,
    val raw: String = "",
    val invalid: Boolean = false,
    val backend: String = "",
    /** Probability of [label] when the backend scores labels (the prototype's llama.cpp mode); null here. */
    val confidence: Double? = null,
    /** Time to the first streamed chunk (about the prefill), when the reply was streamed. */
    val firstChunkMs: Long? = null,
)

/** Features in, label out (the button is the policy's call). */
interface Classifier {
    suspend fun classify(f: Features): ClassifierResult
}

/**
 * Qwen3-0.6B on LiteRT-LM. Loads lazily on the first uncertain candidate (GPU first, CPU if the
 * GPU backend fails), runs one inference at a time, and unloads after [idleMs] without calls so
 * the ~0.6 GB model does not sit in RAM.
 */
class LiteRtClassifier(
    val modelFile: File,
    private val cacheDir: File,
    private val idleMs: Long = 90_000,
) : Classifier {
    /**
     * Stream the reply and stop as soon as the label is known, instead of decoding the whole
     * `{"label": ..., "target": N}` object. The policy ignores the model's target, so the label
     * is all we need, and greedy decoding gives the same label either way. Off until the
     * on-device benchmark (ModelBenchmark) has shown it works on real phones.
     */
    @Volatile var stopAfterLabel = false
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var backendName = ""
    private var unloadJob: Job? = null

    @Volatile var lastError: String? = null
        private set

    val loaded: Boolean get() = engine != null
    val backend: String get() = backendName

    private fun load(): Engine {
        engine?.let { return it }
        var err: Throwable? = null
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            try {
                val t0 = System.nanoTime()
                val e = Engine(EngineConfig(modelPath = modelFile.path, backend = backend, maxNumTokens = 1024, cacheDir = cacheDir.path))
                e.initialize()
                Log.i(TAG, "model loaded on ${backend.name} in ${(System.nanoTime() - t0) / 1_000_000} ms")
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

    /** Loads the model if needed; returns the load time in ms (0 if it was already loaded). */
    suspend fun warmUp(): Long = withContext(Dispatchers.Default) {
        mutex.withLock {
            unloadJob?.cancel()
            val t0 = System.nanoTime()
            val wasLoaded = engine != null
            try { load() } finally { scheduleUnload() }
            if (wasLoaded) 0L else (System.nanoTime() - t0) / 1_000_000
        }
    }

    override suspend fun classify(f: Features): ClassifierResult = classify(f, stopAfterLabel)

    suspend fun classify(f: Features, stopEarly: Boolean): ClassifierResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            unloadJob?.cancel()
            try {
                val e = load()
                val prompt = Prompt.build(f)
                val t0 = System.nanoTime()
                // A fresh conversation per call: same system prefix every time, no history.
                val conversation = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(Prompt.SYSTEM),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        maxOutputToken = 32,
                        enableResponseFormat = true,
                    ),
                )
                var firstChunkMs: Long? = null
                val raw = conversation.use { c ->
                    val format = ResponseFormat.json(Prompt.OUTPUT_SCHEMA)
                    if (!stopEarly) return@use c.sendMessage(prompt, responseFormat = format).toString()
                    var acc = ""
                    try {
                        c.sendMessageAsync(prompt, responseFormat = format).first { chunk ->
                            if (firstChunkMs == null) firstChunkMs = (System.nanoTime() - t0) / 1_000_000
                            val t = chunk.toString()
                            // Chunks may be deltas or the text so far; handle both.
                            acc = if (t.startsWith(acc)) t else acc + t
                            Prompt.labelPrefix(acc) != null
                        }
                    } catch (e: NoSuchElementException) {
                        // The stream ended before the label was complete; parse what came.
                    } finally {
                        runCatching { c.cancelProcess() }
                    }
                    acc
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                lastError = null
                val r = if (stopEarly) Prompt.labelPrefix(raw)?.let { Prompt.Reply(it, -1) } ?: Prompt.parse(raw, f.buttons.size) else Prompt.parse(raw, f.buttons.size)
                ClassifierResult(r?.label ?: "content", r?.target ?: -1, ms, raw, invalid = r == null, backend = backendName, firstChunkMs = firstChunkMs)
            } catch (t: Throwable) {
                lastError = t.message
                throw t
            } finally {
                scheduleUnload()
            }
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
        private const val TAG = "SereneLLM"
    }
}
