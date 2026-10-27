package app.serene.model

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.serene.BuildConfig
import app.serene.engine.LiteRtClassifier
import app.serene.engine.Policy
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The model benchmark on the Qualcomm NPU (Hexagon HTP), through LiteRT-LM's Backend.NPU. It runs
 * the model compiled ahead of time for the SM8750 (android/npu-model) once that is imported
 * ([importCompiled]). Otherwise LiteRT-LM takes its generic path with the downloaded .litertlm:
 * LiteRT's Qualcomm compiler plugin compiles the parts of the graph the HTP supports when the
 * model loads, and the rest runs on the CPU. The libraries come from
 * Maven (litert-npu-runtime-qualcomm, qnn-runtime) and sit in the app's nativeLibraryDir.
 *
 * It runs in its own process (NpuBenchmarkService, android:process=":npu"), because a failure in
 * the vendor libraries can abort the process instead of throwing. The two processes talk through
 * files: the service writes its pid, its progress and finally the report; [start] polls them from
 * the app and, if the process dies first, reports that with the process's last log lines.
 */
class NpuBenchmark(private val context: Context, private val model: File, private val modelName: String) {
    /** Runs every record on the NPU and returns a plain-text report to paste into an issue. */
    suspend fun measure(progress: (String) -> Unit): String {
        Engine.setNativeMinLogSeverity(LogSeverity.INFO) // LiteRT's partition and QNN messages, for the report
        val libDir = context.applicationInfo.nativeLibraryDir
        val template = if (model == compiledModel(context)) CHATML else null
        val npu = LiteRtClassifier(model, context.cacheDir, backends = listOf({ Backend.NPU(nativeLibraryDir = libDir) }), chatTemplate = template)
        val rows = ModelBenchmark(context, npu, context.cacheDir, modelName).records()
        val n = rows.size
        val ms = mutableListOf<Long>()
        val firstChunk = mutableListOf<Long>()
        val misses = mutableListOf<String>()
        var correct = 0
        var guarded = 0
        var invalid = 0
        var contentHidden = 0
        val content = rows.count { it.expected == "content" }
        var loadMs = -1L
        var firstCallMs = -1L
        var error: String? = null
        try {
            progress("loading the model on the NPU (a model not compiled for it is compiled now, which takes a while)")
            loadMs = npu.warmUp()
            firstCallMs = npu.classify(rows.first().features, LiteRtClassifier.Reply.FREE).ms
            rows.forEachIndexed { i, row ->
                val a = npu.classify(row.features, LiteRtClassifier.Reply.FREE)
                ms += a.ms
                a.firstChunkMs?.let { firstChunk += it }
                if (a.invalid) invalid++
                if (a.label == row.expected) correct++ else misses += "${row.id}: ${row.expected} → ${a.label}"
                val label = if (Policy.looksLikeChrome(row.features)) "content" else a.label
                if (label == row.expected) guarded++
                if (row.expected == "content" && label != "content") contentHidden++
                progress("NPU: ${i + 1} of $n overlays")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "NPU benchmark failed", t)
            error = generateSequence(t) { it.cause }.mapNotNull { it.message }.distinct().joinToString(" ← ")
        } finally {
            npu.unload()
        }
        fun pct(k: Int) = "${(100 * k + n / 2) / n}%"
        fun med(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[it.size / 2]} ms" }
        fun p95(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[minOf(it.size - 1, (it.size * 95) / 100)]} ms" }
        return buildString {
            appendLine(header(modelName, n))
            appendLine("NPU libraries: ${npuLibraries(libDir).ifEmpty { "none found in $libDir" }}")
            if (error != null) appendLine("NPU failed${if (loadMs < 0) " to load" else " after loading in $loadMs ms"}: $error")
            if (ms.isNotEmpty()) {
                appendLine("NPU (loaded in $loadMs ms, first call $firstCallMs ms): median ${med(ms)}, p95 ${p95(ms)}, first chunk median ${med(firstChunk)}, invalid $invalid, accuracy ${pct(correct)}, with the header guard ${pct(guarded)}, content hidden $contentHidden/$content")
                if (ms.size < n) appendLine("Stopped after ${ms.size} of $n records")
            }
            if (misses.isNotEmpty()) {
                appendLine("NPU misses:")
                misses.forEach { appendLine("  $it") }
            }
            // How much of the graph the compiler plugin put on the NPU, and every warning or error from
            // the native libraries (QNN's and FastRPC's come under their own tags). Our own lines are
            // left out: the error above already has them, and a stack trace would crowd out the rest.
            val npuLines = logLines(Process.myPid(), last = 5000).filter { l ->
                val m = LOG_LINE.find(l) ?: return@filter false
                val (level, tag) = m.destructured
                !tag.startsWith("Serene") && (level in "WEF" || NPU_LOG_WORDS.any { l.contains(it, ignoreCase = true) })
            }.takeLast(120)
            if (npuLines.isNotEmpty()) {
                appendLine("NPU log lines:")
                npuLines.forEach { appendLine("  $it") }
            }
        }
    }

