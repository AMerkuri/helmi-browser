package app.serene

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.bookmarks.Bookmarks
import app.serene.browser.History
import app.serene.browser.SitePermissions
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
import app.serene.reader.ReaderPrefs
import app.serene.stats.Stats
import app.serene.ui.applyNightMode
import com.google.ai.edge.litertlm.Backend
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
    /**
     * For a model compiled ahead of time for an NPU: the chip it runs on (Build.SOC_MODEL). It
     * runs on the NPU only, with [NpuBenchmark.CHATML]. Null: any phone, on the GPU or CPU.
     */
    val soc: String? = null,
) {
    val npu: Boolean get() = soc != null
}

/**
 * Qwen3-0.6B fine-tuned on engine/train, exported for the GPU and CPU and compiled for an NPU by
 * the npu-model workflow and published as releases of this repo by npu-model-release (sizes and
 * hashes from the release). The stock builds came from huggingface.co/litert-community.
 */
object ModelCatalog {
    /** npu-model run 17, INT8 weights: 94% with 1 of 80 content items hidden on the desktop (stock: 83%, 6). */
    val DEFAULT = ModelSpec(
        id = "qwen3-0.6b-int8",
        name = "Qwen3-0.6B",
        note = "INT8 weights, trained for Helmi. The default on phones without an NPU model.",
        file = "Qwen3-0.6B-helmi-1.litertlm",
        url = "https://github.com/AMerkuri/helmi-browser/releases/download/qwen3-0.6b-helmi-1/Qwen3-0.6B-helmi.litertlm",
        sizeBytes = 613_474_960,
        sha256 = "21cff3d378a120dce909bb79dcc3de91599f70401ed7d836bdedcdf50e45eeeb",
    )
    /** npu-model run 17, 4-bit weights in blocks of 32: 91% with 2 of 80 hidden on the desktop (stock: 77%, 10). */
    val SMALL = ModelSpec(
        id = "qwen3-0.6b-int4",
        name = "Qwen3-0.6B (INT4)",
        note = "Smaller and about a third faster, a little less accurate than INT8.",
        file = "Qwen3-0.6B-helmi-1_wi4b32.litertlm",
        url = "https://github.com/AMerkuri/helmi-browser/releases/download/qwen3-0.6b-helmi-1/Qwen3-0.6B-helmi_wi4b32.litertlm",
        sizeBytes = 341_230_640,
        sha256 = "5a03796675201a7d9fbadabc4a2c4ffe3c14c3b84eaa2766de29776f70c1d102",
    )
    /**
     * npu-model run 16: Qwen3-0.6B fine-tuned on engine/train, 357 ms a call on a Galaxy Z Fold 7,
     * +705 MB, 92% with 3 of 80 content items hidden (run 9, the stock model: 72%, 3 hidden).
     * The file name changes with each build, because a downloaded model counts as ready when its
     * size matches and builds of the same model come out the same size.
     */
    val NPU_SM8750 = ModelSpec(
        id = "qwen3-0.6b-npu-sm8750",
        name = "Qwen3-0.6B (NPU)",
        note = "Trained for Helmi and compiled for this phone's NPU. About 3x faster than INT8 on the GPU, a third of its memory, and more accurate.",
        file = "Qwen3-0.6B.sm8750-2.litertlm",
        url = "https://github.com/AMerkuri/helmi-browser/releases/download/npu-qwen3-0.6b-sm8750-2/Qwen3-0.6B.sm8750.litertlm",
        sizeBytes = 780_882_290,
        sha256 = "fbd8a301e553e32c0a4b2c41f43b55ce072799b338d9332f6e5f7d84efb07576",
        soc = "SM8750",
    )
    val all = listOf(DEFAULT, SMALL, NPU_SM8750)

    /** Files of models no longer in the list, deleted from the phone on the next start. */
    val retiredFiles = listOf("Qwen3-0.6B.litertlm", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", "Qwen3-0.6B.sm8750.litertlm")

    private val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""

    /** The models this phone can run. */
    val available: List<ModelSpec> get() = all.filter { it.soc == null || it.soc.equals(soc, ignoreCase = true) }

    /** The model compiled for this phone's NPU if there is one, else INT8. */
    val default: ModelSpec get() = available.firstOrNull { it.npu } ?: DEFAULT

    fun byId(id: String) = available.firstOrNull { it.id == id } ?: default
}

class SereneApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings private set
    lateinit var models: ModelManager private set
    lateinit var engine: SereneEngine private set
    lateinit var lists: FilterLists private set
    lateinit var downloads: Downloads private set
    lateinit var readerPrefs: ReaderPrefs private set
    lateinit var stats: Stats private set
    lateinit var bookmarks: Bookmarks private set
    lateinit var history: History private set
    /** Camera, microphone and location: what the user allowed or blocked per site. */
    lateinit var sitePermissions: SitePermissions private set

    /** Shown in settings: "loading", "ready", or an error. */
    var rulesStatus by mutableStateOf("loading")
        private set

    private var classifier: LiteRtClassifier? = null

    /** Battery saver as BatterySaverNotice last saw it, null until it first looks. */
    var batterySaverSeen: Boolean? = null

    /** Tests swap in a stub model here. */
    @VisibleForTesting var classifierOverride: Classifier? = null

