package app.serene.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import app.serene.BuildConfig
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.engine.CandidateRecord
import app.serene.engine.EngineJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Report missed popup": what the detector sees on screen right now, what the engine did on this
 * page, a screenshot and the user's note, saved as files and handed to the share sheet. Nothing
 * leaves the phone unless the user sends it.
 */
object Reporter {
    // Same probe as residualOverlays() in engine/harness/browser.mjs: the content script's own
    // detector, run read-only over the viewport.
    private const val SNAPSHOT_JS = """(() => {
  const s = window.__serene;
  const out = [], seen = new Set();
  for (const fx of [0.15, 0.5, 0.85]) for (const fy of [0.03, 0.2, 0.4, 0.6, 0.8, 0.97]) {
    let stack = [];
    try { stack = document.elementsFromPoint(innerWidth * fx, innerHeight * fy).slice(0, 6); } catch (e) {}
    for (const el of stack) {
      if (el === document.documentElement || el === document.body) break;
      let root = null;
      for (let n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null) {
        const p = getComputedStyle(n).position;
        if (p === 'fixed' || p === 'sticky') root = n;
      }
      if (root) {
        if (!seen.has(root)) { seen.add(root); const f = s ? s.extract(root) : { tag: root.localName, text: (root.innerText || '').slice(0, 300) }; if (f) { delete f._buttons; delete f._iframes; f.serene = root.getAttribute('data-serene'); out.push(f); } }
        break;
      }
    }
  }
  const h = getComputedStyle(document.documentElement), b = document.body ? getComputedStyle(document.body) : h;
  const locked = h.overflow === 'hidden' || h.overflowY === 'hidden' || b.overflow === 'hidden' || b.overflowY === 'hidden' || b.position === 'fixed';
  return JSON.stringify({ engineStarted: !!(s && s.state.started), overlays: out, scrollLocked: locked, scanMs: s ? s.state.scanMs : [], href: location.href, viewport: [innerWidth, innerHeight] });
})()"""

    /** Collects the page state. [done] gets the report directory on the main thread. */
    fun capture(context: Context, app: SereneApp, tab: Tab, done: (File) -> Unit) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "reports/$stamp").apply { mkdirs() }
        val shot = screenshot(tab)
        tab.webView.evaluateJavascript(SNAPSHOT_JS) { raw ->
            val snapshot: JsonElement = runCatching {
                // evaluateJavascript hands back the returned string JSON-encoded.
                EngineJson.parseToJsonElement(EngineJson.decodeFromString<String>(raw))
            }.getOrDefault(JsonNull)
            val session = tab.session
            val report = buildJsonObject {
                put("kind", "missed-popup")
                put("time", stamp)
                put("url", tab.url)
                put("site", session?.site())
                put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                put("webview", WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
                put("documentStartScript", Tab.documentStartSupported)
                put("engineEnabled", app.settings.engineEnabled)
                put("siteEnabled", session?.enabled)
                put("rules", app.rulesStatus)
                put("model", app.models.state.toString())
                put("modelId", app.settings.modelId)
                put("blockedRequests", session?.blockedRequests?.get() ?: 0)
                put("consentRejected", JsonArray((session?.consentRejected() ?: emptyList()).map(::JsonPrimitive)))
                put("snapshot", snapshot)
                put("candidates", EngineJson.encodeToJsonElement(ListSerializer(CandidateRecord.serializer()), session?.candidates() ?: emptyList()))
                put("log", JsonArray((session?.log() ?: emptyList()).map(::JsonPrimitive)))
            }
            File(dir, "report.json").writeText(EngineJson.encodeToString(JsonElement.serializer(), report))
            shot?.let { b -> File(dir, "screenshot.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            done(dir)
        }
    }

    private fun screenshot(tab: Tab): Bitmap? = runCatching {
        val v = tab.webView
        if (v.width == 0 || v.height == 0) return null
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }.getOrNull()

    /** Adds the user's note and opens the share sheet with the JSON and the screenshot. */
    fun share(context: Context, dir: File, note: String, url: String) {
        if (note.isNotBlank()) File(dir, "note.txt").writeText(note)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.reports", it) })
        val text = buildString {
            appendLine("Serene missed a popup on $url")
            if (note.isNotBlank()) appendLine(note)
            appendLine()
            appendLine("Attached: report.json (what the detector saw, what the engine did) and a screenshot.")
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Serene: missed popup on ${Uri.parse(url).host ?: url}")
            putExtra(Intent.EXTRA_TEXT, text)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("report", uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send report"))
    }
}
