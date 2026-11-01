package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.webkit.WebViewCompat
import app.serene.BuildConfig
import app.serene.DefaultBrowser
import app.serene.DimStrength
import app.serene.ModelCatalog
import app.serene.SearchEngine
import app.serene.SereneApp
import app.serene.engine.FilterLists
import app.serene.ThemeMode
import app.serene.browser.Tab
import app.serene.model.ModelState
import app.serene.model.NpuBenchmark
import app.serene.report.NpuRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: SereneApp, onBack: () -> Unit) {
    val s = app.settings
    val ctx = LocalContext.current
    // Re-read file-based status (lists, learned sites) every few seconds while open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    var listsOpen by rememberSaveable { mutableStateOf(false) }
    if (listsOpen) {
        BackHandler { listsOpen = false }
        FilterListsScreen(app, onBack = { listsOpen = false })
        return
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            DefaultBrowserRow(app)
            Section("Blocking")
            SwitchRow("Block annoyances", "Filter lists, the overlay detector and the model. Off: every site loads as sent.", s.engineEnabled) { s.engineEnabled = it }
            // status() reads the list files, so off the main thread; .value, not by, so st smart-casts.
            val st = produceState<FilterLists.Status?>(null, tick, s.listSelection) { value = withContext(Dispatchers.IO) { app.lists.status() } }.value
            val on = FilterLists.CATALOG.filter { it.id in s.listsEnabled }.map { it.title } + listOfNotNull(s.customLists.size.takeIf { it > 0 }?.let { "$it of your own" })
            ListItem(
                headlineContent = { Text("Filter lists") },
                supportingContent = {
                    Text(
                        on.joinToString().ifEmpty { "None switched on" } +
                            (if (st != null) " · ${"%,d".format(st.rules)} rules · ${Formatter.formatShortFileSize(ctx, st.bytes)} · updated ${DateUtils.getRelativeTimeSpanString(st.updatedAt)}" else " · not downloaded yet") +
                            "\nEngine: ${app.rulesStatus}",
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { listsOpen = true },
            )
            var clearing by rememberSaveable { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Clear browsing data") },
                supportingContent = { Text("History, cookies and site storage (including consent choices), and cached files.") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { clearing = true },
            )
            if (clearing) ClearBrowsingDataDialog(app, onDismiss = { clearing = false })
            ListItem(
                headlineContent = { Text("Learned site rules") },
                supportingContent = { Text("Decisions remembered for ${remember(tick) { app.engine.cache.siteCount() }} sites, so the model runs about once per site.") },
                trailingContent = { OutlinedButton(onClick = { app.engine.cache.clear() }) { Text("Forget") } },
            )

            ListItem(
                headlineContent = { Text("Statistics") },
                supportingContent = { Text("The totals on the new tab page, counted since ${DateUtils.formatDateTime(ctx, app.stats.totals.since, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)}.") },
                trailingContent = { OutlinedButton(onClick = { app.stats.reset() }) { Text("Reset") } },
            )

            Section("On-device model")
            ModelSection(app)

            if (s.disabledSites.isNotEmpty()) {
                Section("Helmi is off on")
                s.disabledSites.sorted().forEach { site ->
                    ListItem(headlineContent = { Text(site) }, trailingContent = {
                        IconButton(onClick = { s.setSiteEnabled(site, true) }) { Icon(Icons.Filled.Close, "Turn Helmi back on for $site") }
                    })
                }
            }

            SitePermissionsSection(app.sitePermissions)

            Section("Theme")
            ThemeMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { s.theme = m }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.theme == m, onClick = { s.theme = m })
                    Column {
                        Text(m.label)
                        if (m == ThemeMode.SYSTEM) Text("Dark when the phone's dark theme is on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SwitchRow(
                "Apply dark theme to sites, when possible",
                "Darkens sites that have no dark theme of their own. Sites with one use it, and pictures keep their colors.",
                s.darkSites, enabled = s.theme != ThemeMode.LIGHT,
            ) { s.darkSites = it }
            SwitchRow(
                "Dim pictures",
                "In the dark theme, pictures on darkened sites are shown dimmer, so white ones don't glare at night. Their colors stay. " +
                    DimStrength.entries.joinToString(", ", postfix = " brightness.") { "${it.label.lowercase()} ${it.percent}%" }.replaceFirstChar { it.uppercase() },
                s.dimPictures, enabled = s.theme != ThemeMode.LIGHT,
            ) { s.dimPictures = it }
            if (s.dimPictures && s.theme != ThemeMode.LIGHT) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DimStrength.entries.forEach { d ->
                        FilterChip(selected = s.dimStrength == d, onClick = { s.dimStrength = d }, label = { Text(d.label) })
                    }
                }
            }
            // Sites switched in ⋮ › Dark theme; X puts one back on the setting above.
            s.darkSiteChoices.toSortedMap().forEach { (site, on) ->
                ListItem(
                    headlineContent = { Text(site) },
                    supportingContent = { Text(if (on) "Dark theme on" else "Dark theme off") },
                    trailingContent = {
                        IconButton(onClick = { s.resetDarkSite(site) }) { Icon(Icons.Filled.Close, "Use the setting for $site") }
                    },
                )
            }

            Section("Search")
            SearchEngine.entries.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { s.searchEngine = e }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.searchEngine == e, onClick = { s.searchEngine = e })
                    Text(e.label)
                }
            }

            Section("Flags")
            Text(
                "Features to try out. They may change or go away.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow(
                "Back/forward cache",
                if (Tab.backForwardCacheSupported) "Going back or forward resumes the page as it was, and swipes show a picture of it. Off: the page loads again, and swipes show its title."
                else "Not supported by this WebView: pages load again, and swipes show the page's title.",
                s.backForwardCache && Tab.backForwardCacheSupported,
                enabled = Tab.backForwardCacheSupported,
            ) { s.backForwardCache = it }
            SwitchRow(
                "Unload idle tabs",
                "Tabs in the background let their page go to save memory, and load it again when you open them: a tab not opened for 30 minutes, the oldest beyond the 4 opened last, and most of them when the phone is low on memory or Helmi is in the background. Not a tab playing sound or video, or one with a form you typed in.",
                s.discardTabs,
            ) { s.discardTabs = it }
            SwitchRow(
                "Show model decisions",
                "A short message above the bottom bar, like the one after closing a tab, each time the on-device model decides about an overlay on this page: what it hid, with Undo, or what it left alone, with Hide.",
                s.showModelDecisions,
            ) { s.showModelDecisions = it }

            Section("About")
            val wv = WebViewCompat.getCurrentWebViewPackage(ctx)
            ListItem(
                headlineContent = { Text("Helmi ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})") },
                supportingContent = { Text("WebView ${wv?.versionName ?: "?"} · document-start scripts ${if (Tab.documentStartSupported) "supported" else "not supported (fallback)"} · passkeys ${if (Tab.webAuthnSupported) "supported" else "not supported by this WebView"}") },
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

// Asks with Android's dialog where there is one; once Helmi is the default, or where Android
// shows no dialog, its default apps screen.
@Composable
private fun DefaultBrowserRow(app: SereneApp) {
    val ctx = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultBrowser.isDefault(ctx)) }
    // Back from Android's settings, or another browser took over meanwhile.
    LifecycleResumeEffect(Unit) {
        isDefault = DefaultBrowser.isDefault(ctx)
        onPauseOrDispose {}
    }
    var askedAt by remember { mutableLongStateOf(0L) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        isDefault = DefaultBrowser.isDefault(ctx)
        // Once the user picked "Don't ask again", Android answers at once without a dialog: its
        // default apps screen instead, so the tap still leads somewhere.
        if (!isDefault && r.resultCode != Activity.RESULT_OK && SystemClock.elapsedRealtime() - askedAt < NO_DIALOG_MS) DefaultBrowser.openSystemSettings(ctx)
    }
    ListItem(
        headlineContent = { Text(if (isDefault) "Helmi is the default browser" else "Make Helmi the default browser") },
        supportingContent = { Text(if (isDefault) "Links from other apps open in Helmi. Change it in Android's default apps." else "Links from other apps open in Helmi.") },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable {
            val intent = if (isDefault) null else DefaultBrowser.requestIntent(ctx)
            if (intent == null) {
                DefaultBrowser.openSystemSettings(ctx)
            } else {
                app.settings.askedDefaultBrowser = true // asked now: no question on a later launch
                askedAt = SystemClock.elapsedRealtime()
                ask.launch(intent)
            }
        },
    )
}

