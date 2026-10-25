package app.serene.model

import android.content.Context
import android.os.Build
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
 * run with the reply the engine uses ([LiteRtClassifier.Reply.FREE]) on the GPU, then on the CPU
 * through a second, CPU-only classifier, so the two backends can be compared on the same phone.
 */
class ModelBenchmark(private val context: Context, private val classifier: LiteRtClassifier, private val cacheDir: File, private val modelName: String) {
    data class Row(val id: String, val expected: String, val features: Features)

    data class Progress(val run: String, val done: Int, val total: Int)

    private class Run(val name: String) {
        var backend = ""
        var loadMs = 0L
        val ms = mutableListOf<Long>()
        val firstChunk = mutableListOf<Long>()
        var correct = 0
        var guarded = 0
        var invalid = 0
        val misses = mutableListOf<String>()
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
        val gpu = run("GPU", classifier, rows, onProgress)
        classifier.unload()
        val cpuOnly = LiteRtClassifier(classifier.modelFile, cacheDir, backends = listOf({ Backend.CPU() }))
        val cpu = try { run("CPU", cpuOnly, rows, onProgress) } finally { cpuOnly.unload() }
        val n = rows.size
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
            for (r in listOf(gpu, cpu)) {
                appendLine("${r.name} (ran on ${r.backend.ifEmpty { "?" }}, loaded in ${r.loadMs} ms): median ${med(r.ms)}, p95 ${p95(r.ms)}, first chunk median ${med(r.firstChunk)}, invalid ${r.invalid}, accuracy ${pct(r.correct)}, with the header guard ${pct(r.guarded)}")
            }
            for (r in listOf(gpu, cpu)) {
                if (r.misses.isEmpty()) continue
                appendLine("${r.name} misses:")
                r.misses.forEach { appendLine("  $it") }
            }
        }
    }

    private suspend fun run(name: String, c: LiteRtClassifier, rows: List<Row>, onProgress: (Progress) -> Unit): Run {
        val r = Run(name)
        onProgress(Progress(name, 0, rows.size))
        r.loadMs = c.warmUp()
        r.backend = c.backend
        c.classify(rows.first().features, LiteRtClassifier.Reply.FREE) // the first call compiles GPU kernels
        rows.forEachIndexed { i, row ->
            val a = c.classify(row.features, LiteRtClassifier.Reply.FREE)
            r.ms += a.ms
            a.firstChunkMs?.let { r.firstChunk += it }
            if (a.invalid) r.invalid++
            if (a.label == row.expected) r.correct++ else r.misses += "${row.id}: ${row.expected} → ${a.label}"
            // What the engine does: site headers never reach the model (Policy.looksLikeChrome).
            if ((if (Policy.looksLikeChrome(row.features)) "content" else a.label) == row.expected) r.guarded++
            onProgress(Progress(name, i + 1, rows.size))
        }
        return r
    }
}
