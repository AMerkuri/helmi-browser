package app.serene.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.engine.CandidateRecord

private val LABELS = mapOf(
    "cookie_consent" to "Cookie banner",
    "newsletter" to "Newsletter pop-up",
    "notification_prompt" to "Notification prompt",
    "paywall_teaser" to "Paywall overlay",
    "app_install" to "Open-in-app banner",
    "login_wall" to "Sign-in wall",
    "chat_widget" to "Chat widget",
    "survey" to "Survey",
    "content" to "Page element",
)

private val SOURCES = mapOf("heuristic" to "heuristics", "llm" to "on-device model", "cache" to "remembered", "chrome" to "page chrome")

/** What Serene did on this page, the per-site switch, undo, and the report button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSheet(app: SereneApp, tab: Tab, onDismiss: () -> Unit, onReport: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = tab.version
    val s = tab.session
    val site = s?.site()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(site ?: "No page", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (site != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Serene on this site", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!app.settings.engineEnabled) "Turned off for all sites in settings" else "Off: the page loads exactly as the site sends it",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = app.settings.isSiteEnabled(site),
                        enabled = app.settings.engineEnabled,
                        onCheckedChange = { app.settings.setSiteEnabled(site, it); tab.reload() },
                    )
                }
                Spacer(Modifier.height(16.dp))
                val actions = s.actions()
                Text(
                    "${s.blockedRequests.get()} requests blocked · ${actions.size} overlays handled",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                if (actions.isEmpty()) {
                    Text("No overlays handled on this page.", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height((72 * actions.size.coerceAtMost(4)).dp)) {
                        items(actions, key = { it.id }) { rec -> ActionRow(rec) { s.undo(rec); tab.reload() } }
                    }
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onReport) { Text("Report missed popup") }
                if (site != null) OutlinedButton(onClick = { app.engine.cache.clear(site); tab.reload() }) { Text("Forget this site") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionRow(rec: CandidateRecord, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val what = when (rec.action) {
                "click" -> "pressed “${rec.buttonLabel ?: "close"}”"
                "hide" -> "hidden"
                else -> rec.action ?: ""
            }
            Text("${LABELS[rec.label] ?: rec.label ?: "Overlay"}: $what", style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(SOURCES[rec.source] ?: rec.source, rec.features.text.take(60).takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}
