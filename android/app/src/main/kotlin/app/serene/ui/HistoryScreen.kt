package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.serene.SereneApp
import app.serene.browser.SiteData
import app.serene.browser.Visit
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * ⋮ › History: the pages visited, newest first under a heading per day, as in Chrome. Tap opens a
 * page in the tab in front, long press offers a new tab or the link; X deletes one, the bin a time
 * range. Search filters by title and URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(app: SereneApp, onBack: () -> Unit, onOpen: (url: String, newTab: Boolean) -> Unit) {
    val history = app.history
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    if (searching) BackHandler { searching = false; query = "" }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (!searching) Text("History")
                else {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search your history") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("historySearch"),
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                if (searching) {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                } else {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search history") }
                    IconButton(onClick = { clearing = true }, enabled = history.entries.isNotEmpty()) { Icon(Icons.Filled.Delete, "Delete history") }
                }
            },
        )
    }) { pad ->
        // entries is state: a visit, title or delete shows at once.
        val shown = remember(history.entries, query) { history.matching(query) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "Pages you visit show here." else "No pages match “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val rows = remember(shown) { withDays(shown, ZoneId.systemDefault()) }
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(rows, key = { r -> if (r is LocalDate) "day-$r" else (r as Visit).let { "${it.url}@${it.at}" } }) { r ->
                    if (r is Visit) HistoryRow(r, onOpen = { onOpen(r.url, it) }, onDelete = { history.delete(r) })
                    else DayHeading(r as LocalDate)
                }
            }
        }
    }
    if (clearing) ClearHistoryDialog(onDismiss = { clearing = false }) { from ->
        clearing = false
        history.deleteSince(from)
        if (from <= 0) app.stats.forgetSites()
    }
}

/** [shown] (newest first) with the day (a LocalDate) before each day's first page. */
private fun withDays(shown: List<Visit>, zone: ZoneId): List<Any> {
    val out = ArrayList<Any>(shown.size + 16)
    var day: LocalDate? = null
    for (v in shown) {
        val d = Instant.ofEpochMilli(v.at).atZone(zone).toLocalDate()
        if (d != day) { day = d; out += d }
        out += v
    }
    return out
}

@Composable
private fun DayHeading(day: LocalDate) {
    val ctx = LocalContext.current
    Text(
        dayName(ctx, day),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(v: Visit, onOpen: (newTab: Boolean) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val host = remember(v.url) { runCatching { URI(v.url).host }.getOrNull()?.removePrefix("www.").orEmpty().ifEmpty { v.url } }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(false) }, onLongClick = { menu = true }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The site's first letter in a circle: there are no favicons yet.
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(host.firstOrNull()?.uppercase() ?: "•", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$host · ${DateFormat.getTimeFormat(ctx).format(v.at)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "Delete from history") }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { menu = false; onOpen(true) })
            DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                menu = false
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", v.url))
            })
            DropdownMenuItem(text = { Text("Delete from history") }, onClick = { menu = false; onDelete() })
        }
    }
}

/** "Today", "Yesterday", or the date ("Thursday, 29 October"; with the year when not this year). */
private fun dayName(ctx: Context, day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateUtils.formatDateTime(ctx, day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE)
    }
}

/** Chrome's time ranges for deleting history. */
private val RANGES = listOf(
    "Last hour" to TimeUnit.HOURS.toMillis(1),
    "Last 24 hours" to TimeUnit.DAYS.toMillis(1),
    "Last 7 days" to TimeUnit.DAYS.toMillis(7),
    "Last 4 weeks" to TimeUnit.DAYS.toMillis(28),
    "All time" to 0L,
)

/** Picks a time range; [onDelete] gets its start (ms), 0 for all time. */
@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onDelete: (from: Long) -> Unit) {
    var picked by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete history") },
        text = {
            Column {
                RANGES.forEachIndexed { i, (name, _) ->
                    Row(Modifier.fillMaxWidth().clickable { picked = i }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == i, onClick = { picked = i })
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val span = RANGES[picked].second
                onDelete(if (span == 0L) 0L else System.currentTimeMillis() - span)
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Settings › Clear browsing data, as in Chrome: the history, cookies and site storage (which signs
 * out of sites and forgets consent choices), and the cache. Open tabs stay as they are.
 */
@Composable
fun ClearBrowsingDataDialog(app: SereneApp, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var history by rememberSaveable { mutableStateOf(true) }
    var cookies by rememberSaveable { mutableStateOf(true) }
    var cache by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                CheckRow("Browsing history", "${app.history.entries.size} pages, and the most cleaned-up sites on the new tab page.", history) { history = it }
                CheckRow("Cookies and site data", "Signs you out of most sites. Cookie banners you answered ask again.", cookies) { cookies = it }
                CheckRow("Cached images and files", "Pages load a little slower the next time.", cache) { cache = it }
            }
        },
        confirmButton = {
            TextButton(enabled = history || cookies || cache, onClick = {
                clearBrowsingData(ctx, app, history, cookies, cache)
                onDismiss()
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun clearBrowsingData(ctx: Context, app: SereneApp, history: Boolean, cookies: Boolean, cache: Boolean) {
    if (history) {
        app.history.clear()
        app.history.save()
        app.settings.clearHiddenSuggestions()
        app.stats.forgetSites()
    }
    if (cache) SiteData.clearCache(ctx)
    val toast = { Toast.makeText(ctx, "Browsing data cleared", Toast.LENGTH_SHORT).show() }
    if (cookies) SiteData.clearAll(ctx, toast) else toast()
}
