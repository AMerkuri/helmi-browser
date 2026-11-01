package app.serene.browser

import android.Manifest
import android.content.Context
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.serene.engine.siteOf
import java.net.URI

/** What a site can ask for, as in Chrome's site settings, with the Android permissions each needs. */
enum class SitePermission(val key: String, val label: String, val noun: String, val android: List<String>) {
    Camera("camera", "Camera", "camera", listOf(Manifest.permission.CAMERA)),
    Microphone("mic", "Microphone", "microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    // Either is enough: Android 12+ lets the user give only the approximate location.
    Location("location", "Location", "location", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    ;

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** What to do with a site's request, given the choices it has: [Grant], [Deny], or ask about some. */
sealed interface PermissionDecision {
    data object Grant : PermissionDecision
    data object Deny : PermissionDecision
    /** The ones the user has not chosen for yet. */
    data class Ask(val kinds: Set<SitePermission>) : PermissionDecision
}

/**
 * A request for camera and microphone together (a video call) is one getUserMedia call: it fails
 * as a whole if either is missing, so one blocked kind blocks it. Undecided ones are asked about.
 */
fun decidePermission(kinds: Set<SitePermission>, choices: Map<SitePermission, Boolean>): PermissionDecision = when {
    kinds.isEmpty() -> PermissionDecision.Deny
    kinds.any { choices[it] == false } -> PermissionDecision.Deny
    kinds.all { choices[it] == true } -> PermissionDecision.Grant
    else -> PermissionDecision.Ask(kinds.filterTo(linkedSetOf()) { it !in choices })
}

/**
 * The origin choices are kept for, as Chrome keeps them: scheme, host, and a port other than the
 * scheme's own. Web pages only (WebView offers these to secure origins: https, and localhost).
 */
fun permissionOrigin(url: String): String? {
    val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = u.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
    val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val port = u.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
    return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}

/** "example.com" for https://example.com, the whole origin otherwise. */
fun originLabel(origin: String) = origin.removePrefix("https://")

/** One origin's choices as stored: "camera=1,location=0". */
internal fun encodeChoices(choices: Map<SitePermission, Boolean>) =
    choices.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.key}=${if (it.value) 1 else 0}" }

internal fun decodeChoices(s: String): Map<SitePermission, Boolean> = s.split(',').mapNotNull { part ->
    val kind = SitePermission.byKey(part.substringBefore('=')) ?: return@mapNotNull null
    when (part.substringAfter('=', "")) {
        "1" -> kind to true
        "0" -> kind to false
        else -> null
    }
}.toMap()

/**
 * The user's Allow and Block per origin, kept until reset (the site sheet, Clear site data, or
 * Settings › Site permissions). Compose state, so the sheets follow changes.
 */
class SitePermissions(context: Context) : PermissionChoices {
    private val prefs = context.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)

    var all by mutableStateOf<Map<String, Map<SitePermission, Boolean>>>(
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to decodeChoices(it) } }.filter { it.second.isNotEmpty() }.toMap(),
    )
        private set

    override fun choices(origin: String): Map<SitePermission, Boolean> = all[origin].orEmpty()

    override fun set(origin: String, kind: SitePermission, allow: Boolean) = put(origin, choices(origin) + (kind to allow))

    fun reset(origin: String, kind: SitePermission) = put(origin, choices(origin) - kind)

    /** The origins on [site] (eTLD+1) that have choices, with them. */
    fun forSite(site: String): Map<String, Map<SitePermission, Boolean>> = all.filterKeys { siteOf(it) == site }

    /** Ask again on every origin of [site]. */
    fun resetSite(site: String) = forSite(site).keys.forEach { put(it, emptyMap()) }

    fun resetAll() {
        all = emptyMap()
        prefs.edit { clear() }
        GeolocationPermissions.getInstance().clearAll()
    }

    private fun put(origin: String, choices: Map<SitePermission, Boolean>) {
        all = if (choices.isEmpty()) all - origin else all + (origin to choices)
        prefs.edit { if (choices.isEmpty()) remove(origin) else putString(origin, encodeChoices(choices)) }
        // Helmi never has WebView keep a location answer, but an old one must not outlive a reset.
        if (SitePermission.Location !in choices) GeolocationPermissions.getInstance().clear("$origin/")
    }
}

/** What the user chose per origin: [SitePermissions], or a stand-in in tests. */
interface PermissionChoices {
    fun choices(origin: String): Map<SitePermission, Boolean>
    fun set(origin: String, kind: SitePermission, allow: Boolean)
}

/** The activity behind the tabs: asks Android for the permissions a site's request needs. */
interface PermissionHost {
    /** [done] gets true when Android lets Helmi use every one of [kinds]. */
    fun requestAndroidPermissions(kinds: Set<SitePermission>, done: (Boolean) -> Unit)
}

/**
 * A site's request waiting for the user: Allow or Block [kinds] for [origin]; [all] is the whole
 * request. [page]: the origin of the tab's page when it asked (a frame's origin can differ).
 */
