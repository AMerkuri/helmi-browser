package app.serene

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.download.Downloads
import app.serene.engine.AutoconsentRules
import app.serene.engine.Classifier
import app.serene.engine.FilterLists
import app.serene.engine.LiteRtClassifier
import app.serene.engine.SereneEngine
import app.serene.engine.VerdictCache
import app.serene.model.ModelBenchmark
import app.serene.model.ModelManager
import app.serene.model.NpuBenchmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

data class ModelSpec(
    val id: String,
    val name: String,
    val note: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Models from huggingface.co/litert-community; sizes and hashes from the Hugging Face API. */
object ModelCatalog {
    val DEFAULT = ModelSpec(
        id = "qwen3-0.6b-int8",
        name = "Qwen3-0.6B",
        note = "Default. INT8 weights, the build the engine was evaluated with.",
        file = "Qwen3-0.6B.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        sizeBytes = 613_722_264,
        sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
    )
    val SMALL = ModelSpec(
        id = "qwen3-0.6b-int4",
        name = "Qwen3-0.6B (INT4)",
        note = "Smaller and faster, for phones with less memory. Accuracy not measured yet.",
        file = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 344_671_744,
        sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
    )
    val all = listOf(DEFAULT, SMALL)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: DEFAULT
}

class SereneApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings private set
    lateinit var models: ModelManager private set
    lateinit var engine: SereneEngine private set
    lateinit var lists: FilterLists private set
    lateinit var downloads: Downloads private set

    /** Shown in settings: "loading", "ready", or an error. */
    var rulesStatus by mutableStateOf("loading")
        private set

    private var classifier: LiteRtClassifier? = null

    /** Tests swap in a stub model here. */
    @VisibleForTesting var classifierOverride: Classifier? = null

    override fun onCreate() {
        super.onCreate()
        // The NPU benchmark's process (NpuBenchmarkService) needs none of the browser.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":npu")) return
        settings = Settings(this)
        models = ModelManager(this, settings, scope)
        models.refresh()
        downloads = Downloads(this)
        lists = FilterLists(File(filesDir, "lists"))
        engine = SereneEngine(
            rules = null, // heuristics only until the lists are loaded
            cache = VerdictCache(File(filesDir, "site-rules.json")),
            classifier = ::classifierNow,
            siteEnabled = settings::isSiteEnabled,
        )
        loadRules(force = false)
        scope.launch(Dispatchers.IO) {
            // DuckDuckGo autoconsent's rule snapshot (engine/dist): a real "reject" for the consent
            // tools it knows, in any language. Until it is parsed, list-matched banners are just hidden.
            try {
                fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }
                engine.autoconsent = AutoconsentRules(asset("autoconsent-rules.json"), asset("autoconsent-config.json"))
            } catch (t: Throwable) {
                Log.e("Serene", "autoconsent rules failed", t)
            }
        }
    }

    fun loadRules(force: Boolean) {
        rulesStatus = "loading"
        scope.launch(Dispatchers.IO) {
            try {
                val rules = lists.load(force)
                val old = engine.rules
                engine.rules = rules
                if (old != null && old !== rules) old.close()
                rulesStatus = if (rules != null) "ready" else "no filter lists yet (offline?)"
            } catch (t: Throwable) {
                Log.e("Serene", "rules failed", t)
                rulesStatus = "error: ${t.message}"
            }
        }
    }

    /** The model, if it may run right now. Otherwise the engine runs rules + heuristics only. */
    fun classifierNow(): Classifier? {
        classifierOverride?.let { return it }
        if (!settings.modelEnabled) return null
        val file = models.readyFile() ?: return null
        if (settings.skipModelOnBatterySaver && getSystemService(PowerManager::class.java).isPowerSaveMode) return null
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (mem.lowMemory) return null
        return liteRtClassifier()
    }

    /** The downloaded model, whether or not browsing may use it right now (the benchmark uses it too). */
    fun liteRtClassifier(): LiteRtClassifier? {
        val file = models.readyFile() ?: return null
        synchronized(this) {
            val c = classifier
            if (c != null && c.modelFile == file) return c
            c?.let { old -> scope.launch { old.unload() } }
            return LiteRtClassifier(file, cacheDir).also { classifier = it }
        }
    }

    /** The on-device model benchmark in settings: its report, or progress while it runs. */
    var benchmarkReport by mutableStateOf<String?>(null)
        private set
    var benchmarkProgress by mutableStateOf<String?>(null)
        private set

    fun runBenchmark() {
        if (benchmarkProgress != null || npuBenchmarkProgress != null) return
        val c = liteRtClassifier() ?: return
        benchmarkProgress = "loading the model"
        scope.launch {
            benchmarkReport = try {
                ModelBenchmark(this@SereneApp, c, cacheDir, models.spec.name).measure { p -> benchmarkProgress = "${p.run}: ${p.done} of ${p.total} overlays" }
            } catch (t: Throwable) {
                Log.e("Serene", "benchmark failed", t)
                "Benchmark failed: ${t.message}"
            } finally {
                benchmarkProgress = null
            }
        }
    }

    /** The same benchmark on the Qualcomm NPU, run in a separate process (NpuBenchmark). */
    var npuBenchmarkReport by mutableStateOf<String?>(null)
        private set
    var npuBenchmarkProgress by mutableStateOf<String?>(null)
        private set

    fun runNpuBenchmark() {
        if (npuBenchmarkProgress != null || benchmarkProgress != null) return
        val file = models.readyFile() ?: return
        npuBenchmarkProgress = "starting"
        scope.launch {
            // One model in memory at a time: the NPU process loads its own copy.
            classifier?.unload()
            npuBenchmarkReport = try {
                NpuBenchmark.start(this@SereneApp, file, models.spec.name) { npuBenchmarkProgress = it }
            } catch (t: Throwable) {
                Log.e("Serene", "NPU benchmark failed", t)
                "NPU benchmark failed: ${t.message}"
            } finally {
                npuBenchmarkProgress = null
            }
        }
    }

    fun classifierInfo(): String = classifier?.let { c ->
        when {
            c.lastError != null -> "last error: ${c.lastError}"
            c.loaded -> "loaded (${c.backend})"
            else -> "not loaded (loads on the first uncertain overlay)"
        }
    } ?: "not loaded (loads on the first uncertain overlay)"
}
