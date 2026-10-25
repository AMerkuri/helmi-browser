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

    override suspend fun classify(f: Features): ClassifierResult = withContext(Dispatchers.Default) {
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
                val raw = conversation.use { it.sendMessage(prompt, responseFormat = ResponseFormat.json(Prompt.OUTPUT_SCHEMA)).toString() }
                val ms = (System.nanoTime() - t0) / 1_000_000
                lastError = null
                val r = Prompt.parse(raw, f.buttons.size)
                ClassifierResult(r?.label ?: "content", r?.target ?: -1, ms, raw, invalid = r == null, backend = backendName)
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
