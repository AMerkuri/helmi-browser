package app.serene.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Autoconsent rules for each frame. Port of engine/src/host/autoconsent.mjs: DuckDuckGo's compact
 * CMP rule set (engine/dist/autoconsent-rules.json, an asset) cut down to the rules that can run
 * on a URL, plus the config (engine/dist/autoconsent-config.json). [forFrame] mirrors
 * autoconsent's own filterCompactRules, without its last step that blanks unused strings to save
 * bytes (the rules read the same either way).
 */
class AutoconsentRules(rulesJson: String, configJson: String) {
    private val config: JsonObject = EngineJson.parseToJsonElement(configJson).jsonObject
    private val v: JsonElement
    private val s: JsonArray
    private val r: JsonArray
    private val genericRules: IntRange
    private val frameRules: IntRange
    private val specificRules: IntRange
    private val frameStringEnd: Int
    private val genericStringEnd: Int
    private val patterns = HashMap<String, Regex?>()

    init {
        val o = EngineJson.parseToJsonElement(rulesJson).jsonObject
        v = o.getValue("v")
        s = o.getValue("s").jsonArray
        r = o.getValue("r").jsonArray
        val index = o.getValue("index").jsonObject
        fun range(k: String) = index.getValue(k).jsonArray.let { it[0].jsonPrimitive.intOrNull!! until it[1].jsonPrimitive.intOrNull!! }
        genericRules = range("genericRuleRange")
        frameRules = range("frameRuleRange")
        specificRules = range("specificRuleRange")
        frameStringEnd = index.getValue("frameStringEnd").jsonPrimitive.intOrNull!!
        genericStringEnd = index.getValue("genericStringEnd").jsonPrimitive.intOrNull!!
    }

    /** What the frame's content script needs: {config, rules}. */
    fun forFrame(url: String, isTop: Boolean): JsonObject {
        val (strings, rules) = if (!isTop) {
            s.subList(0, frameStringEnd) to rules(frameRules).filter { runsIn(it, isTop, url) }
        } else {
            val specific = rules(specificRules).filter { runsIn(it, isTop, url) }
            if (specific.isNotEmpty()) s to rules(genericRules) + specific
            else s.subList(0, genericStringEnd) to rules(genericRules)
        }
        return buildJsonObject {
            put("config", config)
            put("rules", buildJsonObject {
                put("autoconsent", JsonArray(emptyList()))
                put("compact", buildJsonObject {
                    put("v", v)
                    put("s", JsonArray(strings))
                    put("r", JsonArray(rules))
                })
            })
        }
    }

    private fun rules(range: IntRange) = r.subList(range.first, range.last + 1)

    // shouldRunRuleInContext: rule[4] encodes main/frame, rule[3] is a URL regex.
    private fun runsIn(rule: JsonElement, isTop: Boolean, url: String): Boolean {
        val a = rule.jsonArray
        val runContext = a[4].jsonPrimitive.intOrNull
        if (isTop && runContext == 1) return false
        if (!isTop && runContext in setOf(20, 22, 10, 12)) return false
        val pattern = a[3].jsonPrimitive.contentOrNull
        if (pattern.isNullOrEmpty()) return true
        val re = synchronized(patterns) { patterns.getOrPut(pattern) { runCatching { Regex(pattern) }.getOrNull() } }
        return re?.containsMatchIn(url) == true
    }
}

/**
 * Consent tools' own hosts (CONSENT_TOOL_HOSTS in engine/src/host/autoconsent.mjs). With
 * autoconsent on they get through the lists, so it can answer their banner with a real opt-out
 * instead of the tool never loading and nothing being recorded.
 */
object ConsentTools {
    private val HOSTS = Regex("""(^|\.)(cookielaw\.org|onetrust\.com|cookiebot\.(com|eu)|usercentrics\.(eu|com)|didomi\.io|privacy-center\.org|privacy-mgmt\.com|sp-prod\.net|consensu\.org|quantcast\.com|inmobi\.com|trustarc\.com|truste\.com|consentmanager\.(net|de)|iubenda\.com|cookieyes\.com|osano\.com|termly\.io|cookie-script\.com|axeptio\.eu|cookiefirst\.com|privacymanager\.io|fundingchoicesmessages\.google\.com|sirdata\.(com|net)|ketchcdn\.com|clickio\.com|uniconsent\.com)$""")

    fun matches(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return HOSTS.containsMatchIn(host)
    }
}
