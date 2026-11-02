package app.serene.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.browser.HomeShortcut
import app.serene.browser.HomeShortcutData
import app.serene.browser.Tab
import kotlinx.coroutines.CancellationException

/**
 * Chrome's Add to home screen dialog: the icon the shortcut will have (a spinner while the site's
 * is fetched), its name to edit, and the site under it. Add hands it to the launcher, which asks
 * the user once more where to put it.
 */
@Composable
fun AddToHomeDialog(tab: Tab, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val url = remember { tab.url }
    var name by remember { mutableStateOf(TextFieldValue(tab.title.trim().ifEmpty { HomeShortcutData.site(url) }.take(HomeShortcutData.MAX_NAME))) }
    var edited by remember { mutableStateOf(false) }
    val prepared by produceState<HomeShortcut.Prepared?>(null) {
        value = try {
            HomeShortcut.prepare(tab, density)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val size = (HomeShortcut.ICON_DP * density).toInt()
            HomeShortcut.Prepared(url, name.text, HomeShortcut.letter(HomeShortcutData.monogram(url), HomeShortcutData.fallbackColor(url), size), null)
        }
    }
    // The site's own name, unless the user has started on one.
    LaunchedEffect(prepared) {
        val p = prepared ?: return@LaunchedEffect
        if (!edited) name = TextFieldValue(p.name, TextRange(p.name.length))
    }
    val ready = prepared != null && name.text.isNotBlank()
    val add = {
        val p = prepared
        if (p != null && name.text.isNotBlank()) {
            if (!HomeShortcut.pin(context, url, name.text, p.icon)) {
                Toast.makeText(context, "Your home screen doesn't take shortcuts", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to home screen") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShortcutIcon(prepared)
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.copy(text = it.text.replace('\n', ' ').take(HomeShortcutData.MAX_NAME)); edited = true },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.fillMaxWidth().testTag("shortcut-name"),
                    )
                    Text(
                        HomeShortcutData.site(url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = add, enabled = ready) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The middle of the adaptive icon, the part a launcher shows, in a rounded square like One UI's. */
@Composable
private fun ShortcutIcon(prepared: HomeShortcut.Prepared?) {
    val shown = 56.dp
    Box(Modifier.size(shown).clip(RoundedCornerShape(30)).testTag("shortcut-icon"), contentAlignment = Alignment.Center) {
        if (prepared == null) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            val bitmap = remember(prepared) { prepared.icon.asImageBitmap() }
            // The whole icon is 108 dp for 72 shown.
            Image(bitmap, contentDescription = "Shortcut icon", modifier = Modifier.requiredSize(shown * (108f / 72f)))
        }
    }
}
