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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One frame's end of the bridge: a WebMessageListener reply proxy in the app. */
interface FrameRef {
    val isTop: Boolean
    fun send(json: String)
}

/** What the engine did, for the app's lifetime totals (app.serene.stats). Sites are eTLD+1. */
interface EngineEvents {
    /** A page loaded with Serene on. */
    fun page(site: String) {}
    fun blocked(site: String, type: String) {}
    /** An annoyance clicked away or hidden, once per page and element; cookie banners once per page. */
    fun cleared(site: String, label: String) {}
    /** Autoconsent answered a cookie pop-up "no" for the user. */
    fun consentRejected(site: String) {}
    /** The model labelled an overlay; [acted] when the engine then clicked it away or hid it. */
    fun modelCall(ms: Long, acted: Boolean) {}
    /** The user reversed a model verdict: undid what it hid ([wasHidden]), or hid what it left alone. */
    fun modelOverruled(wasHidden: Boolean) {}
}

/** Verdict sources that mean the model decided. */
val MODEL_SOURCES = setOf("llm", "llm-unsure")

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
    /** Cookie banners the lists know are then just hidden when this is null. */
    @Volatile var autoconsent: AutoconsentRules? = null,
    /** With autoconsent on, consent tools' scripts get through the lists so it can opt out (see [ConsentTools]). */
    val allowConsentTools: Boolean = true,
    @Volatile var events: EngineEvents? = null,
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
    /** For a remembered ([cached]) verdict: the source that first made it ("llm", "heuristic", "user-hide", ...). */
    val ruleSource: String? = null,
) {
    /** Who decided: the source, or for a remembered verdict the one that first made it. */
    val decidedBy: String? get() = if (cached) ruleSource else source
    val byModel: Boolean get() = decidedBy in MODEL_SOURCES
    val acted: Boolean get() = action == "click" || action == "hide"
}

/** A model verdict on this page, for Settings › Flags › Show model decisions. */
data class ModelNotice(val record: CandidateRecord, val at: Long = System.currentTimeMillis())

/** A request the lists blocked: the rule and the list's title, when the engine knows them. */
data class BlockedRequest(val url: String, val type: String, val list: String?, val rule: String?)

/**
 * Who answered a cookie banner on the page: autoconsent ([cmp] is the consent tool it knew), or a
 * filter list's scriptlet, with the [button] it pressed and its [scriptlet] as a list writes it
 * ("+js(trusted-click-element, button.decline, , 1000)"). Also [byList] with [cmp] "Helmi" and no
 * scriptlet: Helmi pressed the reject [button] of a banner the lists know, which no rule answered.
 */
data class ConsentAnswer(val byList: Boolean, val cmp: String?, val button: String? = null, val scriptlet: String? = null)

/** Blocked requests kept per page for the site sheet; the count goes on past it. */
private const val MAX_BLOCKED = 300

/** How a filter list's answer shows in "cookies rejected (…)". */
private const val LIST_CMP = "filter list"

/** The same for Helmi pressing the reject button of a banner the lists know, when no rule answered it. */
private const val HELMI_CMP = "Helmi"

class PageSession internal constructor(private val e: SereneEngine, private val onChange: () -> Unit) {
    private val frames = ConcurrentHashMap<String, FrameRef>()               // origin -> frame (consent iframes)
    private val frameSummaries = ConcurrentHashMap<String, Features>()       // origin -> {text, buttons, heuristic}
    private val records = ConcurrentHashMap<Int, CandidateRecord>()
    private val logLines = CopyOnWriteArrayList<String>()
    private val consent = ConcurrentHashMap<String, Consent>()               // origin -> autoconsent progress in that frame
    private var exceptions: List<String> = emptyList()
    private var generichide = false
    private val cleared = ConcurrentHashMap.newKeySet<String>()             // sigs counted, and "cookie_consent" once a banner is
    private val consentCounted = AtomicBoolean(false)
    private val modelNotices = ConcurrentLinkedQueue<ModelNotice>()

    @Volatile var topUrl: String? = null
    @Volatile var enabled = true
        private set
    val blockedRequests = AtomicInteger(0)
    private val blocked = CopyOnWriteArrayList<BlockedRequest>()
    val startedAt = System.currentTimeMillis()

    /** Candidates in the order they were seen, with what was done about them. */
    fun candidates(): List<CandidateRecord> = records.values.sortedBy { it.id }

