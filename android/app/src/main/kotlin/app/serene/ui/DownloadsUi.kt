package app.serene.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.R
import app.serene.download.Download
import app.serene.download.DownloadState
import app.serene.download.DownloadText
import app.serene.download.Downloads

/**
 * One download, laid out like Chrome's: a progress ring with pause/resume, the name and
 * "20.39 MB / 39.58 MB • 4 secs left", then Open when done and X (cancel, or [onDismiss] once
 * it has finished).
 */
@Composable
fun DownloadRow(d: Download, downloads: Downloads, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val done = d.state == DownloadState.Done
    Row(
        modifier.fillMaxWidth().clickable(enabled = done) { downloads.open(d) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            val ring = Modifier.size(44.dp)
            val track = MaterialTheme.colorScheme.surfaceVariant
            when (d.state) {
                DownloadState.Running -> {
                    val f = d.fraction
                    if (f != null) CircularProgressIndicator(progress = { f }, modifier = ring, strokeWidth = 3.dp, trackColor = track)
                    else CircularProgressIndicator(modifier = ring, strokeWidth = 3.dp, trackColor = track)
                    IconButton(onClick = { downloads.pause(d) }) {
                        Icon(painterResource(R.drawable.ic_pause), "Pause", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DownloadState.Paused -> {
                    CircularProgressIndicator(progress = { d.fraction ?: 0f }, modifier = ring, strokeWidth = 3.dp, trackColor = track)
                    IconButton(onClick = { downloads.resume(d) }) {
                        Icon(Icons.Filled.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DownloadState.Failed -> IconButton(onClick = { downloads.resume(d) }) {
                    Icon(Icons.Filled.Refresh, "Try again", tint = MaterialTheme.colorScheme.error)
                }
                DownloadState.Done, DownloadState.Cancelled -> Icon(
                    painterResource(R.drawable.ic_file), null,
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(statusLine(d), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (done) TextButton(onClick = { downloads.open(d) }) { Text("Open") }
        IconButton(onClick = { if (d.active) downloads.cancel(d) else onDismiss() }) {
            Icon(Icons.Filled.Close, if (d.active) "Cancel download" else "Dismiss")
        }
    }
}

private fun statusLine(d: Download): String = when (d.state) {
    DownloadState.Running -> DownloadText.progress(d.received, d.total, d.bytesPerSec)
    DownloadState.Paused -> "Paused • " + DownloadText.progress(d.received, d.total, 0.0)
    DownloadState.Done -> listOf(DownloadText.size(d.received), d.host).filter { it.isNotEmpty() }.joinToString(" • ")
    DownloadState.Failed -> "Failed • " + (d.error ?: "unknown error")
    DownloadState.Cancelled -> "Cancelled"
}

/**
 * Stands in for the page in a tab that was opened for a file (Chrome's "Downloading…" screen),
 * instead of the empty WebView that drew solid black.
 */
@Composable
fun DownloadPage(tabId: Long, downloads: Downloads, onCloseTab: () -> Unit) {
    val mine = downloads.items.filter { it.tabId == tabId }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_download), null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            val heading = when {
                mine.isEmpty() -> "Nothing downloading"
                mine.any { it.state == DownloadState.Running } -> "Downloading…"
                mine.any { it.state == DownloadState.Paused } -> "Download paused"
                mine.any { it.state == DownloadState.Failed } -> "Download failed"
                mine.any { it.state == DownloadState.Done } -> "Download complete"
                else -> "Download cancelled"
            }
            Text(heading, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            mine.forEach { d -> DownloadRow(d, downloads, onDismiss = { downloads.remove(d) }) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val active = mine.filter { it.active }
                if (active.isNotEmpty()) TextButton(onClick = { active.forEach(downloads::cancel) }) { Text("Cancel") }
                else TextButton(onClick = onCloseTab) { Text("Close tab") }
            }
        }
    }
}

/** Over the bottom of a page: the download a link on it started. */
@Composable
fun DownloadBar(d: Download, downloads: Downloads, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        DownloadRow(d, downloads, onDismiss, Modifier.padding(start = 8.dp))
    }
}

/** Every download, newest first: menu → Downloads, or the download notification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(downloads: Downloads, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    // The system's view of the Downloads folder, where the files are.
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                        }
                    }) { Text("Folder") }
                },
            )
        },
    ) { pad ->
        if (downloads.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Files you download are saved in the Downloads folder and listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                items(downloads.items, key = { it.id }) { d ->
                    DownloadRow(d, downloads, onDismiss = { downloads.remove(d) })
                }
            }
        }
    }
}
