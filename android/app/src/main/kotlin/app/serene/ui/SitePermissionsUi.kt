package app.serene.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.serene.browser.PermissionAsk
import app.serene.browser.SitePermission
import app.serene.browser.SitePermissions
import app.serene.browser.originLabel

/** "camera and microphone", "location". */
internal fun permissionNouns(kinds: Collection<SitePermission>) = kinds.sortedBy { it.ordinal }.map { it.noun }.let {
    if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " and " + it.last()
}

/**
 * Chrome's permission prompt: the site, what it wants, Block or Allow, remembered for the site.
 * Back or a tap outside blocks only this request, so the site can ask again.
 */
@Composable
fun PermissionDialog(ask: PermissionAsk, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val what = when {
        ask.kinds == setOf(SitePermission.Location) -> "know your location"
        else -> "use your ${permissionNouns(ask.kinds)}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${originLabel(ask.origin)} wants to $what") },
        text = { Text("Helmi remembers your answer for this site. You can change it in the site sheet (the shield) or in Settings › Site permissions.") },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Block") } },
    )
}

/** Android itself blocks Helmi from [kinds] and will show no dialog: the way to Helmi's app settings. */
@Composable
fun AndroidBlockedDialog(kinds: Set<SitePermission>, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val nouns = permissionNouns(kinds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android blocks Helmi's ${nouns}") },
        text = { Text("You allowed this site, but Helmi itself has no permission for the $nouns. Turn it on under Permissions in Helmi's app settings, then try again on the page.") },
        confirmButton = { TextButton(onClick = onSettings) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * The site sheet's permissions: one switch per choice the user made on [site]'s origins, as in
 * Chrome's page info, and Reset to be asked again. Nothing when there are none.
 */
@Composable
fun SitePermissionRows(store: SitePermissions, site: String, pageOrigin: String?) {
    val origins = store.forSite(site)
    if (origins.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Permissions", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { store.resetSite(site) }) { Text("Reset") }
    }
    origins.entries.sortedBy { it.key != pageOrigin }.forEach { (origin, choices) ->
        choices.entries.sortedBy { it.key.ordinal }.forEach { (kind, allowed) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (if (allowed) "Allowed" else "Blocked") + if (origin != pageOrigin) " · ${originLabel(origin)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowed, onCheckedChange = { store.set(origin, kind, it) })
            }
        }
    }
}

/** Settings › Site permissions: every site's choices, each with a way to be asked again. */
@Composable
fun SitePermissionsSection(store: SitePermissions) {
    val all = store.all
    if (all.isEmpty()) return
    Section("Site permissions")
    all.entries.sortedBy { originLabel(it.key) }.forEach { (origin, choices) ->
        ListItem(
            headlineContent = { Text(originLabel(origin)) },
            supportingContent = {
                Text(choices.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { (k, v) -> "${k.label} ${if (v) "allowed" else "blocked"}" })
            },
            trailingContent = {
                IconButton(onClick = { choices.keys.forEach { store.reset(origin, it) } }) { Icon(Icons.Filled.Close, "Ask again on ${originLabel(origin)}") }
            },
        )
    }
    ListItem(
        headlineContent = { Text("Reset all site permissions") },
        trailingContent = { TextButton(onClick = store::resetAll) { Text("Reset") } },
    )
}