    /** What the engine acted on: everything that was not left alone. */
    fun actions(): List<CandidateRecord> = candidates().filter { it.acted || (it.cached && it.action != "ignore") }

    /** Overlays the model looked at and left alone (now or on an earlier visit), once per element. */
    fun leftByModel(): List<CandidateRecord> = candidates().filter { it.byModel && it.action == "ignore" }.distinctBy { it.features.sig }

    /** Model verdicts made on this page since the last call, at most [maxAgeMs] old (older ones are dropped). */
    fun takeModelNotices(maxAgeMs: Long = 5_000): List<CandidateRecord> {
        val now = System.currentTimeMillis()
        return generateSequence { modelNotices.poll() }.filter { now - it.at <= maxAgeMs }.map { it.record }.toList()
    }

    /** Requests the lists blocked on this page, first to last (the first [MAX_BLOCKED] kept). */
    fun blocked(): List<BlockedRequest> = blocked.toList()

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
        if (e.autoconsent != null && e.allowConsentTools && ConsentTools.matches(url)) return false
        val m = rules.match(url, sourceUrl, type) ?: return false
        blockedRequests.incrementAndGet()
        if (blocked.size < MAX_BLOCKED) blocked += BlockedRequest(url, type, m.list, m.rule)
        e.events?.blocked(siteOf(sourceUrl), type)
        log("blocked $type ${url.take(120)} by ${m.rule ?: "?"} (${m.list ?: "untitled list"})")
        onChange()
        return true
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
            "autoconsent" -> autoconsentEvent(msg, frame)
            // A trusted list's scriptlet pressed a button in a cookie banner: the lists answered it.
            // by "helmi": Helmi pressed the reject button of a banner the lists know (no rule answered it).
            "listConsent" -> {
                val origin = msg.str("origin") ?: return
                val button = msg.str("button")
                val byHelmi = msg.str("by") == "helmi"
                // The scriptlet's arguments, joined the way lists write them.
                val scriptlet = runCatching { msg["rule"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } }.getOrNull()
                    ?.let { "+js(trusted-click-element, $it)" }
                consent[origin] = Consent(if (byHelmi) HELMI_CMP else LIST_CMP, busy = false, ok = true, button = button, scriptlet = scriptlet)
                log("${if (byHelmi) "Helmi" else "filter list"} pressed \"${button ?: ""}\" in a cookie banner in ${if (frame.isTop) "top" else origin}${scriptlet?.let { " ($it)" } ?: ""}")
                countConsentRejected()
                onChange()
            }
            // A cookie banner the lists hid; the content script saw it on the page first.
            "listHit" -> {
                log("lists hid a ${msg.str("label")} in ${if (frame.isTop) "top" else "a frame"}")
                countCleared(msg.str("label") ?: return, null)
            }
            "log" -> log("page: ${msg.str("msg")}")
        }
    }

    private fun hello(msg: JsonObject, frame: FrameRef) {
        val url = msg.str("url") ?: return
        log("hello from ${if (frame.isTop) "top" else "frame"} ${url.take(100)}")
        if (frame.isTop) {
            topUrl = url
            enabled = e.enabledFor(url)
            if (enabled) e.events?.page(siteOf(url))
        }
        val rules = e.rules
        var css = ""
        val consentSelectors = mutableListOf<String>()
        val out = buildJsonObject {
            put("type", "init")
            put("config", buildJsonObject { if (!enabled) put("observeOnly", true) })
            if (enabled && rules != null) {
                val t0 = System.nanoTime()
                val c = rules.cosmetic(url)
                exceptions = c.exceptions
                if (frame.isTop) generichide = c.generichide
                if (c.hideSelectors.isNotEmpty()) css = c.hideSelectors.joinToString(",\n") + "{display:none!important}"
                consentSelectors += c.consentSelectors
                put("script", c.script)
                put("procedural", JsonArray(c.procedural))
                if (frame.isTop) log("cosmetic: ${c.hideSelectors.size} hide, ${c.consentSelectors.size} consent, ${c.procedural.size} procedural, scriptlets=${c.script.isNotEmpty()}, ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            // After an Undo on a cookie banner the user answers it: no autoconsent on the site.
            val ac = if (enabled && !e.cache.userAnswersCookies(topUrl ?: url)) e.autoconsent else null
            ac?.let { put("autoconsent", it.forFrame(url, frame.isTop)) }
            if (enabled && frame.isTop) {
                put("siteRules", EngineJson.encodeToJsonElement(ListSerializer(SiteRule.serializer()), e.cache.rulesFor(url)))
                val cached = e.cache.cssFor(url, parkCookies = ac != null)
                if (cached.isNotEmpty()) css += "\n" + cached
                if (ac != null) consentSelectors += e.cache.cookieSelectorsFor(url)
            }
            put("consentSelectors", JsonArray(consentSelectors.map(::JsonPrimitive)))
            put("css", css)
        }
        frame.send(out.toString())
    }

    private fun classIds(msg: JsonObject, frame: FrameRef) {
        // Always reply: the content script holds its first scan until this arrives.
        val rules = e.rules
        val sels = if (enabled && rules != null && !generichide) {
            rules.genericSelectors(msg.strings("classes"), msg.strings("ids"), exceptions)
        } else RulesEngine.Generic(emptyList(), emptyList())
        if (sels.hide.isNotEmpty() || sels.consent.isNotEmpty()) log("generic: ${sels.hide.size} selectors, ${sels.consent.size} consent")
        frame.send(buildJsonObject {
            put("type", "generic")
            put("css", if (sels.hide.isNotEmpty()) sels.hide.joinToString(",\n") + "{display:none!important}" else "")
            put("consentSelectors", JsonArray(sels.consent.map(::JsonPrimitive)))
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
            update(CandidateRecord(id, f, cached = true, label = rule?.label, action = rule?.action, source = "cache", buttonLabel = rule?.buttonLabel, ruleSource = rule?.source))
            if (rule != null && rule.action != "ignore") countCleared(rule.label, f.sig)
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

        // A consent dialog in a frame where autoconsent is answering it, or already has.
        var v: Verdict? = null
        val acOrigin = f.iframeOrigins.firstOrNull { consent[it]?.let { c -> c.busy || c.ok == true } == true }
        if (acOrigin != null) {
            val ac = waitForConsent(acOrigin, 4000)
            if (ac?.ok == true) v = Policy.verdict("content", -1, feats, "autoconsent")
        }

        val classifier = e.classifierNow()
        v = v ?: Policy.preDecide(feats, llmAvailable = classifier != null, p = e.policy)
        var llmMs: Long? = null
        if (v.needsLlm && classifier != null) {
            v = try {
                val r = classifier.classify(feats)
                llmMs = r.ms
                log("llm #$id: ${r.label}${r.confidence?.let { " p=%.2f".format(it) } ?: ""} ${r.ms} ms ${r.backend}${if (r.invalid) " INVALID: ${r.raw}" else ""}")
                // The model only names the label; which button to press is the policy's call.
                Policy.fromModel(r.label, r.confidence, feats, e.policy).also { e.events?.modelCall(r.ms, it.action == "click" || it.action == "hide") }
            } catch (t: Throwable) {
                log("llm #$id error: ${t.message}")
                Policy.verdict("content", -1, feats, "llm-error")
            }
        }
        v = v.copy(inFrame = inFrame)
        if (v.action == "click" || v.action == "hide") countCleared(v.label, f.sig)
        val decidedMs = (System.nanoTime() - t0) / 1_000_000
        val rec = CandidateRecord(id, f, label = v.label, action = v.action, source = v.source, buttonLabel = v.buttonLabel, inFrame = inFrame, decidedMs = decidedMs, llmMs = llmMs)
        if (rec.byModel && modelNotices.size < 20) modelNotices += ModelNotice(rec)
        update(rec)
        log("candidate #$id ${f.sig} → ${v.label}/${v.action} via ${v.source}${v.buttonLabel?.let { " \"$it\"" } ?: ""} in $decidedMs ms")
        frame.send(buildJsonObject {
            put("type", "verdict")
            put("id", id)
            put("label", v.label)
            put("action", v.action)
            put("target", v.target)
            v.buttonLabel?.let { put("buttonLabel", it) }
            inFrame?.let { put("inFrame", it) }
            put("source", v.source)
        }.toString())
        // Cache decisions that hold for the site; a user-gesture "content" is about that moment only.
        if (frame.isTop && v.source !in setOf("user-gesture", "backdrop", "llm-error", "autoconsent")) {
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
        e.cache.undo(url, rec.features.sig, rec.label?.takeIf { it != "content" })
        if (rec.byModel && rec.acted) e.events?.modelOverruled(wasHidden = true)
        log("undo ${rec.features.sig}")
    }

    /**
     * The user says an overlay left alone is an annoyance after all: hide it on this site from now
     * on, as the heuristics' kind when they named one (a cookie banner is then still parked for
     * autoconsent). Undo in the site sheet reverses it.
     */
    fun hide(rec: CandidateRecord) {
        val url = topUrl ?: rec.features.url
        val label = rec.features.heuristic.label.takeIf { it != "content" } ?: "other"
        e.cache.put(url, SiteRule(sig = rec.features.sig, label = label, action = "hide", source = "user-hide"))
        if (rec.byModel) e.events?.modelOverruled(wasHidden = false)
        log("hide ${rec.features.sig}")
    }

    /**
     * Elements the user hid on this site. A remembered hide applies as CSS before the detector sees
     * the element, so it is never a candidate on a later page: this is how the sheet lists it.
     */
    fun hiddenByUser(): List<SiteRule> = topUrl?.let(e.cache::hiddenByUser) ?: emptyList()

    /** Reverse a Hide: drop the rule so the element is judged afresh on the next load. */
    fun showAgain(rule: SiteRule) {
        val url = topUrl ?: return
        e.cache.remove(url, rule.sig)
        log("show again ${rule.sig}")
    }

    /** Elements the user undid on this site, which Helmi leaves alone until hidden again. */
    fun undone(): List<SiteRule> = topUrl?.let(e.cache::undone) ?: emptyList()

    /** Reverse an undo: drop the allow rule so the element is judged afresh on the next load. */
    fun hideAgain(rule: SiteRule) {
        val url = topUrl ?: return
        e.cache.remove(url, rule.sig)
        log("hide again ${rule.sig}")
    }

    /** Autoconsent progress in one frame: found a CMP popup, opted out (or failed), finished. */
    private data class Consent(val cmp: String?, val busy: Boolean = false, val ok: Boolean? = null, val button: String? = null, val scriptlet: String? = null)

    private fun autoconsentEvent(msg: JsonObject, frame: FrameRef) {
        val origin = msg.str("origin") ?: return
        val event = msg.str("event")
        val cmp = msg.str("cmp")
        val result = msg["result"]?.jsonPrimitive?.booleanOrNull
        consent.compute(origin) { _, old ->
            val c = old ?: Consent(cmp)
            when (event) {
                "popupFound" -> c.copy(cmp = cmp, busy = true)
                "optOutResult" -> c.copy(busy = false, ok = result == true)
                "autoconsentDone" -> c.copy(busy = false, ok = c.ok ?: true)
                else -> c
            }
        }
        log("autoconsent ${if (frame.isTop) "top" else origin}: $event ${cmp ?: ""}${result?.let { " → $it" } ?: ""}")
        if (event == "optOutResult" && result == true) countConsentRejected()
        onChange()
    }

    private fun countConsentRejected() {
        if (!consentCounted.compareAndSet(false, true)) return
        site()?.let { e.events?.consentRejected(it) }
        countCleared("cookie_consent", null)
    }

    /** CMPs autoconsent opted out of on this page, and "filter list" when a list's scriptlet
     *  answered a banner (for the shield sheet and the report). */
    fun consentRejected(): List<String> = consent.values.filter { it.ok == true }.mapNotNull { it.cmp }.distinct()

    /** The same, one entry per frame, with what a filter list pressed (the site sheet's detail). */
    fun consentAnswers(): List<ConsentAnswer> = consent.values.filter { it.ok == true }
        .map { ConsentAnswer(byList = it.cmp == LIST_CMP || it.cmp == HELMI_CMP, cmp = it.cmp, button = it.button, scriptlet = it.scriptlet) }

    private suspend fun waitForConsent(origin: String, ms: Long): Consent? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val c = consent[origin]
            if (c?.busy != true || System.currentTimeMillis() > end) return c
            delay(100)
        }
    }

    private suspend fun waitForFrame(origins: List<String>, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val o = origins.firstOrNull { frameSummaries.containsKey(it) }
            if (o != null || System.currentTimeMillis() > end) return o
            delay(100)
        }
    }

    /**
     * For the totals: an element counts once per page however often it comes back, and a page
     * has one cookie banner, whether the lists, autoconsent or the detector dealt with it.
     */
    private fun countCleared(label: String, sig: String?) {
        if (sig != null && !cleared.add(sig)) return
        if (label == "cookie_consent" && !cleared.add(label)) return
        val site = site() ?: return
        e.events?.cleared(site, label)
    }

    private fun update(rec: CandidateRecord) {
        records[rec.id] = rec
        onChange()
    }

    fun site(): String? = topUrl?.let(::siteOf)
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.strings(k: String) = this[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
