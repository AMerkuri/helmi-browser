package app.serene.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.ModelCatalog
import app.serene.ModelSpec
import app.serene.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads the model after install (never bundled in the APK) with the system DownloadManager,
 * which survives the app being killed and shows its own notification, then checks the SHA-256
 * from Hugging Face before the file counts as ready.
 */
class ModelManager(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    private var pollJob: Job? = null

    var state by mutableStateOf<ModelState>(ModelState.NotDownloaded)
        private set

    val spec: ModelSpec get() = ModelCatalog.byId(settings.modelId)

    fun file(spec: ModelSpec = this.spec) = File(dir, spec.file)
    private fun partFile(spec: ModelSpec) = File(dir, spec.file + ".download")

    /** The model file when it is downloaded and verified. */
    fun readyFile(): File? = file().takeIf { state == ModelState.Ready && it.exists() }

    fun refresh() {
        val f = file()
        state = when {
            f.exists() && f.length() == spec.sizeBytes -> ModelState.Ready
            settings.modelDownloadId >= 0 -> ModelState.Downloading(0, spec.sizeBytes).also { poll() }
            else -> ModelState.NotDownloaded
        }
    }

    fun download() {
        val s = spec
        dir.mkdirs()
        partFile(s).delete()
        val req = DownloadManager.Request(Uri.parse(s.url))
            .setTitle("Serene model: ${s.name}")
            .setDescription("On-device model for the annoyance blocker")
            .setDestinationUri(Uri.fromFile(partFile(s)))
            .setAllowedOverMetered(!settings.wifiOnlyDownloads)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        settings.modelDownloadId = dm.enqueue(req)
        state = ModelState.Downloading(0, s.sizeBytes)
        poll()
    }

    /** Copies a model file the user downloaded themselves (a gated one), then checks it like a download. */
    fun import(uri: Uri) {
        val s = spec
        if (state is ModelState.Downloading) cancel()
        dir.mkdirs()
        state = ModelState.Verifying
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input -> partFile(s).outputStream().use { input.copyTo(it, 1 shl 20) } }
                }.isSuccess
            }
            if (copied) verify() else { partFile(s).delete(); state = ModelState.Failed("could not read the file") }
        }
    }

    fun cancel() {
        val id = settings.modelDownloadId
        if (id >= 0) dm.remove(id)
        settings.modelDownloadId = -1
        pollJob?.cancel()
        partFile(spec).delete()
        state = ModelState.NotDownloaded
    }

    fun delete() {
        cancel()
        ModelCatalog.all.forEach { file(it).delete() }
        state = ModelState.NotDownloaded
    }

    /** Switching models keeps the other file on disk; only the selected one is used. */
    fun select(id: String) {
        if (id == settings.modelId) return
        if (state is ModelState.Downloading) cancel()
        settings.modelId = id
        refresh()
    }

    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val id = settings.modelDownloadId
                if (id < 0) return@launch
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                val row = c?.use { cur ->
                    if (!cur.moveToFirst()) null
                    else Triple(
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    )
                }
                when {
                    row == null -> { settings.modelDownloadId = -1; refreshAfterLostDownload(); return@launch }
                    row.first == DownloadManager.STATUS_SUCCESSFUL -> { settings.modelDownloadId = -1; verify(); return@launch }
                    row.first == DownloadManager.STATUS_FAILED -> {
                        settings.modelDownloadId = -1
                        state = ModelState.Failed("download failed (reason ${row.third})")
                        return@launch
                    }
                    else -> state = ModelState.Downloading(row.second, spec.sizeBytes)
                }
                delay(1000)
            }
        }
    }

    private fun refreshAfterLostDownload() {
        state = if (file().exists()) ModelState.Ready else ModelState.NotDownloaded
    }

    private suspend fun verify() {
        val s = spec
        state = ModelState.Verifying
        var problem = "checksum mismatch, try again"
        val ok = withContext(Dispatchers.IO) {
            val part = partFile(s)
            if (!part.exists() || part.length() != s.sizeBytes) {
                if (part.exists()) problem = "wrong file: ${part.length()} bytes, expected ${s.sizeBytes}"
                return@withContext false
            }
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != s.sha256 && s.gatedPage != null) problem = "checksum mismatch: the file's SHA-256 is $hex"
            hex == s.sha256 && part.renameTo(file(s))
        }
        if (!ok) partFile(s).delete()
        state = if (ok) ModelState.Ready else ModelState.Failed(problem)
    }
}
