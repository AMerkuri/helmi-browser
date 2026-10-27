package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.serene.SereneApp
import app.serene.browser.PageImage
import app.serene.browser.PageImages
import app.serene.browser.Pressed
import app.serene.browser.Tab
import app.serene.browser.isWebLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The menu for a long-pressed link or picture, as Chrome's: what was pressed at the top (tap it
 * for the whole address), then what to do with it. A picture link gets both sets of items.
 */
@Composable
fun ContextMenu(app: SereneApp, tab: Tab, pressed: Pressed, onDismiss: () -> Unit, onNewTab: (String) -> Unit) {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val link = pressed.link
    val src = pressed.image
    var full by remember(link, src) { mutableStateOf(false) }
    // The picture, fetched as the menu opens: for its thumbnail, and ready to copy or share. In
    // the app's scope, so a copy or share chosen before it arrives still happens.
    // The user agent and referrer are read here, on the main thread: WebView allows no other.
    val image = remember(src) {
        src?.let {
            val ua = tab.userAgent
            val referrer = tab.referrer
            app.scope.async(Dispatchers.IO) { PageImages.fetch(context, it, ua, referrer) }
        }
    }
    val thumbPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, image) {
        value = image?.await()?.let { withContext(Dispatchers.IO) { PageImages.thumbnail(it, thumbPx) } }?.asImageBitmap()
    }
    val address = link ?: src.orEmpty()
    val title = pressed.text.ifEmpty { pressed.alt }
    val withImage = { act: (PageImage) -> Unit ->
        onDismiss()
        app.scope.launch(Dispatchers.Main) {
            val img = image?.await()
            if (img == null) Toast.makeText(context, "Helmi couldn't get this picture", Toast.LENGTH_SHORT).show() else act(img)
        }
        Unit
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp).widthIn(max = 400.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { full = !full }.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (src != null) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(56.dp)) {
                            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    } else {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                val host = Uri.parse(address).host.orEmpty().removePrefix("www.")
                                Text(host.take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        if (title.isNotEmpty()) {
                            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = if (full) 3 else 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            if (full) address else address.substringAfter("://").removePrefix("www."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (full) 6 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                if (link != null) {
                    Item("Open in new tab") { onDismiss(); onNewTab(link) }
                    Item("Copy link address") { onDismiss(); copyText(context, "Link", link, "Link copied") }
                    if (pressed.text.isNotEmpty()) Item("Copy link text") { onDismiss(); copyText(context, "Link text", pressed.text, "Text copied") }
                    Item("Share link") { onDismiss(); shareText(activity, link, pressed.text) }
                }
                if (src != null) {
                    if (link != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // Not a data: picture: the address bar can't take one.
                    if (isWebLink(src)) Item("Open image in new tab") { onDismiss(); onNewTab(src) }
                    Item("Copy image") { withImage { copyImage(context, it) } }
                    Item("Download image") { onDismiss(); tab.downloadImage(src) }
                    Item("Share image") { withImage { shareImage(activity, it, title) } }
                }
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

private fun copyText(context: Context, label: String, text: String, done: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and later show their own confirmation of a copy.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotEmpty()) send.putExtra(Intent.EXTRA_TITLE, title)
    context.startActivity(Intent.createChooser(send, null))
}

private fun imageUri(context: Context, image: PageImage): Uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", image.file)

private fun copyImage(context: Context, image: PageImage) {
    val uri = imageUri(context, image)
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
}

private fun shareImage(context: Context, image: PageImage, title: String) {
    val uri = imageUri(context, image)
    val send = Intent(Intent.ACTION_SEND).setType(image.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser's preview, and the grant passed on to the app picked.
    send.clipData = ClipData.newRawUri(title, uri)
    if (title.isNotEmpty()) send.putExtra(Intent.EXTRA_TITLE, title)
    context.startActivity(Intent.createChooser(send, null))
}