class PermissionAsk internal constructor(
    val origin: String,
    val kinds: Set<SitePermission>,
    internal val all: Set<SitePermission>,
    internal val page: String?,
    internal val tag: Any,
    internal val grant: () -> Unit,
    internal val deny: () -> Unit,
)

/**
 * One tab's camera, microphone and location requests, Chrome's way: a site asks once, the user's
 * Allow or Block is remembered for its origin, and an allowed one gets Android's permission for
 * Helmi when it doesn't have it yet. A prompt left without an answer (back, a tap outside, the
 * page went) blocks this one request and asks again next time.
 */
class PermissionRequests(
    private val store: PermissionChoices,
    /** The address of the tab's page now: WebView's, which is the new page's once it commits. */
    private val pageUrl: () -> String?,
    private val host: () -> PermissionHost?,
) {
    private val queue = mutableStateListOf<PermissionAsk>()

    /** The prompt to show for this tab, oldest first. */
    val current: PermissionAsk? get() = queue.firstOrNull()

    /** getUserMedia: camera, microphone. Protected media (DRM video) is allowed, as Chrome on Android does. */
    fun onPermissionRequest(request: PermissionRequest) {
        val kinds = linkedSetOf<SitePermission>()
        var drm = false
        for (r in request.resources) when (r) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> kinds += SitePermission.Camera
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> kinds += SitePermission.Microphone
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> drm = true
            // MIDI with system-exclusive messages can reprogram devices: not offered.
        }
        val media = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (kinds.isEmpty()) {
            if (drm) request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) else request.deny()
            return
        }
        val origin = permissionOrigin(request.origin.toString()) ?: return request.deny()
        handle(origin, kinds, request, grant = {
            request.grant((media + listOfNotNull(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.takeIf { drm })).toTypedArray())
        }, deny = { request.deny() })
    }

    fun onPermissionRequestCanceled(request: PermissionRequest) { queue.removeAll { it.tag === request } }

    /** navigator.geolocation. WebView is told not to keep the answer: the choice is Helmi's to keep. */
    fun onGeolocationPrompt(webOrigin: String, callback: GeolocationPermissions.Callback) {
        val origin = permissionOrigin(webOrigin) ?: return callback.invoke(webOrigin, false, false)
        handle(origin, setOf(SitePermission.Location), GeoTag(webOrigin), grant = { callback.invoke(webOrigin, true, false) }, deny = { callback.invoke(webOrigin, false, false) })
    }

    fun onGeolocationHidePrompt() { queue.removeAll { it.tag is GeoTag } }

    private class GeoTag(val origin: String)

    /** The user's answer on the prompt: remembered for the origin, then the request goes on. */
    fun answer(ask: PermissionAsk, allow: Boolean) {
        if (!queue.remove(ask)) return
        ask.kinds.forEach { store.set(ask.origin, it, allow) }
        if (allow) withAndroid(ask) else ask.deny()
        // Other requests from the same site may be answered by this now.
        queue.filter { it.origin == ask.origin }.forEach { other ->
            when (decidePermission(other.all, store.choices(other.origin))) {
                PermissionDecision.Grant -> { queue.remove(other); withAndroid(other) }
                PermissionDecision.Deny -> { queue.remove(other); other.deny() }
                is PermissionDecision.Ask -> Unit
            }
        }
    }

    /** The prompt went without an answer: this request is blocked, nothing remembered. */
    fun dismiss(ask: PermissionAsk) { if (queue.remove(ask)) ask.deny() }

    /** The tab is closing, or its page went with its renderer: its requests go unanswered. */
    fun cancelAll() = cancel { true }

    /**
     * A new page started at [url]: as in Chrome, the prompts of the page before it go. Not the new
     * page's own: WebView tells of its start by a message that can come after the page has already
     * asked (a location request as it loads), and those asks were made on [url]'s origin.
     * (WebView also cancels the requests of a page that goes, which removes them here too.)
     */
    fun pageStarted(url: String) {
        val origin = permissionOrigin(url)
        cancel { it.page != origin }
    }

    private fun cancel(which: (PermissionAsk) -> Boolean) {
        val asks = queue.filter(which)
        queue.removeAll(asks)
        asks.forEach { it.deny() }
    }

    private fun handle(origin: String, kinds: Set<SitePermission>, tag: Any, grant: () -> Unit, deny: () -> Unit) {
        val page = pageUrl()?.let(::permissionOrigin)
        when (val d = decidePermission(kinds, store.choices(origin))) {
            PermissionDecision.Deny -> deny()
            PermissionDecision.Grant -> withAndroid(PermissionAsk(origin, kinds, kinds, page, tag, grant, deny))
            is PermissionDecision.Ask -> queue += PermissionAsk(origin, d.kinds, kinds, page, tag, grant, deny)
        }
    }

    private fun withAndroid(ask: PermissionAsk) {
        val h = host() ?: return ask.deny()
        h.requestAndroidPermissions(ask.all) { ok -> if (ok) ask.grant() else ask.deny() }
    }
}
