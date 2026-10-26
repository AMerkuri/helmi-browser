package app.serene.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.serene.reader.ReaderHtml
import app.serene.reader.ReaderPage
import app.serene.reader.ReaderPrefs
import app.serene.reader.ReaderTheme
import kotlin.math.roundToInt

/**
 * Reader mode: the page's article alone, in book typography, like an e-reader. A WebView of its
 * own with JavaScript off shows ReaderHtml; links open in the tab. Tapping the left or right edge
 * turns the page, the middle shows and hides the bars, and the footer says how far along you are.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ReaderView(page: ReaderPage, prefs: ReaderPrefs, onClose: () -> Unit, onOpenLink: (String) -> Unit) {
    val context = LocalContext.current
    // Auto follows the app's theme (light, dark or the system's).
    val style = prefs.style(appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val theme = style.theme
    val bg = Color(theme.background)
    val fg = Color(theme.text)
    val muted = Color(theme.muted)
    var chrome by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showStyle by remember { mutableStateOf(false) }
    // Where to scroll back to once a restyled page has laid out.
    val restore = remember { floatArrayOf(-1f) }

    BackHandler(onBack = onClose)

    val webView = remember(page) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            with(settings) {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                setSupportZoom(false)
            }
            // The reader's own colours, not the browser's dark rendering of them.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            isVerticalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                // Only a tap on a link leaves the reader; nothing in the page can navigate by itself.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.hasGesture()) onOpenLink(request.url.toString())
                    return true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val f = restore[0]
                    if (f <= 0f) return
                    restore[0] = -1f
                    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { view.scrollTo(0, (f * maxScroll(view)).roundToInt()) }
                    })
                }
            }
            setOnScrollChangeListener { v, _, y, _, oldY ->
                val max = maxScroll(v as WebView)
                progress = if (max > 0) (y.toFloat() / max).coerceIn(0f, 1f) else 1f
                if (y > oldY + 8 && chrome) chrome = false
            }
        }
    }

    // Edge taps turn pages; the middle toggles the bars. Links keep working: the WebView handles those.
    val tapToTurn = prefs.tapToTurn
    DisposableEffect(webView, tapToTurn) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val hit = webView.hitTestResult.type
                if (hit == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
                val w = webView.width
                when {
                    tapToTurn && e.x < w * 0.25f -> turn(webView, -1).also { chrome = false }
                    tapToTurn && e.x > w * 0.75f -> turn(webView, 1).also { chrome = false }
                    else -> chrome = !chrome
                }
                return true
            }
        })
        webView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
        onDispose { webView.setOnTouchListener(null) }
    }

    LaunchedEffect(webView, style) {
        if (progress > 0f) restore[0] = progress
        webView.loadDataWithBaseURL(page.url, ReaderHtml.build(page, style), "text/html", "utf-8", null)
    }

    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }

    // Status bar icons that show on the reader's page colour.
    val view = LocalView.current
    DisposableEffect(theme.dark) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        val beforeNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !theme.dark
        controller?.isAppearanceLightNavigationBars = !theme.dark
        onDispose {
            before?.let { controller.isAppearanceLightStatusBars = it }
            beforeNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }

    CompositionLocalProvider(LocalContentColor provides fg) {
        Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView }, modifier = Modifier.fillMaxSize())
                if (chrome) {
                    Row(
                        Modifier.fillMaxWidth().background(bg.copy(alpha = 0.96f)).padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close reader") }
                        Text(
                            page.siteName.ifBlank { android.net.Uri.parse(page.url).host?.removePrefix("www.") ?: "" },
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showStyle = true }) { Text("Aa", color = Color(theme.accent), fontFamily = FontFamily.Serif, fontSize = 18.sp) }
                    }
                }
            }
            // The footer, as on an e-reader: how far along, and how long the rest takes.
            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                val left = (page.minutes * (1f - progress)).roundToInt()
                Text(
                    if (progress >= 0.995f) "End" else if (left <= 0) "Less than a minute left" else "$left min left",
                    color = muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                )
                Text("${(progress * 100).roundToInt()}%", color = muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showStyle) ReaderStyleSheet(prefs, current = theme, onDismiss = { showStyle = false })
}

private fun maxScroll(v: WebView): Int = (v.contentHeight * v.resources.displayMetrics.density).roundToInt() - v.height

/** Scrolls by a screen less a line or two, so the last lines stay in view as the first ones. */
private fun turn(v: WebView, dir: Int) {
    val target = (v.scrollY + dir * (v.height * 0.9f).roundToInt()).coerceIn(0, maxOf(0, maxScroll(v)))
    ObjectAnimator.ofInt(v, "scrollY", v.scrollY, target).setDuration(160).start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderStyleSheet(prefs: ReaderPrefs, current: ReaderTheme, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                ThemeSwatch("Auto", null, selected = prefs.theme == null, onClick = { prefs.theme = null })
                ReaderTheme.entries.forEach { t ->
                    ThemeSwatch(t.label, t, selected = prefs.theme == t, onClick = { prefs.theme = t })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontFamily = FontFamily.Serif, fontSize = 14.sp)
                Slider(
                    value = prefs.textSize.toFloat(),
                    onValueChange = { prefs.textSize = it.roundToInt() },
                    valueRange = ReaderPrefs.MIN_SIZE.toFloat()..ReaderPrefs.MAX_SIZE.toFloat(),
                    steps = ReaderPrefs.MAX_SIZE - ReaderPrefs.MIN_SIZE - 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("A", fontFamily = FontFamily.Serif, fontSize = 24.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = prefs.serif, onClick = { prefs.serif = true }, label = { Text("Serif", fontFamily = FontFamily.Serif) })
                FilterChip(selected = !prefs.serif, onClick = { prefs.serif = false }, label = { Text("Sans", fontFamily = FontFamily.SansSerif) })
            }
            SwitchRow("Justify, with hyphens", prefs.justify, { prefs.justify = it })
            SwitchRow("Tap the edges to turn pages", prefs.tapToTurn, { prefs.tapToTurn = it })
            Text("Theme now: ${current.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeSwatch(label: String, theme: ReaderTheme?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        val fill = theme?.let { Color(it.background) } ?: MaterialTheme.colorScheme.surfaceVariant
        Box(
            Modifier.size(44.dp).background(fill, CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = theme?.let { Color(it.text) } ?: MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Serif)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
