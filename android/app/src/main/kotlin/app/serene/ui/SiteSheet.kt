package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.BuildConfig
import app.serene.SereneApp
import app.serene.browser.SiteData
import app.serene.browser.Tab
import app.serene.engine.BlockedRequest
import app.serene.engine.CandidateRecord
import app.serene.engine.ConsentAnswer
import app.serene.engine.FilterLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.serene.engine.SiteRule

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

/** What Serene did on this page (and, a tap away, which lists blocked which requests and who answered its cookie banners), the per-site switch, undo (and hide again), and the report button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSheet(app: SereneApp, tab: Tab, onDismiss: () -> Unit, onReport: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = tab.version
    val s = tab.session
    val site = s?.site()
    val ctx = LocalContext.current
    var showBlocked by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (showBlocked && s != null) {
            BlockedRequests(s.blocked(), s.blockedRequests.get(), s.consentAnswers(), app.lists, android.net.Uri.parse(tab.url).host ?: "") { showBlocked = false }
        }
        else Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(site ?: "No page", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (site != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Helmi on this site", style = MaterialTheme.typography.titleMedium)
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
                val rejected = s.consentRejected()
                val blocked = s.blockedRequests.get()
                // Which lists blocked what, and who rejected the cookies with which rule: one tap
                // away, so the sheet stays short.
                val detail = blocked > 0 || rejected.isNotEmpty()
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = detail) { showBlocked = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$blocked ${if (blocked == 1) "request" else "requests"} blocked · ${actions.size} overlays handled" +
                            if (rejected.isNotEmpty()) " · cookies rejected (${rejected.joinToString()})" else "",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (detail) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Which lists blocked or rejected what", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                // An undo sticks for the site; this is the way back without forgetting everything else.
                val undone = s.undone()
                if (undone.isNotEmpty()) {
                    Text("Shown on this site because you pressed Undo", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    undone.forEach { rule -> UndoneRow(rule) { s.hideAgain(rule); tab.reload() } }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onReport) { Text("Report missed popup") }
                if (site != null) OutlinedButton(onClick = { app.engine.cache.clear(site); tab.reload() }) { Text("Forget this site") }
            }
            if (site != null) {
                Spacer(Modifier.height(8.dp))
                // A first visit again: the site's cookies (consent choices included), storage and Serene's rules for
                // it, except what the user put back with Undo (so a cookie banner they undid comes back for them).
                TextButton(onClick = {
                    app.engine.cache.clear(site, keepUndone = true)
                    SiteData.clear(tab.url) { tab.reload() }
                }) { Text("Clear site data and reload") }
                // The engine's ms-stamped log for this page, to paste into a bug report or a chat.
                TextButton(onClick = {
                    val text = buildString {
                        appendLine("${tab.url}")
                        appendLine("Helmi ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_SHA}) · ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                        appendLine("${s.blockedRequests.get()} requests blocked · cookies rejected: ${s.consentRejected().joinToString().ifEmpty { "no" }}")
                        s.log().forEach { appendLine(it) }
                    }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Helmi page log", text))
                    Toast.makeText(ctx, "Page log copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy page log") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Who rejected the page's cookie banners (autoconsent, naming the consent tool, or a filter list,
 * with the button its rule pressed and the rule as the list writes it), then the requests the lists
 * blocked, grouped by list, each with the rule that matched.
 */
@Composable
private fun BlockedRequests(blocked: List<BlockedRequest>, count: Int, answers: List<ConsentAnswer>, lists: FilterLists, host: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Lists in the order they first blocked something; "?" when the engine could not tell.
    val byList = blocked.groupBy { it.list ?: "?" }
    Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (count > 0) "Blocked requests" else "Cookies rejected", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (answers.isNotEmpty() && count > 0) item(key = "consent") {
                Text("Cookies rejected", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(answers) { a -> if (a.byList) ListAnswerRow(a, lists, host) else AutoconsentRow(a) }
            if (answers.isNotEmpty() && count > 0) item(key = "requests") {
                Text("Requests", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            }
            byList.forEach { (list, reqs) ->
                item(key = "list:$list") {
                    Text(
                        "${if (list == "?") "List unknown" else list} · ${reqs.size}",
                        Modifier.padding(top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(reqs) { BlockedRow(it) }
            }
            if (count > blocked.size) item {
                Text(
                    "and ${count - blocked.size} more",
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ListAnswerRow(a: ConsentAnswer, lists: FilterLists, host: String) {
    val source by produceState<FilterLists.RuleSource?>(null, a.scriptlet) {
        value = a.scriptlet?.let { withContext(Dispatchers.IO) { runCatching { lists.findScriptlet(it, host) }.getOrNull() } }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(source?.list ?: "Filter list", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("Pressed “${a.button?.ifBlank { null } ?: "a button"}” in the cookie banner", style = MaterialTheme.typography.bodyMedium)
        (source?.rule ?: a.scriptlet)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AutoconsentRow(a: ConsentAnswer) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Autoconsent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("Opted out in ${a.cmp ?: "the consent tool"}'s own dialog, with DuckDuckGo's rule for it", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BlockedRow(req: BlockedRequest) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(req.url.substringAfter("://"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(req.type, req.rule).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun UndoneRow(rule: SiteRule, onHideAgain: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LABELS[rule.was ?: "content"] ?: rule.was ?: "Page element", style = MaterialTheme.typography.bodyMedium)
            Text(
                rule.sig, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onHideAgain) { Text("Hide again") }
    }
}
