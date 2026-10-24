package app.serene.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One frame's end of the bridge: a WebMessageListener reply proxy in the app. */
interface FrameRef {
    val isTop: Boolean
    fun send(json: String)
}

/**
 * Host side of the hybrid engine. Port of engine/src/host/engine.mjs: one [PageSession] per tab
 * navigation, and frames talk to it through the SereneBridge message channel.
 */
class SereneEngine(
    @Volatile var rules: RulesEngine?,
    val cache: VerdictCache,
    /** The model when it is downloaded, enabled and allowed right now (battery, memory), else null. */
    private val classifier: () -> Classifier?,
    /** Serene off globally or for this site (eTLD+1): detect only, never act. */
    private val siteEnabled: (String) -> Boolean,
    val policy: PolicyConfig = PolicyConfig(),
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun newPage(onChange: () -> Unit = {}) = PageSession(this, onChange)

    internal fun classifierNow() = classifier()
    internal fun enabledFor(url: String) = siteEnabled(siteOf(url))
}

@Serializable
data class CandidateRecord(
    val id: Int,
    val features: Features,
    val cached: Boolean = false,
    val label: String? = null,
    val action: String? = null,
    val source: String? = null,
    val buttonLabel: String? = null,
    val inFrame: String? = null,
    val decidedMs: Long? = null,
    val llmMs: Long? = null,
    val applied: String? = null,
)

class PageSession internal constructor(private val e: SereneEngine, private val onChange: () -> Unit) {
    private val frames = ConcurrentHashMap<String, FrameRef>()               // origin -> frame (consent iframes)
    private val frameSummaries = ConcurrentHashMap<String, Features>()       // origin -> {text, buttons, heuristic}
    private val records = ConcurrentHashMap<Int, CandidateRecord>()
    private val logLines = CopyOnWriteArrayList<String>()
    private var exceptions: List<String> = emptyList()
    private var generichide = false

    @Volatile var topUrl: String? = null
    @Volatile var enabled = true
        private set
    val blockedRequests = AtomicInteger(0)
    val startedAt = System.currentTimeMillis()

    /** Candidates in the order they were seen, with what was done about them. */
    fun candidates(): List<CandidateRecord> = records.values.sortedBy { it.id }

    /** What the engine acted on: everything that was not left alone. */
    fun actions(): List<CandidateRecord> = candidates().filter { it.action == "click" || it.action == "hide" || (it.cached && it.action != "ignore") }

    fun log(): List<String> = logLines.toList()

    /** A line from outside the engine, such as a page console error. */
    fun note(s: String) = log(s)

    private fun log(s: String) {
        logLines += "${System.currentTimeMillis() - startedAt}ms $s"
        if (logLines.size > 200) logLines.removeAt(0)
    }

    /** Network hook (shouldInterceptRequest), on WebView's IO threads. */
    fun shouldBlock(url: String, type: String, sourceUrl: String? = topUrl): Boolean {
        val rules = e.rules ?: return false
        if (sourceUrl == null || !enabled) return false
        val blocked = rules.shouldBlock(url, sourceUrl, type)
        if (blocked) {
            blockedRequests.incrementAndGet()
            log("blocked $type ${url.take(120)}")
            onChange()
        }
        return blocked
    }

