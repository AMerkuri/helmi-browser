package app.serene.download

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

enum class DownloadState { Running, Paused, Done, Failed, Cancelled }

/** One file download. Its fields are Compose state and only change on the main thread. */
class Download internal constructor(
    val id: Long,
    val url: String,
    /** The tab it started in, so that tab can show it; null after a restart. */
    val tabId: Long?,
    internal val userAgent: String,
    internal val referer: String?,
    name: String,
    mimeType: String,
) {
    var name by mutableStateOf(name)
        internal set
    var mimeType by mutableStateOf(mimeType)
        internal set
    var state by mutableStateOf(DownloadState.Running)
        internal set
    var received by mutableLongStateOf(0L)
        internal set
    /** -1 when the server did not say. */
    var total by mutableLongStateOf(-1L)
        internal set
    var bytesPerSec by mutableDoubleStateOf(0.0)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** The file: a MediaStore Downloads row (Android 10+) or a FileProvider URI. */
    var uri by mutableStateOf<Uri?>(null)
        internal set

    internal var file: File? = null // before Android 10 only
    internal var validator: String? = null // ETag or Last-Modified, sent as If-Range on resume
    internal var job: Job? = null
    @Volatile internal var connection: HttpURLConnection? = null

    val host: String get() = if (url.startsWith("http")) Uri.parse(url).host?.removePrefix("www.") ?: "" else ""
    val fraction: Float? get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
    val active: Boolean get() = state == DownloadState.Running || state == DownloadState.Paused
}

/**
 * Downloads from pages. Serene fetches the file itself rather than handing it to the system
 * DownloadManager, so it can pause (an HTTP Range request on resume) and show progress in the
 * tab. Each request, and each redirect hop, carries the WebView's cookies for that URL only:
 * signed-in GitHub artifact links work, and GitHub's cookies don't follow the redirect to the
 * storage host. Files go to the shared Downloads folder. DownloadService keeps the process alive
 * and shows a notification while anything is running.
 */
class Downloads(private val context: Context) {
    val items = mutableStateListOf<Download>()
    val running: List<Download> get() = items.filter { it.state == DownloadState.Running }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolver get() = context.contentResolver
    private val savedFile = File(context.filesDir, "downloads.json")
    private var nextId = 1L

    init { load() }

    fun start(url: String, userAgent: String, contentDisposition: String?, mimeType: String?, referer: String?, tabId: Long?): Download {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            .ifEmpty { if (url.startsWith("data:")) url.removePrefix("data:").substringBefore(',').substringBefore(';') else "" }
        val name = DownloadText.fileName(url, contentDisposition, mime, ::extensionFor)
        val d = Download(nextId++, url, tabId, userAgent, referer, name, mime)
        items.add(0, d)
        run(d)
        return d
    }

    fun pause(d: Download) {
        if (d.state != DownloadState.Running) return
        d.state = DownloadState.Paused
        d.bytesPerSec = 0.0
        stop(d)
        save()
    }

    fun pauseAll() = running.forEach(::pause)

    /** Resumes a paused download, or retries a failed one from where it stopped. */
    fun resume(d: Download) {
        if (d.state == DownloadState.Paused || d.state == DownloadState.Failed) run(d)
    }

    fun cancel(d: Download) {
        if (!d.active && d.state != DownloadState.Failed) return
        d.state = DownloadState.Cancelled
        d.bytesPerSec = 0.0
        val job = stop(d)
        scope.launch {
            job?.join()
            deleteFile(d)
            save()
        }
    }

    /** Takes it off the list. A finished file stays in Downloads; a partial one is deleted. */
    fun remove(d: Download) {
        if (d.active) cancel(d)
        else if (d.state == DownloadState.Failed) scope.launch { deleteFile(d) }
        items.remove(d)
        save()
    }

    /** Opens a finished file in the app the system picks for its type. */
    fun open(d: Download): Boolean {
        val uri = d.uri ?: return false
        val intent = viewIntent(d, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone opens ${d.name.substringAfterLast('.', "this kind of").lowercase()} files", Toast.LENGTH_SHORT).show()
            false
        } catch (e: SecurityException) {
            Toast.makeText(context, "The file was moved or deleted", Toast.LENGTH_SHORT).show()
            false
        }
    }

