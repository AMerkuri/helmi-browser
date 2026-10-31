package app.serene.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndSelectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewFeature
import app.serene.R
import app.serene.Screen
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.browser.TabManager
import app.serene.engine.siteOf
import app.serene.reader.ReaderPage
import app.serene.report.Reporter
import java.io.File
import kotlin.math.roundToInt

/** [covered]: the tab switcher is drawn over this screen, so a new tab doesn't open the keyboard under it. */
@Composable
fun BrowserScreen(app: SereneApp, tabs: TabManager, onOpen: (Screen) -> Unit, onPageBounds: (Rect) -> Unit = {}, covered: Boolean = false) {
    val tab = tabs.current ?: return
    val context = LocalContext.current
    var showSite by remember { mutableStateOf(false) }
    var reportDir by remember { mutableStateOf<File?>(null) }
    // Reader mode, for the current tab's page.
    var reader by remember { mutableStateOf<ReaderPage?>(null) }
    LaunchedEffect(tab.id) { reader = null }

    HistoryBackHandler(tab)
    // Back on the first page of a tab a page opened (a popup, a target=_blank link) closes it and
    // returns to the page that opened it, as in Chrome.
    BackHandler(enabled = !tab.canGoBack && tabs.openerOf(tab) != null) { tabs.closeWindow(tab) }
    // A swipe or pull left half way (another tab, another screen) puts its page back.
    DisposableEffect(tab) { onDispose { tab.swipe.reset(); tab.pull.reset() } }
    // A sideways drag on the bottom bar turns the cube to the tab beside this one.
    val scope = rememberCoroutineScope()
    val cube = remember { TabCube(tabs, scope) }
    DisposableEffect(Unit) { onDispose { cube.reset() } }
    val window = LocalActivity.current?.window
    val focusManager = LocalFocusManager.current

    // Android 13+: the download notification needs permission. Ask once, at the first download.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(app.downloads.items.isNotEmpty()) {
        if (Build.VERSION.SDK_INT >= 33 && app.downloads.items.isNotEmpty() && !app.settings.askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            app.settings.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val startReport = {
        Reporter.capture(context, app, tab) { reportDir = it }
    }

    // The page's article from engine/dist/reader.js.
    val openReader = {
        ReaderPage.extract(tab.webView) { page ->
            if (page == null) Toast.makeText(context, "Helmi found no article on this page", Toast.LENGTH_SHORT).show()
            else reader = page
        }
    }

    // The content colour a Surface would set: without it icons default to black and vanish on the
    // dark theme's bars. Not a Surface itself: wrapping the WebView in one turned the address bar
    // and the bars around the page solid black.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                UrlBar(app, tab, covered, autofocus = tab !== cube.arrived, onShield = { showSite = true })
                if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                else Spacer(Modifier.height(2.dp))
                val density = LocalDensity.current
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged {
                        tab.swipe.width = it.width.toFloat()
                        tab.swipe.flingVelocity = with(density) { 800.dp.toPx() }
                        tab.pull.trigger = with(density) { 72.dp.toPx() }
                        cube.width = it.width.toFloat()
                        cube.flingVelocity = with(density) { 500.dp.toPx() }
                    }.onGloballyPositioned { onPageBounds(it.boundsInRoot()); cube.bounds = it.boundsInWindow() },
                ) {
                    val startPage = !tab.openedByPage && (tab.url.isEmpty() || tab.url == "about:blank")
                    val downloadPage = tab.downloadPage
                    val swipe = tab.swipe
                    // Swiping back, the previous page is underneath; the WebView moves itself.
                    if (swipe.active && !swipe.targetOnTop) SwipeTarget(swipe)
                    key(tab.id) {
                        AndroidView(
                            factory = { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView); tab.webView },
                            modifier = Modifier.fillMaxSize(),
                            // A WebView that has loaded nothing drew the address bar and the bars around
                            // it solid black; the start page covers it anyway.
                            // The same goes for a tab whose first page hasn't started yet (a link from
                            // another app) or whose only navigation was a download.
                            update = { it.visibility = if (startPage || downloadPage || !tab.hasPage) View.INVISIBLE else View.VISIBLE },
                        )
                    }
                    // What covers the WebView moves with it.
                    Box(Modifier.fillMaxSize().offset { IntOffset(swipe.pageOffset.roundToInt(), 0) }) {
                        if (downloadPage) DownloadPage(tab.id, app.downloads, onCloseTab = { tabs.close(tab, undo = false) })
                        else if (startPage) StartPage(app)
                        else tab.downloadBar?.takeIf { it in app.downloads.items }?.let { d ->
                            DownloadBar(d, app.downloads, onDismiss = { tab.downloadBar = null }, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    // Going forward, the next page slides in over this one; after the step, the
                    // target covers the WebView until the new page has drawn.
                    if (swipe.active && swipe.targetOnTop) SwipeTarget(swipe)
                    PullIndicator(tab)
                    TabCubeFaces(cube, app)
                }
                // The buttons still take taps: a drag starts only once the finger has moved.
                Column(
                    Modifier.draggable(
                        rememberDraggableState { cube.drag(it) },
                        Orientation.Horizontal,
                        onDragStarted = { focusManager.clearFocus(); cube.start(window) },
                        onDragStopped = { cube.release(it) },
                    ),
                ) {
                    BottomBar(
                        app, tabs, tab, onOpen, onReport = startReport, onSite = { showSite = true },
                        onReader = openReader,
                    )
                }
            }
            reader?.let { page ->
                ReaderView(
                    page, app.readerPrefs,
                    onClose = { reader = null },
                    onOpenLink = { url -> reader = null; tab.load(url) },
                )
            }
        }
    }

    // A new tab from a link or picture opens behind this one, as in Chrome: the tab count goes up.
    tab.contextMenu?.let { p -> ContextMenu(app, tab, p, onDismiss = { tab.contextMenu = null }, onNewTab = { tabs.open(it, select = false) }) }

    // A site asking for the camera, microphone or location. Not under the tab switcher: it waits
    // until its tab is in front, as in Chrome.
    if (!covered) tab.permissions.current?.let { ask ->
        key(ask) { PermissionDialog(ask, onAnswer = { tab.permissions.answer(ask, it) }, onDismiss = { tab.permissions.dismiss(ask) }) }
    }

    if (showSite) SiteSheet(app, tab, onDismiss = { showSite = false }, onReport = { showSite = false; startReport() })

    reportDir?.let { dir ->
        ReportDialog(
            onDismiss = { reportDir = null },
            onIssue = { note -> reportDir = null; Reporter.openIssue(context, dir, note) { tabs.open(it) } },
            onShare = { note -> reportDir = null; Reporter.share(context, dir, note, tab.url) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UrlBar(app: SereneApp, tab: Tab, covered: Boolean, autofocus: Boolean, onShield: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The state-based field: dragging the cursor handle past the edge keeps scrolling the text,
    // where the TextFieldValue one stopped a character past what was on screen.
    val state = remember(tab.id) { TextFieldState(displayUrl(tab.url)) }
    val scroll = rememberScrollState()
    // The address the edit started from, while the field is focused.
    var original by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(tab.url, focused) {
        if (focused) {
            // The whole address, selected, as in Samsung Internet. After the tap that focused the
            // field has placed its cursor, which would otherwise undo the selection.
            if (original == null) {
                val url = if (tab.url == "about:blank") "" else tab.url
                original = url
                state.setTextAndSelectAll(url)
            }
        } else {
            original = null
            state.edit { replace(0, length, displayUrl(tab.url)); selection = TextRange.Zero }
            // An unfocused field doesn't follow the cursor, so show the start of the address.
            scroll.scrollTo(0)
        }
    }
    // A new tab starts with the keyboard up, once the switcher (if any) is gone. Not one turned
    // to on the bar's cube: that is flipping through tabs, not starting one.
    LaunchedEffect(tab.id, covered) { if (!covered && autofocus && tab.url.isEmpty() && !tab.openedByPage) focus.requestFocus() }
    // Closing the keyboard (back, or its own hide key) ends the edit, as in Chrome.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) {
            imeWasVisible = false
            if (focused) focusManager.clearFocus()
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ShieldButton(app, tab, onShield)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f).height(44.dp)) {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                scrollState = scroll,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                onKeyboardAction = {
                    tab.load(state.text.toString())
                    focusManager.clearFocus()
                },
                modifier = Modifier.fillMaxSize().focusRequester(focus).onFocusChanged {
                    focused = it.isFocused
                },
                decorator = { inner ->
                    // Chrome's inline clear button: inside the field, at its end, while there is
                    // something to clear. Reload stays where it is. Copy sits next to it until
                    // the address is edited, as in Samsung Internet.
                    val showClear = focused && state.text.isNotEmpty()
                    val showCopy = showClear && original != null && state.text.contentEquals(original)
                    Row(
                        Modifier.fillMaxSize().padding(start = 16.dp, end = if (showClear) 2.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (state.text.isEmpty()) Text("Search or type a URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                        if (showCopy) {
                            IconButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Address", original))
                                focusManager.clearFocus()
                            }, modifier = Modifier.size(40.dp)) {
                                Icon(painterResource(R.drawable.ic_copy), "Copy address", Modifier.size(20.dp))
                            }
                        }
                        if (showClear) {
                            IconButton(onClick = { state.clearText() }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Clear, "Clear", Modifier.size(20.dp))
                            }
                        }
                    }
                },
            )
        }
        IconButton(onClick = { if (tab.progress < 100) tab.stop() else tab.reload() }) {
            Icon(if (tab.progress < 100) Icons.Filled.Clear else Icons.Filled.Refresh, if (tab.progress < 100) "Stop" else "Reload")
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
    // The badge sits beside the button, not inside it: IconButton clips its content to a 40 dp
    // circle, which cut off any count wider than one digit.
    Box(Modifier.size(48.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.align(Alignment.Center)) {
            Icon(
                painterResource(if (on) R.drawable.ic_shield else R.drawable.ic_shield_off),
                contentDescription = "Helmi on this site",
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 0 && on) {
            Badge(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)) {
                Text(if (count > 99) "99+" else "$count", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun BottomBar(
    app: SereneApp, tabs: TabManager, tab: Tab, onOpen: (Screen) -> Unit, onReport: () -> Unit, onSite: () -> Unit,
    onReader: () -> Unit,
) {
    val menu = remember { MutableTransitionState(false) }
    fun close() { menu.targetState = false }
    var barTop by remember { mutableIntStateOf(0) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (tab.canGoBack) tab.goBack() else tabs.closeWindow(tab) }, enabled = tab.canGoBack || tabs.openerOf(tab) != null, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        IconButton(onClick = tab::goForward, enabled = tab.canGoForward, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
        IconButton(onClick = { tabs.open() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, "New tab") }
        IconButton(onClick = { onOpen(Screen.Tabs) }, modifier = Modifier.weight(1f)) {
            Surface(shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${tabs.tabs.size}", style = MaterialTheme.typography.labelMedium) }
            }
        }
        // The whole height of the bar, so the menu's anchor starts where the bar does.
        Box(
            Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { barTop = it.positionInWindow().y.roundToInt() },
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { menu.targetState = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
            BarMenu(menu, barTop) {
                DropdownMenuItem(text = { Text("Downloads") }, onClick = { close(); onOpen(Screen.Downloads) })
                val onPage = tab.hasPage && !tab.downloadPage && tab.url.isNotEmpty() && tab.url != "about:blank"
                DropdownMenuItem(text = { Text("Reader mode") }, enabled = onPage, onClick = { close(); onReader() })
                DropdownMenuItem(text = { Text("Report missed popup") }, onClick = { close(); onReport() })
                val site = tab.session?.site()
                if (site != null) {
                    val enabled = app.settings.isSiteEnabled(site)
                    DropdownMenuItem(
                        text = { Text("Helmi on $site") },
                        trailingIcon = { Switch(checked = enabled, onCheckedChange = null, enabled = app.settings.engineEnabled) },
                        enabled = app.settings.engineEnabled,
                        onClick = { close(); app.settings.setSiteEnabled(site, !enabled); tab.reload() },
                    )
                }
                // Chrome's ⋮ › Dark theme: this site darkened or not, the setting until switched here.
                // Only in the dark theme: WebView darkens nothing in the light one.
                if (onPage && isSystemInDarkTheme() && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    val darkSite = siteOf(tab.url)
                    val dark = app.settings.darkSite(darkSite)
                    DropdownMenuItem(
                        text = { Text("Dark theme") },
                        trailingIcon = { Switch(checked = dark, onCheckedChange = null) },
                        onClick = { close(); app.settings.setDarkSite(darkSite, !dark) },
                    )
                }
                DropdownMenuItem(text = { Text("What Helmi did here") }, onClick = { close(); onSite() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { close(); onOpen(Screen.Settings) })
            }
        }
    }
}

/**
 * The bottom bar's menu, standing on the bar at its end, as Chrome's does. Material's DropdownMenu
 * keeps 48 dp clear of the bottom of the visible window, which ends at the navigation bar, so above
 * the bottom bar it floated with a gap under it.
 */
@Composable
private fun BarMenu(expanded: MutableTransitionState<Boolean>, barTop: Int, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded.currentState && !expanded.targetState) return
    // Taller than the room above the bar (a folded phone on its side), it scrolls.
    val density = LocalDensity.current
    val room = with(density) { (barTop - WindowInsets.statusBars.getTop(this)).toDp() - 8.dp }
    Popup(popupPositionProvider = AboveAnchor, onDismissRequest = { expanded.targetState = false }, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            expanded,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(75)),
        ) {
            Surface(
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                Column(
                    Modifier.heightIn(max = room.coerceAtLeast(48.dp)).width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}

/** The popup's bottom on the anchor's top, its end on the anchor's end. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(x.coerceAtLeast(0), anchorBounds.top - popupContentSize.height)
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onIssue: (String) -> Unit, onShare: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report missed popup") },
        text = {
            Column {
                Text("Helmi saved a screenshot and what its detector sees on this page. Add a note if it helps.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text("e.g. newsletter box after scrolling") }, minLines = 2)
                Spacer(Modifier.height(12.dp))
                Text("GitHub issue opens a filled-in form in a new tab, with the screenshot on the clipboard to paste in. You submit it from your GitHub account, and it is public, so check it first. Share files sends the report and screenshot to any app.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onIssue(note) }) { Text("GitHub issue") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onShare(note) }) { Text("Share files") }
            }
        },
    )
}
