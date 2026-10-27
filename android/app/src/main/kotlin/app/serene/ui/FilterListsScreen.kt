package app.serene.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.serene.SereneApp
import app.serene.engine.FilterLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings › Filter lists: the catalog's switches (FilterLists.CATALOG), regional ad lists
 * suggested by the phone's languages, the user's own lists by URL, and their own rules. Every
 * change reloads the rules; downloads happen then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterListsScreen(app: SereneApp, onBack: () -> Unit) {
    val s = app.settings
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    // status() reads the list files, so off the main thread; .value, not by, so st smart-casts.
    val st = produceState<FilterLists.Status?>(null, tick, s.listSelection) { value = withContext(Dispatchers.IO) { app.lists.status() } }.value
    val languages = remember { LocaleListCompat.getDefault().let { l -> (0 until l.size()).mapNotNull { l[it]?.language } } }
    val suggested = remember(languages) { FilterLists.suggestedRegional(languages).toSet() }
    var adding by remember { mutableStateOf(false) }
    var editingFilters by remember { mutableStateOf(false) }

    fun save(enabled: Set<String> = s.listsEnabled, custom: List<String> = s.customLists, myFilters: String = s.myFilters) {
        s.listsEnabled = enabled
        s.customLists = custom
        s.myFilters = myFilters
        app.loadRules(force = false)
    }

    fun toggle(e: FilterLists.Entry, on: Boolean) {
        var enabled = if (on) s.listsEnabled + e.id else s.listsEnabled - e.id
        // Ads on for the first time: the regional lists for the phone's languages come with it.
        if (e.id == "ads" && on && FilterLists.CATALOG.none { it.section == FilterLists.REGIONAL && it.id in enabled }) enabled = enabled + suggested
        save(enabled = enabled)
    }

    /** "Cookie notices … · 20,558 rules", or the list's download problem. */
    fun summary(e: FilterLists.Entry): String {
        val lists = e.lists.mapNotNull { st?.lists?.get(it.name) }
        val error = lists.firstNotNullOfOrNull { it.error }
        val rules = lists.sumOf { it.rules }
        return e.summary + when {
            e.id !in s.listsEnabled -> ""
            error != null && rules == 0 -> "\nCould not download: $error"
            rules > 0 -> " · ${"%,d".format(rules)} rules"
            else -> " · downloading"
        } + (if (e.id in suggested) "\nSuggested for your languages" else "")
    }

    @Composable
    fun EntryRow(e: FilterLists.Entry) = SwitchRow(e.title, summary(e), e.id in s.listsEnabled) { toggle(e, it) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Filter lists") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { app.loadRules(force = true) }) { Text("Update") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Text(
                (if (st != null) "${"%,d".format(st.rules)} rules · ${Formatter.formatShortFileSize(ctx, st.bytes)} · updated ${DateUtils.getRelativeTimeSpanString(st.updatedAt)}" else "Not downloaded yet") +
                    "\nEngine: ${app.rulesStatus}. Lists refresh every few days; big ones wait for Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Section("Annoyances")
            FilterLists.CATALOG.filter { it.section == FilterLists.ANNOYANCES }.forEach { EntryRow(it) }

            Section("Ads and trackers")
            FilterLists.CATALOG.filter { it.section == FilterLists.ADS }.forEach { EntryRow(it) }
            val regional = FilterLists.CATALOG.filter { it.section == FilterLists.REGIONAL }
            // Regional lists are ad lists: shown with "Block ads", or while one is still on.
            if ("ads" in s.listsEnabled || regional.any { it.id in s.listsEnabled }) {
                Section("Regional ad lists")
                regional.sortedByDescending { it.id in suggested }.forEach { EntryRow(it) }
            }

            Section("Your own")
            s.customLists.forEach { url ->
                val ls = st?.lists?.get(FilterLists.customName(url))
                ListItem(
                    headlineContent = { Text(ls?.title ?: url.substringAfterLast('/').ifEmpty { url }) },
                    supportingContent = {
                        Text(url + "\n" + when {
                            ls?.error != null -> "Not used: ${ls.error}"
                            ls != null && ls.rules > 0 -> "${"%,d".format(ls.rules)} rules"
                            else -> "Downloading"
                        })
                    },
                    trailingContent = { IconButton(onClick = { save(custom = s.customLists - url) }) { Icon(Icons.Filled.Close, "Remove $url") } },
                )
            }
            ListItem(
                headlineContent = { Text("Add a list by URL") },
                supportingContent = { Text("Any list in uBlock Origin or Adblock Plus format") },
                leadingContent = { Icon(Icons.Filled.Add, null) },
                modifier = Modifier.clickable { adding = true },
            )
            val myRules = remember(s.myFilters) { FilterLists.ruleCount(s.myFilters) }
            ListItem(
                headlineContent = { Text("My filters") },
                supportingContent = { Text(if (myRules > 0) "$myRules rules" else "Your own rules, such as example.com##.promo-bar") },
                leadingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { editingFilters = true },
            )
            Text(
                "Your own lists and filters can't run uBO's trusted scripts, and a cookie banner they hide is hidden, not answered with \"reject\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (adding) {
        var url by remember { mutableStateOf("") }
        val trimmed = url.trim()
        val problem = when {
            trimmed.isEmpty() -> null
            !trimmed.startsWith("https://") -> "Use an https:// address"
            trimmed in s.customLists -> "Already added"
            FilterLists.CATALOG.any { e -> e.lists.any { it.url == trimmed } } -> "Already in the lists above"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a list") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://example.com/filters.txt") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = trimmed.isNotEmpty() && problem == null, onClick = { save(custom = s.customLists + trimmed); adding = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    if (editingFilters) {
        var text by remember { mutableStateOf(s.myFilters) }
        AlertDialog(
            onDismissRequest = { editingFilters = false },
            title = { Text("My filters") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("example.com##.promo-bar\n||tracker.example^") },
                    minLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { save(myFilters = text); editingFilters = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingFilters = false }) { Text("Cancel") } },
        )
    }
}
