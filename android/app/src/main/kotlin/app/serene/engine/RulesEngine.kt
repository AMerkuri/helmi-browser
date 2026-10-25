package app.serene.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/** Per-URL cosmetic resources, flattened into what the content script needs (rules.mjs). */
@Serializable
data class Cosmetic(
    val hideSelectors: List<String> = emptyList(),
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

    /** Network rule check. [type] is an adblock request type ("script", "sub_frame", ...). */
    fun shouldBlock(url: String, sourceUrl: String, type: String): Boolean =
        handle != 0L && nativeCheck(handle, url, sourceUrl, type)

    fun cosmetic(url: String): Cosmetic {
        val json = if (handle != 0L) nativeCosmetic(handle, url) else null
        return json?.let { runCatching { EngineJson.decodeFromString(Cosmetic.serializer(), it) }.getOrNull() } ?: Cosmetic()
    }

    /** Generic class/id rules (`##.cookie-banner`) for the class/id tokens seen in the page. */
    fun genericSelectors(classes: List<String>, ids: List<String>, exceptions: List<String>): List<String> {
        if (handle == 0L) return emptyList()
        val json = nativeGeneric(handle, classes.toTypedArray(), ids.toTypedArray(), exceptions.toTypedArray()) ?: return emptyList()
        return runCatching { EngineJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
    }

    fun serialize(): ByteArray? = if (handle != 0L) nativeSerialize(handle) else null

    fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("serene_rules")
        }

        /** Parse filter lists. Takes a second or two on a phone for ~65k rules: call off the main thread. */
        fun fromLists(lists: List<String>, resourcesJson: String): RulesEngine? =
            nativeCreate(lists.toTypedArray(), resourcesJson).takeIf { it != 0L }?.let(::RulesEngine)

        /** Load an engine saved with [serialize]: much faster than parsing the lists again. */
        fun fromSerialized(data: ByteArray, resourcesJson: String): RulesEngine? =
            nativeDeserialize(data, resourcesJson).takeIf { it != 0L }?.let(::RulesEngine)

        @JvmStatic private external fun nativeCreate(lists: Array<String>, resources: String): Long
        @JvmStatic private external fun nativeDeserialize(data: ByteArray, resources: String): Long
        @JvmStatic private external fun nativeSerialize(handle: Long): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeCheck(handle: Long, url: String, source: String, type: String): Boolean
        @JvmStatic private external fun nativeCosmetic(handle: Long, url: String): String?
        @JvmStatic private external fun nativeGeneric(handle: Long, classes: Array<String>, ids: Array<String>, exceptions: Array<String>): String?
    }
}
