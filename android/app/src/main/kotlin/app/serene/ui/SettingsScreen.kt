package app.serene.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import app.serene.BuildConfig
import app.serene.ModelCatalog
import app.serene.SearchEngine
import app.serene.SereneApp
import app.serene.browser.SiteData
import app.serene.browser.Tab
import app.serene.model.ModelState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: SereneApp, onBack: () -> Unit) {
    val s = app.settings
    val ctx = LocalContext.current
    // Re-read file-based status (lists, learned sites) every few seconds while open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("Blocking")
            SwitchRow("Block annoyances", "Filter lists, the overlay detector and the model. Off: every site loads as sent.", s.engineEnabled) { s.engineEnabled = it }
            val st = remember(tick) { app.lists.status() }
            ListItem(
                headlineContent = { Text("Filter lists") },
                supportingContent = {
                    Text(
                        (if (st != null) "Fanboy Annoyances, uBO Cookie Notices, uBO Annoyances · ${Formatter.formatShortFileSize(ctx, st.bytes)} · updated ${DateUtils.getRelativeTimeSpanString(st.updatedAt)}" else "Not downloaded yet") +
                            "\nEngine: ${app.rulesStatus}",
                    )
                },
                trailingContent = { OutlinedButton(onClick = { app.loadRules(force = true) }) { Text("Update") } },
            )
            ListItem(
                headlineContent = { Text("Browsing data") },
                supportingContent = { Text("Cookies and site storage for every site, including consent choices.") },
                trailingContent = { OutlinedButton(onClick = { SiteData.clearAll { } }) { Text("Clear") } },
            )
            ListItem(
                headlineContent = { Text("Learned site rules") },
                supportingContent = { Text("Decisions remembered for ${remember(tick) { app.engine.cache.siteCount() }} sites, so the model runs about once per site.") },
                trailingContent = { OutlinedButton(onClick = { app.engine.cache.clear() }) { Text("Forget") } },
            )

            Section("On-device model")
            ModelSection(app)

            if (s.disabledSites.isNotEmpty()) {
                Section("Serene is off on")
                s.disabledSites.sorted().forEach { site ->
                    ListItem(headlineContent = { Text(site) }, trailingContent = {
                        IconButton(onClick = { s.setSiteEnabled(site, true) }) { Icon(Icons.Filled.Close, "Turn Serene back on for $site") }
                    })
                }
            }

            Section("Search")
            SearchEngine.entries.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { s.searchEngine = e }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.searchEngine == e, onClick = { s.searchEngine = e })
                    Text(e.label)
                }
            }

            Section("About")
            val wv = WebViewCompat.getCurrentWebViewPackage(ctx)
            ListItem(
                headlineContent = { Text("Serene ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})") },
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
    Text(
        "A small language model runs on the phone and only looks at overlays the filter lists and heuristics are unsure about. " +
            "Nothing is sent anywhere. Without it Serene still works, using lists and heuristics only.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    ModelCatalog.all.forEach { spec ->
        Row(Modifier.fillMaxWidth().clickable { m.select(spec.id) }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.modelId == spec.id, onClick = { m.select(spec.id) })
            Column {
                Text("${spec.name} · ${Formatter.formatShortFileSize(ctx, spec.sizeBytes)}")
                Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (val st = m.state) {
            ModelState.NotDownloaded -> if (m.spec.gatedPage != null) GatedImport(app) else {
                Button(onClick = m::download) { Text("Download (${Formatter.formatShortFileSize(ctx, m.spec.sizeBytes)})") }
            }
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
                OutlinedButton(onClick = m::delete) { Text("Delete model") }
                Spacer(Modifier.height(12.dp))
                BenchmarkSection(app)
            }
            is ModelState.Failed -> {
                Text("Download failed: ${st.message}", color = MaterialTheme.colorScheme.error)
                if (m.spec.gatedPage != null) GatedImport(app) else Button(onClick = m::download) { Text("Try again") }
            }
        }
    }
    SwitchRow("Use the model", "For overlays the heuristics are unsure about.", s.modelEnabled) { s.modelEnabled = it }
    SwitchRow("Download on Wi-Fi only", null, s.wifiOnlyDownloads) { s.wifiOnlyDownloads = it }
    SwitchRow("Skip the model in battery saver", "Lists and heuristics only while battery saver is on.", s.skipModelOnBatterySaver) { s.skipModelOnBatterySaver = it }
}

// Hugging Face serves a gated model only to a signed-in account that accepted its terms, so the
// user downloads it in a browser and picks the file here.
@Composable
private fun GatedImport(app: SereneApp) {
    val ctx = LocalContext.current
    val m = app.models
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(m::import) }
    Text(
        "Hugging Face only lets signed-in accounts download this model. Open its page, sign in, accept the terms and download " +
            "${m.spec.file} (${Formatter.formatShortFileSize(ctx, m.spec.sizeBytes)}), then import it here.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row {
        OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.spec.gatedPage))) } }) { Text("Open page") }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Import file") }
    }
}

// Runs the prototype's labelled overlays through the model on this phone (ModelBenchmark).
@Composable
private fun BenchmarkSection(app: SereneApp) {
    val ctx = LocalContext.current
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
        OutlinedButton(onClick = app::runBenchmark) { Text(if (app.benchmarkReport == null) "Run benchmark" else "Run again") }
    }
    app.benchmarkReport?.let { report ->
        Spacer(Modifier.height(6.dp))
        Text(report, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Serene model benchmark", report))
        }) { Text("Copy results") }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