    internal fun viewIntent(d: Download, uri: Uri): Intent {
        val type = extensionMime(d.name) ?: d.mimeType.ifEmpty { "application/octet-stream" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun run(d: Download) {
        d.state = DownloadState.Running
        d.error = null
        d.bytesPerSec = 0.0
        DownloadService.start(context)
        val previous = d.job
        d.job = scope.launch {
            // A paused transfer may still be closing its connection and file.
            previous?.join()
            val me = coroutineContext.job
            try {
                withContext(Dispatchers.IO) { transfer(d) }
                d.state = DownloadState.Done
                d.bytesPerSec = 0.0
                d.uri?.let { DownloadService.notifyDone(context, d, viewIntent(d, it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pause and cancel close the connection, which surfaces here as an IOException.
                if (d.state == DownloadState.Running && d.job === me) {
                    Log.w(TAG, "download ${d.id} failed", e)
                    d.state = DownloadState.Failed
                    d.bytesPerSec = 0.0
                    d.error = e.message ?: e.javaClass.simpleName
                }
            } finally {
                if (d.job === me) d.connection = null
                save()
            }
        }
    }

    private fun stop(d: Download): Job? {
        val job = d.job
        job?.cancel()
        // A blocked read only ends when the socket closes. Not on the main thread: it may do I/O.
        d.connection?.let { c -> scope.launch(Dispatchers.IO) { runCatching { c.disconnect() } } }
        return job
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    // ---- the transfer, on Dispatchers.IO ----

    private suspend fun transfer(d: Download) {
        if (d.url.startsWith("data:")) return writeData(d)
        val start = d.received
        val conn = connect(d, start)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("the server answered HTTP $code")
            // 206 continues the partial file; 200 means the server sent the whole file again.
            val contentRange = conn.getHeaderField("Content-Range")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL && start > 0
            if (resumed) {
                val at = contentRange?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (at != null && at != start) throw IOException("the server resumed at the wrong place")
            }
            if (d.uri == null) {
                // The server's own name and type, when the link didn't carry them.
                val cd = conn.getHeaderField("Content-Disposition")
                val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                ui {
                    if (!type.isNullOrEmpty() && (d.mimeType.isEmpty() || d.mimeType == "application/octet-stream")) d.mimeType = type
                    if (DownloadText.fromContentDisposition(cd) != null) d.name = DownloadText.fileName(d.url, cd, d.mimeType, ::extensionFor)
                }
                createTarget(d)
            }
            val length = conn.contentLengthLong
            val total = when {
                code == HttpURLConnection.HTTP_PARTIAL ->
                    contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: if (length >= 0) length + start else -1
                else -> length
            }
            val from = if (resumed) start else 0L
            d.validator = conn.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: conn.getHeaderField("Last-Modified")
            ui { d.total = total; d.received = from }
            val got = openChannel(d, from).use { ch ->
                ch.truncate(from)
                ch.position(from)
                conn.inputStream.use { copy(it, ch, d, from) }
            }
            if (total >= 0 && got < total) throw IOException("the connection closed early")
            finishTarget(d)
        } finally {
            conn.disconnect()
        }
    }

    /** Follows redirects itself, so each hop gets only its own cookies. */
    private fun connect(d: Download, start: Long): HttpURLConnection {
        val cookies = CookieManager.getInstance()
        var url = URL(d.url)
        repeat(MAX_REDIRECTS) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", d.userAgent)
                // Identity, so Content-Length is the file's size and Range offsets are file offsets.
                setRequestProperty("Accept-Encoding", "identity")
                d.referer?.let { setRequestProperty("Referer", it) }
                cookies.getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
                if (start > 0) {
                    setRequestProperty("Range", "bytes=$start-")
                    d.validator?.let { setRequestProperty("If-Range", it) }
                }
            }
            d.connection = c
            val code = c.responseCode
            c.headerFields.filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
                .forEach { cookies.setCookie(url.toString(), it) }
            val location = c.getHeaderField("Location")
            if (code !in 300..399 || code == HttpURLConnection.HTTP_NOT_MODIFIED || location == null) return c
            c.disconnect()
            url = URL(url, location)
            if (url.protocol != "https" && url.protocol != "http") throw IOException("redirected to a ${url.protocol}: link")
        }
        throw IOException("too many redirects")
    }

    private suspend fun copy(input: InputStream, ch: FileChannel, d: Download, from: Long): Long {
        val buf = ByteArray(64 * 1024)
        var got = from
        var lastAt = SystemClock.elapsedRealtime()
        var lastBytes = from
        var rate = 0.0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            val bb = ByteBuffer.wrap(buf, 0, n)
            while (bb.hasRemaining()) ch.write(bb)
            got += n
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt >= 300) {
                // Smoothed, so "secs left" doesn't jump around.
                val inst = (got - lastBytes) * 1000.0 / (now - lastAt)
                rate = if (rate == 0.0) inst else 0.8 * rate + 0.2 * inst
                lastAt = now
                lastBytes = got
                val g = got
                val r = rate
                ui { d.received = g; d.bytesPerSec = r }
            }
        }
        val g = got
        ui { d.received = g }
        return got
    }

    /** data: links: the file is in the URL (blob: links arrive here as data: from the tab). */
    private suspend fun writeData(d: Download) {
        val header = d.url.substringBefore(',')
        val payload = d.url.substringAfter(',', "")
        val bytes = if (header.endsWith(";base64", ignoreCase = true)) Base64.decode(payload, Base64.DEFAULT)
        else URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        createTarget(d)
        ui { d.total = bytes.size.toLong() }
        openChannel(d, 0).use { ch ->
            ch.truncate(0)
            val bb = ByteBuffer.wrap(bytes)
            while (bb.hasRemaining()) ch.write(bb)
        }
        ui { d.received = bytes.size.toLong() }
        finishTarget(d)
    }

    private suspend fun createTarget(d: Download) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, d.name)
                // The type the name implies, so MediaStore doesn't add a second extension.
                put(MediaStore.MediaColumns.MIME_TYPE, extensionMime(d.name) ?: d.mimeType.ifEmpty { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("could not create the file in Downloads")
            // MediaStore renames on a clash ("x (1).zip"); show the name the file really has.
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ui { d.uri = uri; if (actual != null) d.name = actual }
        } else {
            val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")).apply { mkdirs() }
            val base = d.name.substringBeforeLast('.')
            val ext = d.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var f = File(dir, d.name)
            var i = 1
            while (!f.createNewFile()) f = File(dir, "$base (${i++})$ext")
            d.file = f
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", f)
            ui { d.uri = uri; d.name = f.name }
        }
    }

