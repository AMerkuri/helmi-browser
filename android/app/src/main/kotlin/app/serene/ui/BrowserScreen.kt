package app.serene.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.serene.R
import app.serene.Screen
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.browser.TabManager
import app.serene.report.Reporter
import java.io.File

@Composable
fun BrowserScreen(app: SereneApp, tabs: TabManager, onOpen: (Screen) -> Unit) {
    val tab = tabs.current ?: return
    val context = LocalContext.current
    var showSite by remember { mutableStateOf(false) }
    var reportDir by remember { mutableStateOf<File?>(null) }

    BackHandler(enabled = tab.canGoBack) { tab.goBack() }

    val startReport = {
        Reporter.capture(context, app, tab) { reportDir = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        UrlBar(app, tab, onShield = { showSite = true })
        if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) {
                AndroidView(
                    factory = { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView); tab.webView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab.url.isEmpty() || tab.url == "about:blank") StartPage(app)
        }
        BottomBar(app, tabs, tab, onOpen, onReport = startReport, onSite = { showSite = true })
    }

    if (showSite) SiteSheet(app, tab, onDismiss = { showSite = false }, onReport = { showSite = false; startReport() })

    reportDir?.let { dir ->
        ReportDialog(
            onDismiss = { reportDir = null },
            onSend = { note -> reportDir = null; Reporter.share(context, dir, note, tab.url) },
        )
    }
}

@Composable
private fun UrlBar(app: SereneApp, tab: Tab, onShield: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var value by remember(tab.id) { mutableStateOf(TextFieldValue(tab.url)) }
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab.url, focused) { if (!focused) value = TextFieldValue(displayUrl(tab.url)) }
    LaunchedEffect(tab.id) { if (tab.url.isEmpty()) focus.requestFocus() }

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ShieldButton(app, tab, onShield)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f).height(44.dp)) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = {
                        tab.load(value.text)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                        if (it.isFocused && !focused) value = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                        focused = it.isFocused
                    },
                    decorationBox = { inner ->
                        if (value.text.isEmpty()) Text("Search or type a URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
            }
        }
        if (focused) {
            IconButton(onClick = { value = TextFieldValue("") }) { Icon(Icons.Filled.Clear, "Clear") }
        } else {
            IconButton(onClick = { if (tab.progress < 100) tab.stop() else tab.reload() }) {
                Icon(if (tab.progress < 100) Icons.Filled.Clear else Icons.Filled.Refresh, if (tab.progress < 100) "Stop" else "Reload")
            }
        }
    }
}

private fun displayUrl(url: String) = if (url == "about:blank") "" else url.removePrefix("https://").removeSuffix("/")

@Composable
private fun ShieldButton(app: SereneApp, tab: Tab, onClick: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = tab.version // recompose on engine activity
    val s = tab.session
    val count = (s?.blockedRequests?.get() ?: 0) + (s?.actions()?.size ?: 0)
    val on = s?.enabled ?: app.settings.engineEnabled
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (count > 0 && on) Badge { Text(if (count > 99) "99+" else "$count") } }) {
            Icon(
                painterResource(if (on) R.drawable.ic_shield else R.drawable.ic_shield_off),
                contentDescription = "Serene on this site",
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BottomBar(app: SereneApp, tabs: TabManager, tab: Tab, onOpen: (Screen) -> Unit, onReport: () -> Unit, onSite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = tab::goBack, enabled = tab.canGoBack, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        IconButton(onClick = tab::goForward, enabled = tab.canGoForward, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
        IconButton(onClick = { onOpen(Screen.Tabs) }, modifier = Modifier.weight(1f)) {
            Surface(shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${tabs.tabs.size}", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New tab") }, onClick = { menu = false; tabs.open() })
                DropdownMenuItem(text = { Text("Report missed popup") }, onClick = { menu = false; onReport() })
                val site = tab.session?.site()
                if (site != null) {
                    val enabled = app.settings.isSiteEnabled(site)
                    DropdownMenuItem(
                        text = { Text("Serene on $site") },
                        trailingIcon = { Switch(checked = enabled, onCheckedChange = null, enabled = app.settings.engineEnabled) },
                        enabled = app.settings.engineEnabled,
                        onClick = { menu = false; app.settings.setSiteEnabled(site, !enabled); tab.reload() },
                    )
                }
                DropdownMenuItem(text = { Text("What Serene did here") }, onClick = { menu = false; onSite() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpen(Screen.Settings) })
            }
        }
    }
}

@Composable
private fun StartPage(app: SereneApp) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Icon(painterResource(R.drawable.ic_shield), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Serene", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cookie banners, newsletter pop-ups and other overlays are cleared for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            val model = if (app.models.readyFile() != null && app.settings.modelEnabled) "on-device model ready" else "filter lists and heuristics (no model)"
            Text("Engine: $model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report missed popup") },
        text = {
            Column {
                Text("Serene saved a screenshot and what its detector sees on this page. Add a note if it helps, then pick where to send it.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("e.g. newsletter box after scrolling") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSend(note) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
