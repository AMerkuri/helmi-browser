package app.serene.report

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Report missed popup": what the detector sees on screen right now, what the engine did on this
 * page, a screenshot and the user's note, saved as files. The user either opens them as a
 * prefilled GitHub issue or hands the files to the share sheet. Nothing leaves the phone unless
 * the user submits or sends it.
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
        if (!seen.has(root)) { seen.add(root); const f = s ? s.extract(root) : { tag: root.localName, text: (root.innerText || '').slice(0, 300) }; if (f) { delete f._buttons; delete f._iframes; f.serene = root.getAttribute('data-serene'); f.html = root.outerHTML.replace(/\s+/g, ' ').slice(0, 1500); out.push(f); } }
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
        screenshot(tab) { shot, how -> collect(context, app, tab, dir, stamp, shot, how, done) }
    }

    private fun collect(context: Context, app: SereneApp, tab: Tab, dir: File, stamp: String, shot: Bitmap?, how: String, done: (File) -> Unit) {
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
                put("screenshot", how)
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

    /**
     * What is on screen under the WebView. View.draw() cannot render a hardware-accelerated
     * WebView into a software Canvas (it comes out blank white), so read the composited window
     * with PixelCopy, and fall back to draw() only where that is not possible. [done] gets the
     * bitmap, or null when every way came out a single colour, and how it was taken.
     */
    private fun screenshot(tab: Tab, done: (Bitmap?, String) -> Unit) {
        val v = tab.webView
        if (v.width == 0 || v.height == 0) return done(null, "none: no WebView on screen")
        val window = activityOf(v.context)?.window ?: return drawn(v, "no activity window", done)
        val xy = IntArray(2).also { v.getLocationInWindow(it) }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height), bmp, { result ->
                when {
                    result != PixelCopy.SUCCESS -> drawn(v, "pixelcopy failed ($result)", done)
                    isUniform(bmp) -> drawn(v, "pixelcopy blank ${colour(bmp)}", done)
                    else -> done(bmp, "pixelcopy")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) { // window not yet attached to a surface
            drawn(v, "pixelcopy unavailable", done)
        }
    }

    private fun drawn(v: View, why: String, done: (Bitmap?, String) -> Unit) {
        val bmp = runCatching { Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }.getOrNull()
        when {
            bmp == null -> done(null, "none: $why, draw failed")
            isUniform(bmp) -> done(null, "none: $why, draw blank ${colour(bmp)}")
            else -> done(bmp, "draw ($why)")
        }
    }

    private tailrec fun activityOf(c: Context?): Activity? = when (c) {
        is Activity -> c
        is ContextWrapper -> activityOf(c.baseContext)
        else -> null
    }

    private fun colour(b: Bitmap) = "#%08x".format(b.getPixel(b.width / 2, b.height / 2))

    /**
     * A blank capture: every pixel has the same colour. All of them, row by row: a sparse grid
     * can land only on the white of a text page and throw away a good screenshot.
     */
    fun isUniform(b: Bitmap): Boolean {
        val row = IntArray(b.width)
        val first = b.getPixel(0, 0)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (!isUniform(row, first)) return false
        }
        return true
    }

    fun isUniform(pixels: IntArray, colour: Int = pixels.firstOrNull() ?: 0): Boolean = pixels.all { it == colour }

    /**
     * The prefilled GitHub issue for this report (see [IssueReport]); [open] loads it in a tab.
     * A new-issue URL cannot carry files, so the screenshot goes to Pictures/Serene (where the
     * form's attach button finds it) and onto the clipboard, to paste into the Screenshot box.
     */
    fun openIssue(context: Context, dir: File, note: String, open: (String) -> Unit) {
        if (note.isNotBlank()) File(dir, "note.txt").writeText(note)
        val report = EngineJson.parseToJsonElement(File(dir, "report.json").readText()).jsonObject
        val shot = File(dir, "screenshot.png").takeIf { it.exists() }
        if (shot != null) {
            val saved = runCatching { saveToPictures(context, shot) }.getOrNull()
            val uri = saved ?: FileProvider.getUriForFile(context, "${context.packageName}.reports", shot)
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(context.contentResolver, "Helmi screenshot", uri))
            val where = if (saved != null) " and saved to Pictures/Helmi" else ""
            Toast.makeText(context, "Screenshot copied$where. Paste or attach it in the Screenshot box.", Toast.LENGTH_LONG).show()
        }
        open(IssueReport.url(report, note))
    }

    /** Android 10+ needs no permission to add to the shared Pictures folder. */
    private fun saveToPictures(context: Context, png: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "serene-report-${png.parentFile?.name}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Helmi")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> png.inputStream().use { it.copyTo(out) } }
        return uri
    }

    /** Adds the user's note and opens the share sheet with the JSON and the screenshot. */
    fun share(context: Context, dir: File, note: String, url: String) {
        if (note.isNotBlank()) File(dir, "note.txt").writeText(note)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.reports", it) })
        val text = buildString {
            appendLine("Helmi missed a popup on $url")
            if (note.isNotBlank()) appendLine(note)
            appendLine()
            appendLine("Attached: report.json (what the detector saw, what the engine did) and a screenshot.")
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Helmi: missed popup on ${Uri.parse(url).host ?: url}")
            putExtra(Intent.EXTRA_TEXT, text)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("report", uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send report"))
    }
}