    private suspend fun openChannel(d: Download, from: Long): FileChannel {
        try {
            d.file?.let { return RandomAccessFile(it, "rw").channel }
            val pfd = resolver.openFileDescriptor(d.uri ?: throw IOException("no file"), "rw") ?: throw IOException("could not open the file")
            // Closing the channel closes the stream, which closes the descriptor.
            return ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
        } catch (e: FileNotFoundException) {
            // The partial file was deleted while paused: start over in a new one.
            ui { d.uri = null; d.received = 0 }
            d.file = null
            d.validator = null
            if (from > 0) throw IOException("the partial file is gone, try again")
            createTarget(d)
            return openChannel(d, 0)
        }
    }

    private fun finishTarget(d: Download) {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = d.uri ?: return
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private suspend fun deleteFile(d: Download) {
        val uri = d.uri
        val file = d.file
        withContext(Dispatchers.IO) {
            runCatching {
                if (file != null) file.delete() else if (uri != null) resolver.delete(uri, null, null)
            }.onFailure { Log.w(TAG, "could not delete ${d.name}: ${it.message}") }
        }
        d.uri = null
        d.file = null
        d.received = 0
        d.validator = null
    }

    // ---- the list survives restarts ----

    @Serializable
    private class Saved(
        val id: Long, val url: String, val name: String, val mime: String, val state: String,
        val received: Long, val total: Long, val uri: String?, val file: String?, val validator: String?,
        val userAgent: String, val referer: String?,
    )

    private fun save() {
        val list = items.filter { it.state != DownloadState.Cancelled }.take(MAX_SAVED).map { d ->
            Saved(
                id = d.id,
                // A data: link can be megabytes; once written, the file is all that matters.
                url = if (d.url.startsWith("data:")) "data:" else d.url,
                name = d.name, mime = d.mimeType,
                // A download running when the app dies resumes as paused.
                state = (if (d.state == DownloadState.Running) DownloadState.Paused else d.state).name,
                received = d.received, total = d.total, uri = d.uri?.toString(), file = d.file?.path,
                validator = d.validator, userAgent = d.userAgent, referer = d.referer,
            )
        }
        scope.launch(Dispatchers.IO) {
            runCatching { savedFile.writeText(json.encodeToString(ListSerializer(Saved.serializer()), list)) }
                .onFailure { Log.w(TAG, "could not save downloads: ${it.message}") }
        }
    }

    private fun load() {
        val list = runCatching { json.decodeFromString(ListSerializer(Saved.serializer()), savedFile.readText()) }.getOrNull() ?: return
        list.forEach { s ->
            val state = runCatching { DownloadState.valueOf(s.state) }.getOrDefault(DownloadState.Failed)
            items += Download(s.id, s.url, null, s.userAgent, s.referer, s.name, s.mime).apply {
                // A data: link's partial file can't be resumed: its data is gone.
                this.state = if (state != DownloadState.Done && s.url == "data:") DownloadState.Failed else state
                received = s.received
                total = s.total
                uri = s.uri?.let(Uri::parse)
                file = s.file?.let(::File)
                validator = s.validator
            }
        }
        nextId = (list.maxOfOrNull { it.id } ?: 0) + 1
    }

    companion object {
        private const val TAG = "SereneDownloads"
        private const val MAX_REDIRECTS = 20
        private const val MAX_SAVED = 200
        private val json = Json { ignoreUnknownKeys = true }

        fun extensionFor(mime: String): String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        fun extensionMime(name: String): String? =
            name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }
}