    companion object {
        const val TAG = "SereneNpu"
        private const val PROCESS = ":npu"
        private val NPU_LOG_WORDS = listOf("npu", "qnn", "htp", "dispatch", "partition", "compil", "accelerator", "delegate")
        /** A `logcat -v threadtime` line: date, time, pid, tid, level, tag. */
        private val LOG_LINE = Regex("""^\S+ \S+\s+\d+\s+\d+ ([VDIWEF]) (\S+?)\s*:""")

        /** A Qualcomm SoC on Android 12+, where LiteRT's NPU support starts. */
        val supported: Boolean
            get() = Build.VERSION.SDK_INT >= 31 && (Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) || Build.SOC_MANUFACTURER.contains("qualcomm", ignoreCase = true))

        /** Name of the model compiled ahead of time for the NPU (android/npu-model), in the report. */
        const val COMPILED_NAME = "Qwen3-0.6B a16w8, compiled for SM8750"

        /**
         * Qwen3's chat format for the compiled model. The template litert-torch's export carries is
         * Hugging Face's, which adds message contents as strings, while LiteRT-LM passes them as a
         * list of parts ("tried to use + operator on unsupported types string and sequence"). This
         * renders the same text the calibration prompts used: system, user, an empty think block.
         */
        const val CHATML =
            "{%- for m in messages -%}" +
                "{%- set c = m.content if m.content is string else (m.content | map(attribute='text') | join('')) -%}" +
                "<|im_start|>{{ m.role }}\n{{ c }}<|im_end|>\n" +
                "{% endfor %}" + // no "-": that would trim the newline before it
                "{%- if add_generation_prompt -%}<|im_start|>assistant\n<think>\n\n</think>\n\n{% endif %}"

