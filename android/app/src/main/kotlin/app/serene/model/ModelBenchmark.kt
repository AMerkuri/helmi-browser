package app.serene.model

import android.content.Context
import android.os.Build
import android.os.Debug
import app.serene.BuildConfig
import app.serene.engine.EngineJson
import app.serene.engine.Features
import app.serene.engine.LiteRtClassifier
import app.serene.engine.Policy
import com.google.ai.edge.litertlm.Backend
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The prototype's model eval (engine/test/llm-eval.mjs) on the phone: the same labelled overlay
 * records (engine/test/data/labeled-features.jsonl, packaged as an asset) through LiteRT-LM, so
 * accuracy and latency can be compared with the desktop numbers in docs/engine.md. The records
 * run with the reply the engine uses ([LiteRtClassifier.Reply.FREE]) on the GPU, first with a new
 * conversation per call and then with one prepared ahead of time ([LiteRtClassifier.prepare]),
 * and then on the CPU through a second, CPU-only classifier.
 */
class ModelBenchmark(private val context: Context, private val classifier: LiteRtClassifier, private val cacheDir: File, private val modelName: String) {
    data class Row(val id: String, val expected: String, val features: Features)

    data class Progress(val run: String, val done: Int, val total: Int)

    private class Run(val name: String) {
        var backend = ""
        var loadMs = 0L
        val ms = mutableListOf<Long>()
        val prepareMs = mutableListOf<Long>()
        var prepared = 0
        val firstChunk = mutableListOf<Long>()
        var correct = 0
        var guarded = 0
        /** Content records the engine would hide: given an annoyance label, header guard included. */
        var falsePositives = 0
        var invalid = 0
        val misses = mutableListOf<String>()
        /** The process's memory over the app without a model loaded, sampled between calls; the largest sample. */
        var peak: Mem? = null
    }

    /** Proportional set size in kB: the whole process, its native heap, and graphics (GPU buffers). */
    data class Mem(val total: Int, val native: Int, val graphics: Int) {
        operator fun minus(o: Mem) = Mem(total - o.total, native - o.native, graphics - o.graphics)
        fun mb(kb: Int) = "${(kb + 512) / 1024} MB"
        override fun toString() = "${mb(total)} (native ${mb(native)}, graphics ${mb(graphics)})"
    }

    private fun mem(): Mem {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return Mem(mi.totalPss, mi.nativePss, mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0)
    }

    fun records(): List<Row> = context.assets.open("labeled-features.jsonl").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.mapNotNull { line ->
            val o = runCatching { EngineJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
            val f = runCatching { EngineJson.decodeFromJsonElement(Features.serializer(), o["features"]!!) }.getOrNull() ?: return@mapNotNull null
            Row(o["id"]?.jsonPrimitive?.content ?: f.sig, o["expected"]?.jsonPrimitive?.content ?: return@mapNotNull null, f)
        }.toList()
    }

    /** Runs every record on each backend and returns a plain-text report to paste into an issue. */
    suspend fun measure(onProgress: (Progress) -> Unit): String {
        val rows = records()
        classifier.unload() // measure a cold load, and keep only one model in memory
        val baseline = mem()
        val keep = classifier.prepare
        val (gpu, gpuPrepared) = try {
            run("GPU", classifier, rows, onProgress, prepare = false, baseline) to run("GPU, prepared", classifier, rows, onProgress, prepare = true, baseline)
        } finally {
            classifier.prepare = keep
        }
        classifier.unload()
        val cpuOnly = LiteRtClassifier(classifier.modelFile, cacheDir, backends = listOf({ Backend.CPU() }))
        val cpu = try { run("CPU, prepared", cpuOnly, rows, onProgress, prepare = true, mem()) } finally { cpuOnly.unload() }
        val runs = listOf(gpu, gpuPrepared, cpu)
        val n = rows.size
        val contentRows = rows.count { it.expected == "content" }
        fun pct(k: Int) = "${(100 * k + n / 2) / n}%"
        fun med(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[it.size / 2]} ms" }
        fun p95(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[minOf(it.size - 1, (it.size * 95) / 100)]} ms" }
        val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return buildString {
            appendLine("Serene model benchmark")
            appendLine("App ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_SHA})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}$soc")
            appendLine("Model: $modelName, free reply cut off after the label")
            appendLine("Records: $n")
            for (r in runs) {
                appendLine("${r.name} (ran on ${r.backend.ifEmpty { "?" }}, loaded in ${r.loadMs} ms): median ${med(r.ms)}, p95 ${p95(r.ms)}, first chunk median ${med(r.firstChunk)}, invalid ${r.invalid}, accuracy ${pct(r.correct)}, with the header guard ${pct(r.guarded)}, content hidden ${r.falsePositives}/$contentRows" +
                    if (r.prepareMs.isNotEmpty()) ", prepared ${r.prepared}/$n (preparing took median ${med(r.prepareMs)})" else "")
            }
            for (r in runs) {
                if (r.misses.isEmpty()) continue
                appendLine("${r.name} misses:")
                r.misses.forEach { appendLine("  $it") }
            }
            appendLine("Memory with the model loaded, over the app without it (PSS, largest sample between calls):")
            for (r in runs) r.peak?.let { appendLine("  ${r.name}: +$it") }
        }
    }

    private suspend fun run(name: String, c: LiteRtClassifier, rows: List<Row>, onProgress: (Progress) -> Unit, prepare: Boolean, baseline: Mem): Run {
        val r = Run(name)
        fun sample() {
            val m = mem() - baseline
            if (r.peak.let { it == null || m.total > it.total }) r.peak = m
        }
        onProgress(Progress(name, 0, rows.size))
        c.prepare = prepare
        r.loadMs = c.warmUp()
        r.backend = c.backend
        c.classify(rows.first().features, LiteRtClassifier.Reply.FREE) // the first call compiles GPU kernels
        sample()
        rows.forEachIndexed { i, row ->
            if (i % 20 == 19) sample() // between calls, outside the timings
            // In the app the next conversation is prepared while the reader is on the page;
            // here it is done (and timed) between calls, so each call can use one.
            if (prepare) {
                val t0 = System.nanoTime()
                c.prepareNow() // or waits for the one the last call started
                r.prepareMs += (System.nanoTime() - t0) / 1_000_000
            }
            val a = c.classify(row.features, LiteRtClassifier.Reply.FREE)
            r.ms += a.ms
            if (a.prepared) r.prepared++
            a.firstChunkMs?.let { r.firstChunk += it }
            if (a.invalid) r.invalid++
            if (a.label == row.expected) r.correct++ else r.misses += "${row.id}: ${row.expected} → ${a.label}"
            // What the engine does: site headers never reach the model (Policy.looksLikeChrome).
            val engineLabel = if (Policy.looksLikeChrome(row.features)) "content" else a.label
            if (engineLabel == row.expected) r.guarded++
            if (row.expected == "content" && engineLabel != "content") r.falsePositives++
            onProgress(Progress(name, i + 1, rows.size))
        }
        return r
    }
}
