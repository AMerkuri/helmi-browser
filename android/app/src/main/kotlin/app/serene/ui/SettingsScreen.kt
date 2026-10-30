package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import app.serene.BuildConfig
import app.serene.ModelCatalog
import app.serene.SearchEngine
import app.serene.SereneApp
import app.serene.Settings
import app.serene.engine.FilterLists
import app.serene.ThemeMode
import app.serene.browser.SiteData
import app.serene.browser.Tab
import app.serene.browser.dimLevel
import app.serene.model.ModelState
import app.serene.model.NpuBenchmark
import app.serene.report.NpuRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
            ListItem(
                headlineContent = { Text("Browsing data") },
                supportingContent = { Text("Cookies and site storage for every site, including consent choices, and the most cleaned-up sites on the new tab page.") },
                trailingContent = { OutlinedButton(onClick = { SiteData.clearAll { }; app.stats.forgetSites() }) { Text("Clear") } },
            )
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
                "In the dark theme, pictures on darkened sites are shown dimmer, so white ones don't glare at night. Their colors stay.",
                s.dimPictures, enabled = s.theme != ThemeMode.LIGHT,
            ) { s.dimPictures = it }
            if (s.dimPictures && s.theme != ThemeMode.LIGHT) {
                val screen = app.brightness.value
                SwitchRow(
                    "Follow screen brightness",
                    "Sets the brightness below from the screen's: ${Settings.DIM_LEVEL_MIN}% at the lowest screen brightness, ${Settings.DIM_LEVEL_MAX}% at the highest." +
                        (screen?.let { " Screen brightness now: ${(it * 100).roundToInt()}%." } ?: ""),
                    s.dimFollowsBrightness,
                ) { s.dimFollowsBrightness = it }
                // Following the screen, the slider shows the level it gives now and can't be moved.
                val following = s.dimFollowsBrightness && screen != null
                val shown = if (following) (dimLevel(s, screen) * 100).roundToInt() else s.dimLevel
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness $shown%", Modifier.width(120.dp).alpha(if (following) 0.5f else 1f))
                    Slider(
                        value = shown.toFloat(),
                        onValueChange = { s.dimLevel = (it / 10).roundToInt() * 10 },
                        enabled = !following,
                        valueRange = Settings.DIM_LEVEL_MIN.toFloat()..Settings.DIM_LEVEL_MAX.toFloat(),
                        steps = if (following) 0 else (Settings.DIM_LEVEL_MAX - Settings.DIM_LEVEL_MIN) / 10 - 1,
                        modifier = Modifier.weight(1f),
                    )
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