        /** The model compiled ahead of time for the NPU, once imported ([importCompiled]). */
        fun compiledModel(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/npu/Qwen3-0.6B.sm8750.litertlm")

        /** Size and import time of the compiled model, so a report says which build it ran. */
        fun describe(file: File) = "${file.length() / 1_000_000} MB, imported ${SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(Date(file.lastModified()))}"

        /**
         * Copies a compiled model into the app: the .litertlm itself, or the .zip GitHub Actions
         * makes of the npu-model workflow's artifact (its first .litertlm entry). Returns its size.
         */
        suspend fun importCompiled(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
            val out = compiledModel(context)
            out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            val input = context.contentResolver.openInputStream(uri)?.buffered() ?: error("cannot open the file")
            input.use { raw ->
                raw.mark(4)
                val zip = ByteArray(4).let { raw.read(it) == 4 && it[0] == 'P'.code.toByte() && it[1] == 'K'.code.toByte() }
                raw.reset()
                part.outputStream().use { sink ->
                    if (!zip) return@use raw.copyTo(sink)
                    val z = ZipInputStream(raw)
                    generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".litertlm") } ?: error("no .litertlm in the zip")
                    z.copyTo(sink)
                }
            }
            if (!part.renameTo(out)) error("could not save the model")
            out.length()
        }

        private fun dir(context: Context) = File(context.filesDir, "npu-benchmark")
        private fun pidFile(context: Context) = File(dir(context), "pid")
        private fun progressFile(context: Context) = File(dir(context), "progress")
        private fun reportFile(context: Context) = File(dir(context), "report.txt")

        private fun header(modelName: String, records: Int?): String {
            val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
            return listOfNotNull(
                "Serene model benchmark (NPU)",
                "App ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_SHA})",
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}$soc",
                "Model: $modelName, free reply cut off after the label",
                records?.let { "Records: $it" },
            ).joinToString("\n")
        }

        private fun npuLibraries(libDir: String) = File(libDir).list().orEmpty()
            .filter { it.startsWith("libQnn") || it.startsWith("libLiteRt") }.sorted().joinToString(", ")

        private fun npuProcessRunning(context: Context): Boolean {
            val name = context.packageName + PROCESS
            return context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any { it.processName == name }
        }

        /**
         * App side: starts the benchmark in the NPU process and waits for its report. If the
         * process dies without one, the report says so and carries the process's log lines.
         */
        suspend fun start(context: Context, model: File, modelName: String, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
            dir(context).deleteRecursively()
            dir(context).mkdirs()
            context.startService(
                Intent(context, NpuBenchmarkService::class.java)
                    .putExtra(NpuBenchmarkService.EXTRA_MODEL, model.path)
                    .putExtra(NpuBenchmarkService.EXTRA_NAME, modelName),
            )
            waitForReport(context, modelName, onProgress)
        }

        private suspend fun waitForReport(context: Context, modelName: String, onProgress: (String) -> Unit): String {
            val t0 = System.nanoTime()
            var pid = -1
            while (true) {
                delay(1000)
                reportFile(context).readTextOrNull()?.let { return it }
                if (pid < 0) pid = pidFile(context).readTextOrNull()?.trim()?.toIntOrNull() ?: -1
                progressFile(context).readTextOrNull()?.let(onProgress)
                if (npuProcessRunning(context)) continue
                if (pid < 0) {
                    if (System.nanoTime() - t0 > 30_000_000_000L) return "The NPU benchmark process did not start."
                    continue
                }
                delay(500) // the report may land just before the process exits
                reportFile(context).readTextOrNull()?.let { return it }
                return buildString {
                    appendLine(header(modelName, records = null))
                    appendLine("NPU libraries: ${npuLibraries(context.applicationInfo.nativeLibraryDir)}")
                    appendLine("The NPU process died (a native crash, or the system killed it) while ${progressFile(context).readTextOrNull() ?: "starting"}.")
                    appendLine("Its last log lines:")
                    logLines(pid).forEach { appendLine("  $it") }
                }
            }
        }

        private fun File.readTextOrNull() = takeIf { it.exists() }?.readText()

        /** The last lines a process logged, plus the crash buffer (abort message and backtrace). */
        private fun logLines(pid: Int, last: Int = 80): List<String> {
            fun logcat(vararg args: String) = runCatching {
                val p = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + args).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readLines().also { p.waitFor() }
            }.getOrElse { listOf("(logcat failed: ${it.message})") }
            val own = logcat("--pid=$pid").takeLast(last)
            val crash = logcat("-b", "crash").filter { " $pid " in it }.takeLast(40)
            return own + crash.filter { it !in own }
        }

        /** NPU process side: the progress and report files the app polls. */
        internal fun writePid(context: Context) {
            dir(context).mkdirs()
            pidFile(context).writeText(Process.myPid().toString())
        }
        internal fun writeProgress(context: Context, text: String) = runCatching { progressFile(context).writeText(text) }
        internal fun writeReport(context: Context, text: String) {
            val tmp = File(dir(context), "report.tmp")
            tmp.writeText(text)
            tmp.renameTo(reportFile(context))
        }
    }
}

/** Runs [NpuBenchmark] in the ":npu" process, then ends that process. */
class NpuBenchmarkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = intent?.getStringExtra(EXTRA_MODEL)
        if (model == null || running) return START_NOT_STICKY
        running = true
        val name = intent.getStringExtra(EXTRA_NAME) ?: File(model).name
        scope.launch {
            NpuBenchmark.writePid(this@NpuBenchmarkService)
            val report = try {
                NpuBenchmark(this@NpuBenchmarkService, File(model), name).measure { NpuBenchmark.writeProgress(this@NpuBenchmarkService, it) }
            } catch (t: Throwable) {
                Log.e(NpuBenchmark.TAG, "NPU benchmark failed", t)
                "NPU benchmark failed: ${t.message}"
            }
            NpuBenchmark.writeReport(this@NpuBenchmarkService, report)
            stopSelf()
            // The engine's native memory and the vendor libraries go with the process.
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_NAME = "name"
    }
}
