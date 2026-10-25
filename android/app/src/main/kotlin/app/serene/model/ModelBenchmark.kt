package app.serene.model

import android.content.Context
import android.os.Build
import app.serene.BuildConfig
import app.serene.engine.EngineJson
import app.serene.engine.Features
import app.serene.engine.LiteRtClassifier
import app.serene.engine.Policy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The prototype's model eval (engine/test/llm-eval.mjs) on the phone: the same labelled overlay
 * records (engine/test/data/labeled-features.jsonl, packaged as an asset) through LiteRT-LM, so
 * accuracy and latency can be compared with the desktop numbers in docs/engine.md. Each record
 * runs twice: the full JSON reply the engine uses today, and the reply cut off once the label is
 * known ([LiteRtClassifier.stopAfterLabel]).
 */
class ModelBenchmark(private val context: Context, private val classifier: LiteRtClassifier, private val modelName: String) {
    data class Row(val id: String, val expected: String, val features: Features)

    data class Progress(val done: Int, val total: Int)

    fun records(): List<Row> = context.assets.open("labeled-features.jsonl").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.mapNotNull { line ->
            val o = runCatching { EngineJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
            val f = runCatching { EngineJson.decodeFromJsonElement(Features.serializer(), o["features"]!!) }.getOrNull() ?: return@mapNotNull null
            Row(o["id"]?.jsonPrimitive?.content ?: f.sig, o["expected"]?.jsonPrimitive?.content ?: return@mapNotNull null, f)
        }.toList()
    }

    /** Runs every record in both modes and returns a plain-text report to paste into an issue. */
    suspend fun measure(onProgress: (Progress) -> Unit): String {
        val rows = records()
        classifier.unload() // measure a cold load
        val loadMs = classifier.warmUp()
        classifier.classify(rows.first().features, stopEarly = false) // first call compiles GPU kernels
        val full = mutableListOf<Long>()
        val early = mutableListOf<Long>()
        val firstChunk = mutableListOf<Long>()
        var correct = 0
        var guarded = 0
        var same = 0
        var invalid = 0
        var earlyInvalid = 0
        val misses = mutableListOf<String>()
        rows.forEachIndexed { i, r ->
            val a = classifier.classify(r.features, stopEarly = false)
            val b = runCatching { classifier.classify(r.features, stopEarly = true) }.getOrNull()
            full += a.ms
            if (a.invalid) invalid++
            if (a.label == r.expected) correct++ else misses += "${r.id}: ${r.expected} → ${a.label}"
            // What the engine does: site headers never reach the model (Policy.looksLikeChrome).
            if ((if (Policy.looksLikeChrome(r.features)) "content" else a.label) == r.expected) guarded++
            if (b != null) {
                early += b.ms
                b.firstChunkMs?.let { firstChunk += it }
                if (b.invalid) earlyInvalid++
                if (b.label == a.label) same++
            } else earlyInvalid++
            onProgress(Progress(i + 1, rows.size))
        }
        val n = rows.size
        fun pct(k: Int) = "${(100 * k + n / 2) / n}%"
        fun med(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[it.size / 2]} ms" }
        fun p95(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) "–" else "${it[minOf(it.size - 1, (it.size * 95) / 100)]} ms" }
        val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return buildString {
            appendLine("Serene model benchmark")
            appendLine("App ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_SHA})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}$soc")
            appendLine("Model: $modelName on ${classifier.backend}, loaded in $loadMs ms")
            appendLine("Records: $n")
            appendLine("Full JSON reply: median ${med(full)}, p95 ${p95(full)}, invalid $invalid")
            appendLine("Stop after label: median ${med(early)}, p95 ${p95(early)}, first chunk median ${med(firstChunk)}, invalid $earlyInvalid, same label as full $same/$n")
            appendLine("Label accuracy ${pct(correct)}, with the header guard ${pct(guarded)}")
            if (misses.isNotEmpty()) {
                appendLine("Misses:")
                misses.forEach { appendLine("  $it") }
            }
        }
    }
}
