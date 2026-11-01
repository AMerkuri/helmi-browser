package app.serene.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.serene.R
import app.serene.SereneApp
import app.serene.browser.Completion
import app.serene.browser.PageSuggestion
import app.serene.browser.Suggestions
import app.serene.browser.resolveInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * The address bar's text while it is being edited, shared by the bar and the suggestions under
 * it: [state] is the field, [original] the address the edit started from (null when the field
 * isn't focused). [completion] is the field's inline completion, as in Chrome: what was typed,
 * then the rest of a site's address, selected, so the next letter replaces it, backspace removes
 * it and Go opens it.
 */
@Stable
class AddressEdit(initial: String) {
    val state = TextFieldState(initial)
    var focused by mutableStateOf(false)
    var original by mutableStateOf<String?>(null)

    /** The last completion, or null: none, or the text changed since. */
    private var completed: Completion? = null

    /** Finds the completion for what was typed (set by the bar: it reads the history). */
    var complete: (String) -> Completion? = { null }

    /** The completion the field shows (selected or not), if the text is still what it made. */
    val completion: Completion? get() = completed?.takeIf { state.text.contentEquals(it.text) }

    /** What Go opens: the completed page (with its own scheme), or the text as typed. */
    val go: String get() = completion?.url ?: state.text.toString()

    /** The text being edited: the field's, without an inline completion still showing. */
    val typed: String
        get() {
            val text = state.text
            val sel = state.selection
            val c = completed
            return if (c != null && text.contentEquals(c.text) && !sel.collapsed && sel.max == text.length) text.substring(0, sel.min) else text.toString()
        }

    /** Something other than the address the edit started from is in the field. */
    val editing: Boolean get() = focused && original != null && !state.text.contentEquals(original)

    /** Puts [text] in the field to edit further (a suggestion's arrow), cursor at the end. */
    fun refine(text: String) {
        completed = null
        state.edit { replace(0, length, text); placeCursorAtEnd() }
    }

    /**
     * Completes the text after each letter typed at its end. Not after a delete (backspace takes
     * the completion away, as in Chrome), and not with the cursor anywhere else. A keyboard that
     * was composing a word has it committed: Compose drops the composition and restarts the input
     * when a transformation changes the text.
     */
    @OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
    val inputTransformation = InputTransformation {
        completed = null
        val changes = changes
        if (changes.changeCount == 0) return@InputTransformation
        val last = changes.getRange(changes.changeCount - 1)
        if (last.length == 0 || last.end != length || !selection.collapsed || selection.end != length) return@InputTransformation
        val text = asCharSequence().toString()
        val c = complete(text) ?: return@InputTransformation
        if (c.text.length <= text.length || !c.text.startsWith(text)) return@InputTransformation
        append(c.text.substring(text.length))
        selection = TextRange(text.length, length)
        completed = c
    }
}

/**
 * Chrome's suggestions under the address bar while you type: first what Go does (the completed
 * site, the address typed, or a search for it), then pages from the history and bookmarks, then
 * the search engine's suggestions. A tap opens one; its arrow puts it in the field to edit; a long
 * press on a page from the history offers to remove it from the history.
 */
@Composable
fun AddressSuggestions(app: SereneApp, edit: AddressEdit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val typed = edit.typed
    val query = typed.trim()
    val engine = app.settings.searchEngine
    val history = app.history.entries
    val bookmarks = app.bookmarks.items
    val now = remember(query) { System.currentTimeMillis() }
    val pages = remember(query, history, bookmarks) { Suggestions.pages(query, history, bookmarks, now) }
    val completion = edit.completion

    // The search engine's suggestions, a moment after the typing stops. The last answer stays up
    // until the next one comes, so the list doesn't flicker.
    var searches by remember { mutableStateOf(emptyList<String>()) }
    val ask = app.settings.searchSuggestions
    LaunchedEffect(query, engine, ask) {
        if (!ask || query.isEmpty() || "://" in query || query.length > 200) { searches = emptyList(); return@LaunchedEffect }
        delay(150)
        searches = withContext(Dispatchers.IO) { Suggestions.fetchSearchSuggestions(engine.suggest, query) }
    }

    var forget by remember { mutableStateOf<PageSuggestion?>(null) }

    val goesTo = completion?.url ?: resolveInput(query, engine.template)
    val first = completion?.text ?: query
    val firstIsPage = completion != null || !goesTo.startsWith(engine.template.substringBefore("%s"))
    val shownPages = pages.filter { p -> !firstIsPage || Suggestions.bare(p.url).trimEnd('/') != Suggestions.bare(goesTo).trimEnd('/') }
    val shownSearches = searches.filter { !it.equals(query, ignoreCase = true) }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        item(key = "typed") {
            if (firstIsPage) {
                SuggestionRow(
                    icon = painterResource(R.drawable.ic_globe), title = AnnotatedString(first), subtitle = null,
                    onClick = { onOpen(completion?.url ?: first) }, onRefine = { edit.refine(first) },
                )
            } else {
                SuggestionRow(
                    icon = rememberVectorPainter(Icons.Filled.Search), title = AnnotatedString(query), subtitle = "${engine.label} search",
                    onClick = { onOpen(query) }, onRefine = null,
                )
            }
        }
        items(shownPages, key = { "page " + it.url }) { p ->
            SuggestionRow(
                icon = if (p.bookmarked) rememberVectorPainter(Icons.Filled.Star) else painterResource(R.drawable.ic_history),
                title = AnnotatedString(p.label), subtitle = shortUrl(p.url),
                onClick = { onOpen(p.url) }, onRefine = { edit.refine(p.url) },
                onLongClick = if (!p.bookmarked && p.visits > 0) ({ forget = p }) else null,
            )
        }
        items(shownSearches, key = { "search $it" }) { s ->
            SuggestionRow(
                icon = rememberVectorPainter(Icons.Filled.Search), title = boldAfter(s, query), subtitle = null,
                onClick = { onOpen(searchUrl(engine.template, s)) }, onRefine = { edit.refine("$s ") },
            )
        }
    }

    forget?.let { p ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(p.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Remove this page from your history?") },
            confirmButton = { TextButton(onClick = { app.history.deleteUrl(p.url); forget = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The search for suggestion [s], as an address: a search even when it looks like an address
 * ("example.com" typed in the field would open the site).
 */
private fun searchUrl(template: String, s: String) = template.replace("%s", URLEncoder.encode(s.trim(), "UTF-8"))

/** The address as the bar shows it: no scheme, no www, no slash on the end. */
private fun shortUrl(url: String) = url.substringAfter("://").removePrefix("www.").removeSuffix("/")

/** [text] with what follows the typed [prefix] in bold, as Chrome shows search suggestions. */
private fun boldAfter(text: String, prefix: String): AnnotatedString = buildAnnotatedString {
    if (prefix.isNotEmpty() && text.startsWith(prefix, ignoreCase = true)) {
        append(text.substring(0, prefix.length))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(prefix.length)) }
    } else {
        append(text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(
    icon: Painter, title: AnnotatedString, subtitle: String?, onClick: () -> Unit, onRefine: (() -> Unit)?, onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
            )
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRefine != null) {
            IconButton(onClick = onRefine) {
                Icon(painterResource(R.drawable.ic_north_west), "Edit in the address bar", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}