    override fun onCreate() {
        super.onCreate()
        // The NPU benchmark's process (NpuBenchmarkService) needs none of the browser.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":npu")) return
        settings = Settings(this)
        applyNightMode(this, settings.theme)
        models = ModelManager(this, settings, scope)
        models.refresh()
        downloads = Downloads(this)
        readerPrefs = ReaderPrefs(this)
        bookmarks = Bookmarks(File(filesDir, "bookmarks.json"))
        sitePermissions = SitePermissions(this)
        lists = FilterLists(File(filesDir, "lists"), { assets.open("scriptlets.json").bufferedReader().use { it.readText() } }, { settings.listSelection })
        engine = SereneEngine(
            rules = null, // heuristics only until the lists are loaded
            cache = VerdictCache(File(filesDir, "site-rules.json")),
            classifier = ::classifierNow,
            siteEnabled = settings::isSiteEnabled,
        )
        stats = Stats(File(filesDir, "stats.json"), scope)
        engine.events = stats
        history = History(File(filesDir, "history.json"), scope)
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
                val metered = getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
                val rules = lists.load(force, metered)
                val old = engine.rules
                engine.rules = rules
                if (old != null && old !== rules) old.close()
                rulesStatus = when {
                    rules != null -> "ready"
                    FilterLists.sourcesFor(settings.listSelection).isEmpty() -> "no lists switched on"
                    else -> "no filter lists yet (offline?)"
                }
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
        models.readyFile() ?: return null
        if (settings.skipModelOnBatterySaver && getSystemService(PowerManager::class.java).isPowerSaveMode) return null
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (mem.lowMemory) return null
        return liteRtClassifier()
    }

    /**
     * The downloaded model, whether or not browsing may use it right now (the benchmark uses it
     * too). A model compiled for the NPU runs there, with its chat template.
     */
    fun liteRtClassifier(): LiteRtClassifier? {
        val file = models.readyFile() ?: return null
        val spec = models.spec
        synchronized(this) {
            val c = classifier
            if (c != null && c.modelFile == file) return c
            c?.let { old -> scope.launch { old.unload() } }
            val made = if (spec.npu) {
                LiteRtClassifier(
                    file, cacheDir,
                    backends = listOf({ Backend.NPU(nativeLibraryDir = applicationInfo.nativeLibraryDir) }),
                    chatTemplate = NpuBenchmark.CHATML,
                )
            } else {
                LiteRtClassifier(file, cacheDir)
            }
            return made.also { classifier = it }
        }
    }

    /** Frees the model now if it is loaded; it loads again on the next uncertain overlay. */
    fun unloadModel() {
        val c = synchronized(this) { classifier } ?: return
        scope.launch { c.unload() }
    }

    // Out of sight the model goes at once instead of after the classifier's idle timeout. The
    // system freezes a cached app within seconds, so that timer would not run, and a frozen app
    // holding the model (about 2.4 GB on the GPU) is the first one the system kills, browser and all.
    // Calls are rare (about one per new site with an unsure overlay), so a reload is rarely paid.
    // Android 14+ only sends UI_HIDDEN and BACKGROUND; older versions also send the RUNNING_ levels.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (benchmarkProgress != null) return // it would time a reload
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) unloadModel()
    }

    /** The on-device model benchmark in settings: its report, or progress while it runs. */
    var benchmarkReport by mutableStateOf<String?>(null)
        private set
    var benchmarkProgress by mutableStateOf<String?>(null)
        private set

    fun runBenchmark() {
        if (benchmarkProgress != null || npuBenchmarkProgress != null) return
        if (models.spec.npu) return runNpuBenchmark()
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

    /**
     * The NPU benchmark runs a model imported by hand when there is one (to try an npu-model build
     * before it is released), else the downloaded NPU model.
     */
    var npuCompiledModel by mutableStateOf(false)
        private set
    /** Its size and import time, when there is one. */
    var npuCompiledModelInfo by mutableStateOf<String?>(null)
        private set

    fun refreshNpuModel() {
        val file = NpuBenchmark.compiledModel(this)
        npuCompiledModel = file.exists()
        npuCompiledModelInfo = if (file.exists()) NpuBenchmark.describe(file) else null
    }

    fun importNpuModel(uri: Uri) {
        if (npuBenchmarkProgress != null) return
        npuBenchmarkProgress = "importing the NPU model"
        scope.launch {
            try {
                val bytes = NpuBenchmark.importCompiled(this@SereneApp, uri)
                npuBenchmarkReport = "Imported the NPU model (${bytes / 1_000_000} MB). Run the NPU benchmark to try it."
            } catch (t: Throwable) {
                Log.e("Serene", "NPU model import failed", t)
                npuBenchmarkReport = "Could not import the NPU model: ${t.message}"
            } finally {
                refreshNpuModel()
                npuBenchmarkProgress = null
            }
        }
    }

    fun deleteNpuModel() {
        NpuBenchmark.compiledModel(this).delete()
        refreshNpuModel()
    }

    fun runNpuBenchmark() {
        if (npuBenchmarkProgress != null || benchmarkProgress != null) return
        val imported = NpuBenchmark.compiledModel(this).takeIf { it.exists() }
        val file = imported ?: models.readyFile()?.takeIf { models.spec.npu } ?: return
        val name = if (imported != null) "${NpuBenchmark.COMPILED_NAME}, imported (${NpuBenchmark.describe(imported)})" else "${models.spec.name}, ${models.spec.file}"
        npuBenchmarkProgress = "starting"
        scope.launch {
            // One model in memory at a time: the NPU process loads its own copy.
            classifier?.unload()
            npuBenchmarkReport = try {
                NpuBenchmark.start(this@SereneApp, file, name) { npuBenchmarkProgress = it }
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