    /** A message from a frame's content script. */
    fun onMessage(json: String, frame: FrameRef) {
        val msg = runCatching { EngineJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        when (msg.str("type")) {
            "hello" -> hello(msg, frame)
            "classIds" -> classIds(msg, frame)
            "candidate" -> e.scope.launch {
                try { candidate(msg, frame) } catch (t: Throwable) { log("candidate error: $t") }
            }
            "frameSummary" -> {
                val origin = msg.str("origin") ?: return
                val summary = runCatching { EngineJson.decodeFromJsonElement(Features.serializer(), msg) }
                    .onFailure { log("frameSummary from $origin unreadable: ${it.message}") }.getOrNull() ?: return
                frames[origin] = frame
                frameSummaries[origin] = summary
                log("frame $origin: ${summary.buttons.size} buttons, heuristic ${summary.heuristic.label} ${summary.heuristic.score}")
            }
            "frameClick" -> {
                val f = frames[msg.str("origin") ?: return] ?: return
                f.send(buildJsonObject {
                    put("type", "frameClick")
                    put("id", msg["id"]?.jsonPrimitive?.intOrNull ?: 0)
                    put("target", msg["target"]?.jsonPrimitive?.intOrNull ?: -1)
                }.toString())
            }
            "applied" -> applied(msg)
            "log" -> log("page: ${msg.str("msg")}")
        }
    }

    private fun hello(msg: JsonObject, frame: FrameRef) {
        val url = msg.str("url") ?: return
        log("hello from ${if (frame.isTop) "top" else "frame"} ${url.take(100)}")
        if (frame.isTop) {
            topUrl = url
            enabled = e.enabledFor(url)
        }
        val rules = e.rules
        var css = ""
        val out = buildJsonObject {
            put("type", "init")
            put("config", buildJsonObject { if (!enabled) put("observeOnly", true) })
            if (enabled && rules != null) {
                val t0 = System.nanoTime()
                val c = rules.cosmetic(url)
                exceptions = c.exceptions
                if (frame.isTop) generichide = c.generichide
                if (c.hideSelectors.isNotEmpty()) css = c.hideSelectors.joinToString(",\n") + "{display:none!important}"
                put("script", c.script)
                put("procedural", JsonArray(c.procedural))
                if (frame.isTop) log("cosmetic: ${c.hideSelectors.size} hide, ${c.procedural.size} procedural, scriptlets=${c.script.isNotEmpty()}, ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            if (enabled && frame.isTop) {
                put("siteRules", EngineJson.encodeToJsonElement(ListSerializer(SiteRule.serializer()), e.cache.rulesFor(url)))
                val cached = e.cache.cssFor(url)
                if (cached.isNotEmpty()) css += "\n" + cached
            }
            put("css", css)
        }
        frame.send(out.toString())
    }

    private fun classIds(msg: JsonObject, frame: FrameRef) {
        // Always reply: the content script holds its first scan until this arrives.
        val rules = e.rules
        val sels = if (enabled && rules != null && !generichide) {
            rules.genericSelectors(msg.strings("classes"), msg.strings("ids"), exceptions)
        } else emptyList()
        if (sels.isNotEmpty()) log("generic: ${sels.size} selectors")
        frame.send(buildJsonObject {
            put("type", "generic")
            put("css", if (sels.isNotEmpty()) sels.joinToString(",\n") + "{display:none!important}" else "")
        }.toString())
    }

    private suspend fun candidate(msg: JsonObject, frame: FrameRef) {
        val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return log("candidate without id")
        val fJson = msg["features"] ?: return log("candidate #$id without features")
        val f = runCatching { EngineJson.decodeFromJsonElement(Features.serializer(), fJson) }
            .onFailure { log("candidate #$id unreadable: ${it.message}") }.getOrNull() ?: return
        val t0 = System.nanoTime()
        val cached = msg["cached"]?.jsonPrimitive?.booleanOrNull == true
        if (cached) {
            val rule = e.cache.rulesFor(topUrl ?: f.url).firstOrNull { it.sig == f.sig }
            update(CandidateRecord(id, f, cached = true, label = rule?.label, action = rule?.action, source = "cache", buttonLabel = rule?.buttonLabel))
            log("candidate #$id ${f.sig}: cached ${rule?.action}")
            return
        }
        if (!enabled) { update(CandidateRecord(id, f)); return }

        // A dialog inside a cross-origin iframe (typical for CMPs): the top frame sees an empty
        // box, so wait briefly for that frame's own report and classify its text and buttons.
        var feats = f
        var inFrame: String? = null
        if (f.iframeOrigins.isNotEmpty() && f.textLen < 20 && !f.userInitiated) {
            log("candidate #$id ${f.sig}: waiting for iframe ${f.iframeOrigins}")
            val origin = waitForFrame(f.iframeOrigins, 1500)
            if (origin != null) {
                val s = frameSummaries.getValue(origin)
                feats = f.copy(text = s.text, buttons = s.buttons, heuristic = s.heuristic)
                inFrame = origin
            }
        }

        val classifier = e.classifierNow()
        var v = Policy.preDecide(feats, llmAvailable = classifier != null, p = e.policy)
        var llmMs: Long? = null
        if (v.needsLlm && classifier != null) {
            v = try {
                val r = classifier.classify(feats)
                llmMs = r.ms
                log("llm #$id: ${r.label} target=${r.target} ${r.ms} ms ${r.backend}${if (r.invalid) " INVALID: ${r.raw}" else ""}")
                Policy.verdict(r.label, r.target, feats, "llm")
            } catch (t: Throwable) {
                log("llm #$id error: ${t.message}")
                Policy.verdict("content", -1, feats, "llm-error")
            }
        }
        v = v.copy(inFrame = inFrame)
        val decidedMs = (System.nanoTime() - t0) / 1_000_000
        update(CandidateRecord(id, f, label = v.label, action = v.action, source = v.source, buttonLabel = v.buttonLabel, inFrame = inFrame, decidedMs = decidedMs, llmMs = llmMs))
        log("candidate #$id ${f.sig} → ${v.label}/${v.action} via ${v.source}${v.buttonLabel?.let { " \"$it\"" } ?: ""} in $decidedMs ms")
        frame.send(buildJsonObject {
            put("type", "verdict")
            put("id", id)
            put("action", v.action)
            put("target", v.target)
            v.buttonLabel?.let { put("buttonLabel", it) }
            inFrame?.let { put("inFrame", it) }
            put("source", v.source)
        }.toString())
        // Cache decisions that hold for the site; a user-gesture "content" is about that moment only.
        if (frame.isTop && v.source !in setOf("user-gesture", "backdrop", "llm-error")) {
            e.cache.put(topUrl ?: f.url, SiteRule(sig = f.sig, label = v.label, action = v.action, buttonLabel = v.buttonLabel, source = v.source))
        }
    }

    private fun applied(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return
        val rec = records[id] ?: return
        val fallback = msg.str("fallback")
        update(rec.copy(applied = msg.str("action") + if (fallback != null) "→$fallback" else ""))
        // The click did not dismiss it: next time, hide straight away.
        if (fallback == "hide" && rec.source != "cache") {
            topUrl?.let { e.cache.put(it, SiteRule(sig = rec.features.sig, label = rec.label ?: "content", action = "hide", source = rec.source ?: "")) }
        }
    }

    /** The user says this was not an annoyance: leave it alone on this site from now on. */
    fun undo(rec: CandidateRecord) {
        val url = topUrl ?: rec.features.url
        e.cache.undo(url, rec.features.sig)
        log("undo ${rec.features.sig}")
    }

    private suspend fun waitForFrame(origins: List<String>, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val o = origins.firstOrNull { frameSummaries.containsKey(it) }
            if (o != null || System.currentTimeMillis() > end) return o
            delay(100)
        }
    }

    private fun update(rec: CandidateRecord) {
        records[rec.id] = rec
        onChange()
    }

    fun site(): String? = topUrl?.let(::siteOf)
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.strings(k: String) = this[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
