package app.serene.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Per-URL cosmetic resources, flattened into what the content script needs (rules.mjs). */
@Serializable
data class Cosmetic(
    val hideSelectors: List<String> = emptyList(),
    /** Cookie banners among the hide selectors: parked, not hidden, while autoconsent answers them. */
    val consentSelectors: List<String> = emptyList(),
    val procedural: List<JsonElement> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val script: String = "",
    val generichide: Boolean = false,
)

/**
 * Rules layer: Brave's adblock-rust (the crate the prototype runs through adblock-rs), built from
 * android/rules-jni with cargo-ndk. The engine is Sync on the Rust side, so every method may be
 * called from any thread (WebView calls shouldInterceptRequest on a pool).
 */
class RulesEngine private constructor(private var handle: Long) {
    /**
     * A second, small engine with only the cookie-banner rules (FilterLists.consentPart). It tells
     * which of this engine's hide selectors are consent banners; only selectors this engine
     * returns are ever used, so its exceptions still apply.
     */
    var consent: RulesEngine? = null

    /** One filter list. [trusted] lists may inject the scriptlets uBO reserves for its own lists. */
    data class ListText(val text: String, val trusted: Boolean = false)

    /** Generic selectors for the class/id tokens seen in a page, split like [Cosmetic]. */
    data class Generic(val hide: List<String>, val consent: List<String>)

    /**
     * Why a request is blocked: the rule as the list writes it, and that list's `! Title:`.
     * Either may be null (a list without a title; rules the engine fused into one).
     */
    data class Match(val rule: String?, val list: String?)

    /** Network rule check. [type] is an adblock request type ("script", "sub_frame", ...). */
    fun shouldBlock(url: String, sourceUrl: String, type: String): Boolean = match(url, sourceUrl, type) != null

    /** Like [shouldBlock], with the rule and list that block the request; null when not blocked. */
    fun match(url: String, sourceUrl: String, type: String): Match? {
        if (handle == 0L) return null
        val json = nativeMatch(handle, url, sourceUrl, type) ?: return null
        val m = runCatching { EngineJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return Match(null, null)
        val list = m["list"]?.jsonPrimitive?.intOrNull
        return Match(m["rule"]?.jsonPrimitive?.contentOrNull, list?.let { titles.getOrNull(it) })
    }

    /** Each list's title, in the order the lists were given (the saved engine keeps them). */
    private val titles: List<String?> by lazy {
        val json = if (handle != 0L) nativeTitles(handle) else null
        json?.let { runCatching { EngineJson.decodeFromString(ListSerializer(String.serializer().nullable), it) }.getOrNull() } ?: emptyList()
    }

    fun cosmetic(url: String): Cosmetic {
        val c = rawCosmetic(url)
        val cookie = consent?.rawCosmetic(url)?.hideSelectors?.toHashSet() ?: return c
        val (consentSels, hide) = c.hideSelectors.partition { it in cookie }
        return c.copy(hideSelectors = hide, consentSelectors = consentSels)
    }

    private fun rawCosmetic(url: String): Cosmetic {
        val json = if (handle != 0L) nativeCosmetic(handle, url) else null
        return json?.let { runCatching { EngineJson.decodeFromString(Cosmetic.serializer(), it) }.getOrNull() } ?: Cosmetic()
    }

    /** Generic class/id rules (`##.cookie-banner`) for the class/id tokens seen in the page. */
    fun genericSelectors(classes: List<String>, ids: List<String>, exceptions: List<String>): Generic {
        val all = rawGeneric(classes, ids, exceptions)
        val cookie = consent?.rawGeneric(classes, ids, exceptions)?.toHashSet() ?: return Generic(all, emptyList())
        val (consentSels, hide) = all.partition { it in cookie }
        return Generic(hide, consentSels)
    }

    private fun rawGeneric(classes: List<String>, ids: List<String>, exceptions: List<String>): List<String> {
        if (handle == 0L) return emptyList()
        val json = nativeGeneric(handle, classes.toTypedArray(), ids.toTypedArray(), exceptions.toTypedArray()) ?: return emptyList()
        return runCatching { EngineJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
    }

    fun serialize(): ByteArray? = if (handle != 0L) nativeSerialize(handle) else null

    fun close() {
        consent?.close()
        consent = null
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("serene_rules")
        }

        /**
         * Parse filter lists. Takes a second or two on a phone for ~65k rules: call off the main thread.
         * [resourcesJson]: uBO's scriptlets, as engine/dist/scriptlets.json or a bare resource array.
         */
        fun fromLists(lists: List<ListText>, resourcesJson: String): RulesEngine? =
            nativeCreate(lists.map { it.text }.toTypedArray(), lists.map { it.trusted }.toBooleanArray(), resourcesJson)
                .takeIf { it != 0L }?.let(::RulesEngine)

        /** Load an engine saved with [serialize]: much faster than parsing the lists again. */
        fun fromSerialized(data: ByteArray, resourcesJson: String): RulesEngine? =
            nativeDeserialize(data, resourcesJson).takeIf { it != 0L }?.let(::RulesEngine)

        @JvmStatic private external fun nativeCreate(lists: Array<String>, trusted: BooleanArray, resources: String): Long
        @JvmStatic private external fun nativeDeserialize(data: ByteArray, resources: String): Long
        @JvmStatic private external fun nativeSerialize(handle: Long): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeMatch(handle: Long, url: String, source: String, type: String): String?
        @JvmStatic private external fun nativeTitles(handle: Long): String?
        @JvmStatic private external fun nativeCosmetic(handle: Long, url: String): String?
        @JvmStatic private external fun nativeGeneric(handle: Long, classes: Array<String>, ids: Array<String>, exceptions: Array<String>): String?
    }
}
