package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.serene.browser.Link

/**
 * The menu for a long-pressed link, as Chrome's: the link at the top (tap it for the whole
 * address), then what to do with it. A picture link has no text to copy.
 */
@Composable
fun LinkMenu(link: Link, onDismiss: () -> Unit, onNewTab: (Link) -> Unit) {
    val context = LocalContext.current
    var full by remember(link) { mutableStateOf(false) }
    val host = Uri.parse(link.url).host.orEmpty().removePrefix("www.")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 400.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { full = !full }.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(host.take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        if (link.text.isNotEmpty()) {
                            Text(link.text, style = MaterialTheme.typography.bodyLarge, maxLines = if (full) 3 else 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            if (full) link.url else link.url.substringAfter("://").removePrefix("www."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (full) 6 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Item("Open in new tab") { onDismiss(); onNewTab(link) }
                Item("Copy link address") { onDismiss(); copy(context, "Link", link.url, "Link copied") }
                if (link.text.isNotEmpty()) Item("Copy link text") { onDismiss(); copy(context, "Link text", link.text, "Text copied") }
                Item("Share link") { onDismiss(); share(context, link) }
            }
        }
    }
}

@Composable
private fun Item(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

private fun copy(context: Context, label: String, text: String, done: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and later show their own confirmation of a copy.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, link: Link) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url)
    if (link.text.isNotEmpty()) send.putExtra(Intent.EXTRA_TITLE, link.text)
    context.startActivity(Intent.createChooser(send, null))
}
