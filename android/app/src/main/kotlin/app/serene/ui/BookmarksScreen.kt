package app.serene.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.serene.bookmarks.Bookmark
import app.serene.bookmarks.Bookmarks
import app.serene.bookmarks.BookmarksHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * ⋮ › Bookmarks: every saved page, newest first, with a search over titles and addresses. A tap
 * opens it in this tab; its ⋮ (or a long press) opens it in a new tab, edits or deletes it.
 * The bar's ⋮ imports and exports the bookmarks.html file other browsers use.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(bookmarks: Bookmarks, onOpen: (url: String, newTab: Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun tell(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var barMenu by remember { mutableStateOf(false) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { BookmarksHtml.parse(it.bufferedReader().readText()) } }.getOrNull()
            }
            if (entries == null) return@launch tell("Couldn't read that file")
            if (entries.isEmpty()) return@launch tell("No bookmarks in that file")
            val r = bookmarks.import(entries)
            tell(
                when {
                    r.added == 0 -> "All ${r.skipped} were already saved"
                    r.skipped == 0 -> "Imported ${count(r.added)}"
                    else -> "Imported ${count(r.added)}, ${r.skipped} already saved or not web pages"
                },
            )
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val items = bookmarks.items
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(BookmarksHtml.export(items).toByteArray()) } != null }.getOrDefault(false)
            }
            tell(if (ok) "Exported ${count(items.size)}" else "Couldn't write that file")
        }
    }

    fun delete(b: Bookmark) {
        val at = bookmarks.items.indexOfFirst { it.id == b.id }
        bookmarks.remove(b.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Deleted ${b.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) bookmarks.restore(b, at)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { barMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = barMenu, onDismissRequest = { barMenu = false }) {
                            // Chrome and Firefox save these as text/html; some file managers say octet-stream.
                            DropdownMenuItem(text = { Text("Import from file") }, onClick = {
                                barMenu = false
                                importFile.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream", "text/plain"))
                            })
                            DropdownMenuItem(text = { Text("Export to file") }, enabled = bookmarks.items.isNotEmpty(), onClick = {
                                barMenu = false
                                exportFile.launch("bookmarks-${LocalDate.now()}.html")
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val all = bookmarks.items
        if (all.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Pages you save with ⋮ › Bookmark are listed here. To bring bookmarks from another browser, export them there to a file and pick ⋮ › Import from file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search bookmarks") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val shown = remember(all, query) { if (query.isBlank()) all else bookmarks.search(query) }
            if (shown.isEmpty()) {
                Text(
                    "No bookmarks match “${query.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { b ->
                    BookmarkRow(
                        b,
                        onOpen = { onOpen(b.url, false) },
                        onNewTab = { onOpen(b.url, true) },
                        onEdit = { editing = b },
                        onDelete = { delete(b) },
                    )
                }
            }
        }
    }

    editing?.let { b ->
        EditBookmarkDialog(b, onDismiss = { editing = null }, onSave = { title, url -> bookmarks.edit(b.id, title, url); editing = null })
    }
}

private fun count(n: Int) = if (n == 1) "1 bookmark" else "$n bookmarks"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(b: Bookmark, onOpen: () -> Unit, onNewTab: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val address = b.url.substringAfter("://").removePrefix("www.").removeSuffix("/")
    ListItem(
        headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val where = if (b.folder.isEmpty()) address else b.folder.joinToString(" › ") + " · " + address
            Text(where, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            // The site's first letter, as Chrome shows for a site without an icon.
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        address.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Bookmark options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { menu = false; onNewTab() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    )
}

@Composable
private fun EditBookmarkDialog(b: Bookmark, onDismiss: () -> Unit, onSave: (title: String, url: String) -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var url by remember { mutableStateOf(b.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit bookmark") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, url) }, enabled = url.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
