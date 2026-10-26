package app.serene.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.engine.EngineEvents
import app.serene.engine.EngineJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/** Lifetime totals of what Serene did, shown on the start page. */
@Serializable
data class Totals(
    val since: Long = System.currentTimeMillis(),
    val pages: Long = 0,
    /** Blocked requests by request type ("script", "image", ...). */
    val requests: Map<String, Long> = emptyMap(),
    /** Annoyances clicked away or hidden, by label ("cookie_consent", "newsletter", ...). */
    val cleared: Map<String, Long> = emptyMap(),
    /** Cookie pop-ups autoconsent answered "no" for the user. */
    val consentRejected: Long = 0,
    val modelCalls: Long = 0,
    val modelMs: Long = 0,
    /** Blocked requests plus annoyances, by site (eTLD+1); the busiest [MAX_SITES] are kept. */
    val sites: Map<String, Long> = emptyMap(),
) {
    val requestsBlocked: Long get() = requests.values.sum()
    val annoyances: Long get() = cleared.values.sum()

    /** An estimate: the lists' blocked requests never load, so their size is a typical one per type. */
    val bytesSaved: Long get() = requests.entries.sumOf { (type, n) -> n * typicalBytes(type) }

    /** An estimate too: the time it would have taken to close each pop-up by hand. */
    val secondsSaved: Long get() = annoyances * SECONDS_PER_POPUP

    fun busiestSites(n: Int): List<Pair<String, Long>> = sites.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    fun withPage() = copy(pages = pages + 1)
    fun withBlocked(site: String, type: String) = copy(requests = requests.plusOne(type), sites = sites.plusOne(site).capped())
    fun withCleared(site: String, label: String) = copy(cleared = cleared.plusOne(label), sites = sites.plusOne(site).capped())
    fun withConsentRejected() = copy(consentRejected = consentRejected + 1)
    fun withModelCall(ms: Long) = copy(modelCalls = modelCalls + 1, modelMs = modelMs + ms)

    companion object {
        const val SECONDS_PER_POPUP = 3L
        const val MAX_SITES = 200

        /**
         * Typical size of a request the lists block, by type. Measured with
         * engine/harness/blocked-sizes.mjs on the 30 crawl sites (2026-10-26): the annoyance lists
         * block few requests, nearly all scripts (cookie, newsletter and chat widgets), on average
         * 27 KB each. The other types were too rare to measure and get small, cautious sizes.
         * Whatever a blocked script would have loaded in turn isn't counted.
         */
        fun typicalBytes(type: String): Long = when (type) {
            "script" -> 27_000
            "sub_frame" -> 20_000
            "stylesheet", "font", "image" -> 10_000
            "media" -> 0 // streamed; how much of it would have played is anyone's guess
            else -> 2_000
        }

        private fun Map<String, Long>.plusOne(k: String) = this + (k to (this[k] ?: 0) + 1)

        /** Drops the quieter half of the sites once there are too many to keep. */
        private fun Map<String, Long>.capped() =
            if (size <= MAX_SITES) this else entries.sortedByDescending { it.value }.take(MAX_SITES / 2).associate { it.key to it.value }
    }
}

/**
 * The engine's events summed up into [Totals], as Compose state for the start page, and saved
 * to [file] a few seconds after a change (and on [save], when the app goes to the background).
 */
class Stats(private val file: File?, private val scope: CoroutineScope) : EngineEvents {
    var totals by mutableStateOf(load())
        private set
    private var saving: Job? = null

    private fun load(): Totals {
        val f = file ?: return Totals()
        return runCatching { EngineJson.decodeFromString(Totals.serializer(), f.readText()) }.getOrNull() ?: Totals()
    }

    private fun change(f: (Totals) -> Totals) {
        synchronized(this) {
            totals = f(totals)
            if (saving?.isActive == true) return
            saving = scope.launch { delay(5_000); save() }
        }
    }

    fun save() {
        val f = file ?: return
        val t = synchronized(this) { totals }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(EngineJson.encodeToString(Totals.serializer(), t))
            tmp.renameTo(f)
        }
    }

    /** Settings → Statistics → Reset: start counting again from now. */
    fun reset() {
        synchronized(this) { totals = Totals() }
        save()
    }

    /** Clearing browsing data forgets which sites were visited; the totals stay. */
    fun forgetSites() {
        synchronized(this) { totals = totals.copy(sites = emptyMap()) }
        save()
    }

    override fun page(site: String) = change { it.withPage() }
    override fun blocked(site: String, type: String) = change { it.withBlocked(site, type) }
    override fun cleared(site: String, label: String) = change { it.withCleared(site, label) }
    override fun consentRejected(site: String) = change { it.withConsentRejected() }
    override fun modelCall(ms: Long) = change { it.withModelCall(ms) }
}

/** What a label is called in the totals. */
val CATEGORY_NAMES = mapOf(
    "cookie_consent" to "Cookie banners",
    "newsletter" to "Newsletter pop-ups",
    "notification_prompt" to "Notification prompts",
    "paywall_teaser" to "Paywall overlays",
    "app_install" to "Open-in-app banners",
    "login_wall" to "Sign-in walls",
    "chat_widget" to "Chat widgets",
    "survey" to "Surveys",
)

/** "45 s", "12 min", "3 h 5 min", "2 d 4 h". */
fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val h = m / 60
    return when {
        seconds < 60 -> "$seconds s"
        h == 0L -> "$m min"
        h < 24 -> if (m % 60 == 0L) "$h h" else "$h h ${m % 60} min"
        else -> if (h % 24 == 0L) "${h / 24} d" else "${h / 24} d ${h % 24} h"
    }
}