/** A "no" faster than this came without a dialog (see DefaultBrowserRow). */
private const val NO_DIALOG_MS = 500L

@Composable
private fun ModelSection(app: SereneApp) {
    val s = app.settings
    val ctx = LocalContext.current
    val m = app.models
    fun pick(id: String) {
        if (id == s.modelId) return
        m.select(id)
        app.unloadModel() // the old one, now rather than after its idle timeout
    }
    Text(
        "Qwen3-0.6B runs on the phone and only looks at overlays the filter lists and heuristics are unsure about. " +
            "Nothing is sent anywhere. Without it Helmi still works, using lists and heuristics only.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    ModelCatalog.available.forEach { spec ->
        Row(Modifier.fillMaxWidth().clickable { pick(spec.id) }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = m.spec.id == spec.id, onClick = { pick(spec.id) })
            Column {
                Text("${spec.name} · ${Formatter.formatShortFileSize(ctx, spec.sizeBytes)}")
                Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // A Snapdragon whose NPU has no model yet: ask for one (.github/ISSUE_TEMPLATE/npu-model.yml).
    if (NpuBenchmark.supported && ModelCatalog.available.none { it.npu }) {
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NpuRequest.url()))) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Request a model for this phone's NPU")
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (val st = m.state) {
            ModelState.NotDownloaded -> Button(onClick = m::download) { Text("Download (${Formatter.formatShortFileSize(ctx, m.spec.sizeBytes)})") }
            is ModelState.Downloading -> {
                Text("Downloading ${Formatter.formatShortFileSize(ctx, st.bytes)} of ${Formatter.formatShortFileSize(ctx, st.total)}" + if (s.wifiOnlyDownloads) " (waits for Wi-Fi)" else "")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { if (st.total > 0) st.bytes.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = m::cancel) { Text("Cancel") }
            }
            ModelState.Verifying -> Text("Checking the download…")
            ModelState.Ready -> {
                Text("Downloaded. Status: ${app.classifierInfo()}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { m.delete(); app.unloadModel() }) { Text("Delete model") }
                Spacer(Modifier.height(12.dp))
                BenchmarkSection(app)
            }
            is ModelState.Failed -> {
                Text("Download failed: ${st.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = m::download) { Text("Try again") }
            }
        }
    }
    SwitchRow("Use the model", "For overlays the heuristics are unsure about.", s.modelEnabled) { s.modelEnabled = it; if (!it) app.unloadModel() }
    SwitchRow("Download on Wi-Fi only", null, s.wifiOnlyDownloads) { s.wifiOnlyDownloads = it }
    SwitchRow("Skip the model in battery saver", "Lists and heuristics only while battery saver is on.", s.skipModelOnBatterySaver) { s.skipModelOnBatterySaver = it }
}

// Runs the prototype's labelled overlays through the model on this phone (ModelBenchmark).
@Composable
private fun BenchmarkSection(app: SereneApp) {
    val ctx = LocalContext.current
    // A model compiled for the NPU runs only there: its benchmark is NpuBenchmarkRow's.
    if (!app.models.spec.npu) {
        Text("Model benchmark", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Runs 57 sample overlays through the model twice, to measure speed and accuracy on this phone. Takes a few minutes; keep the screen on.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        val progress = app.benchmarkProgress
        if (progress != null) {
            Text("Running: $progress")
        } else {
            OutlinedButton(onClick = app::runBenchmark, enabled = app.npuBenchmarkProgress == null) { Text(if (app.benchmarkReport == null) "Run benchmark" else "Run again") }
        }
        app.benchmarkReport?.let { report ->
            Spacer(Modifier.height(6.dp))
            Text(report, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Helmi model benchmark", report))
            }) { Text("Copy results") }
        }
    }
    if (NpuBenchmark.supported) NpuBenchmarkRow(app)
}

// The same overlays on the Qualcomm NPU (NpuBenchmark), in a separate process.
@Composable
private fun NpuBenchmarkRow(app: SereneApp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { app.refreshNpuModel() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(app::importNpuModel) }
    val downloaded = app.models.spec.npu // this section only shows once the model is downloaded
    Spacer(Modifier.height(12.dp))
    Text(if (downloaded) "NPU benchmark" else "Model benchmark on the NPU", style = MaterialTheme.typography.bodyMedium)
    Text(
        when {
            app.npuCompiledModel -> "Runs the sample overlays on the NPU with the NPU model picked by hand (${app.npuCompiledModelInfo}), in a separate process. Takes a minute."
            downloaded -> "Runs the sample overlays through the model on the NPU, in a separate process, to measure speed, accuracy and memory. Takes a minute."
            else -> "For testing an npu-model build from GitHub Actions: pick its .zip (or the .litertlm in it) to run it on the NPU."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    val progress = app.npuBenchmarkProgress
    if (progress != null) {
        Text("Running: $progress")
    } else {
        if (downloaded || app.npuCompiledModel) {
            OutlinedButton(onClick = app::runNpuBenchmark, enabled = app.benchmarkProgress == null) {
                Text(if (app.npuBenchmarkReport == null) "Run NPU benchmark" else "Run NPU benchmark again")
            }
        }
        Row {
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (app.npuCompiledModel) "Replace picked NPU model" else "Pick NPU model") }
            if (app.npuCompiledModel) TextButton(onClick = app::deleteNpuModel) { Text("Remove picked NPU model") }
        }
    }
    app.npuBenchmarkReport?.let { report ->
        Spacer(Modifier.height(6.dp))
        Text(report, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Helmi NPU benchmark", report))
        }) { Text("Copy NPU results") }
    }
}

@Composable
internal fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }.alpha(if (enabled) 1f else 0.5f),
    )
}
