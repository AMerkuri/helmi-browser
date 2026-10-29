package app.serene.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.R
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.browser.TabManager
import app.serene.browser.TabPreviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** How far the cards beside the centre one are turned, and how much smaller they are (they stand further back). */
private const val SIDE_ANGLE = 65f
private const val SIDE_SCALE = 0.85f
/** The reflection under each card, as a share of the card's height. */
private const val REFLECTION = 0.3f
/** Room above the middle card for its tab's title and site, in dp, and the gap between them and the card. */
private const val TITLE_ROOM = 56f
private const val TITLE_GAP = 12f

/**
 * Where the cards go in a window [width] x [height] px, for pages [aspect] (height / width) tall.
 * Cards have the page's shape, so the centre one grows into the page exactly. CoverFlowTest
 * finds the cards with it too.
 */
data class CoverFlowLayout(
    val cardWidth: Float,
    val cardHeight: Float,
    val centerX: Float,
    val centerY: Float,
    /** From the centre card to the first card beside it. */
    val centerGap: Float,
    /** Between the cards further out, which stack up behind each other. */
    val sideGap: Float,
    /** Above the middle card, for its title and site. */
    val titleRoom: Float,
) {
    /** A drag across this much moves one card: a card's width, so a swipe doesn't run past the next one. */
    val perCard get() = cardWidth

    /** The horizontal distance from the centre of a card [d] places from the centre one. */
    fun x(d: Float): Float {
        val a = abs(d)
        return sign(d) * if (a <= 1f) a * centerGap else centerGap + (a - 1f) * sideGap * SIDE_SCALE
    }

    companion object {
        /** [density] as in DisplayMetrics, for the title's room. */
        fun of(width: Float, height: Float, aspect: Float, density: Float): CoverFlowLayout {
            val title = TITLE_ROOM * density
            // Narrow (a phone, the Fold's cover screen): over half the width. Wide (the Fold open,
            // landscape): less, so more cards show at the sides. Never so tall that the title
            // doesn't fit above it, or the reflection under it. The title above moves the card
            // down by half its room.
            val h = min(height * 0.46f, height * 0.54f - title)
            val w = min(width * if (width > height) 0.42f else 0.58f, h / aspect)
            return CoverFlowLayout(w, w * aspect, width / 2, height * 0.42f + title / 2, w * 0.62f, w * 0.2f, title)
        }
    }
}

/**
 * The open tabs as Cover Flow: each tab a picture of its page, the one in the middle facing you
 * and the others turned towards it on either side, standing on a black floor that reflects them.
 * Drag or fling sideways to flip through; tap a card at the side to bring it to the middle, and
 * the middle one to open it; push it up (or its ✕) to close it.
 *
 * It is drawn over the browser screen: opening it, the page shrinks into its card; opening a
 * tab, the card grows back into the page, over that tab's live WebView, then this goes away.
 * [pageBounds] is where the page is, in the root's coordinates. A new tab's card is its start
 * page, drawn at the page's size and shrunk to the card.
 */
@Composable
fun CoverFlowTabs(app: SereneApp, tabs: TabManager, pageBounds: Rect, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val origin = remember { tabs.current }
    val scroll = remember { Motion(tabs.currentIndex.toFloat(), scope) }
    // 1: the card the switcher opens from or into is the page itself; 0: all cards in the flow.
    val zoom = remember { Animatable(1f) }
    // How opaque the zooming card is. It fades in at the start and out at the end, only while it
    // has the page's place and size, over the live page. A card with the page's picture needs no
    // fade in: it is the page as on screen.
    val cover = remember { Animatable(if (origin != null && TabPreviews.get(origin) != null) 1f else 0f) }
    var zoomId by remember { mutableLongStateOf(origin?.id ?: -1L) }
    // After a tab is closed, the cards after it move up into its place: [gap] goes from 1 to 0;
    // after Undo, from -1 to 0, as they make room for it again.
    val gap = remember { Motion(0f, scope) }
    var gapFrom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // The middle card pushed up to close it.
    val lift = remember { Motion(0f, scope) }
    var liftId by remember { mutableLongStateOf(-1L) }
    var leaving by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val pictures = TabPreviews.version // recompose when a picture comes in

    // Captures now would take this screen instead of the page.
    DisposableEffect(Unit) {
        TabPreviews.cover(true)
        onDispose { TabPreviews.cover(false) }
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.clearFocus() // the address bar, with its keyboard
        cover.animateTo(1f, tween(120))
        zoom.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
    }

    // Light status and navigation bar icons on the black, the theme's again on the way out.
    val window = LocalActivity.current?.window
    val view = LocalView.current
    val dark by remember { derivedStateOf { zoom.value < 0.5f } }
    // Again after a configuration change (folding, unfolding): the activity resets the icons then.
    DisposableEffect(dark, window, LocalConfiguration.current) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val nav = bars?.isAppearanceLightNavigationBars
        if (dark && bars != null) {
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (dark && bars != null) {
                bars.isAppearanceLightStatusBars = status!!
                bars.isAppearanceLightNavigationBars = nav!!
            }
        }
    }

    /** Brings [tab] to the middle if it isn't, then grows it into the page and leaves. */
    fun open(tab: Tab) {
        if (leaving) return
        leaving = true
        scope.launch {
            val i = tabs.tabs.indexOf(tab)
            zoomId = tab.id
            if (i >= 0 && abs(scroll.value - i) > 0.01f) scroll.animateTo(i.toFloat(), spring(dampingRatio = 0.9f, stiffness = 400f)).join()
            // Another tab's WebView goes on screen under the card. Its first frame can hold the
            // main thread for a few hundred ms (a tab not shown since a restart), which skipped
            // the zoom: start it only once the page has drawn.
            if (tabs.current != tab) {
                tabs.select(tab)
                awaitDrawn(tab)
            }
            zoom.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            // The card is the page now; it fades into the live one.
            cover.animateTo(0f, tween(140))
            onDone()
        }
    }

    fun back() {
        val i = tabs.tabs.indexOf(origin)
        open(if (i >= 0) origin!! else tabs.tabs[scroll.value.roundToInt().coerceIn(0, tabs.tabs.lastIndex)])
    }

    fun newTab() {
        if (leaving) return
        // A new card at the end, which flies into the middle and opens.
        val tab = tabs.open()
        open(tab)
    }

    fun close(tab: Tab) {
        val k = tabs.tabs.indexOf(tab)
        if (k < 0 || leaving) return
        scope.launch {
            liftId = tab.id
            lift.animateTo(-2000f, tween(200)).join()
            tabs.close(tab) // opens a new tab if it was the last
            liftId = -1L
            lift.set(0f)
            if (k <= tabs.tabs.lastIndex) {
                // The cards after it move up into its place.
                gapFrom = k
                gap.set(1f)
                val settle = gap.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 300f))
                settle.join()
                if (!settle.isCancelled) gapFrom = Int.MAX_VALUE
            } else {
                // It was the last card: the one before it comes to the middle.
                scroll.animateTo(tabs.tabs.lastIndex.toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f))
            }
        }
    }

    /** Undo on the "Closed …" bar: [tab]'s card drops back into its place, from [height] up, and the cards after it make room. */
    fun reopened(tab: Tab, height: Float) {
        val k = tabs.tabs.indexOf(tab)
        if (k < 0 || leaving) return
        // Set now, before the next frame, so the card never shows in its place before it drops.
        gapFrom = k + 1
        gap.set(-1f)
        liftId = tab.id
        lift.set(-height)
        scroll.animateTo(k.toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f))
        scope.launch {
            val settle = gap.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 300f))
            val drop = lift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 300f))
            settle.join()
            if (!settle.isCancelled) gapFrom = Int.MAX_VALUE
            drop.join()
            if (!drop.isCancelled && liftId == tab.id) liftId = -1L
        }
    }

    // Also while leaving, so a back press then doesn't reach the page under the switcher.
    BackHandler { if (!leaving) back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val page = if (pageBounds.width > 0f && pageBounds.height > 0f) pageBounds else Rect(0f, 0f, width, height)
        val dens = LocalDensity.current
        val layout = remember(width, height, page.width, page.height, dens.density) { CoverFlowLayout.of(width, height, page.height / page.width, dens.density) }
        val cardW = with(dens) { layout.cardWidth.toDp() }
        val cardH = with(dens) { layout.cardHeight.toDp() }
        val reflH = cardH * REFLECTION
        val pageW = with(dens) { page.width.toDp() }
        val pageH = with(dens) { page.height.toDp() }
        val perCard = layout.perCard
        val count = tabs.tabs.size
        DisposableEffect(layout) {
            tabs.onReopen = { reopened(it, layout.cardHeight) }
            onDispose { tabs.onReopen = null }
        }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - zoom.value }.background(Color.Black))
        // While the card is smaller than the page, the page's place stays black around it and
        // only the bars fade: the live page seen through a fading background was a second,
        // full-size page behind the card. Not while the card fades: the black then showed
        // through the card too, and the screen dimmed for a moment (a flicker).
        Box(
            Modifier
                .offset { IntOffset(page.left.roundToInt(), page.top.roundToInt()) }
                .size(with(dens) { page.width.toDp() }, with(dens) { page.height.toDp() })
                .graphicsLayer { alpha = if (cover.value >= 1f) 1f else 0f }
                .background(Color.Black),
        )

        val drag = rememberDraggableState { dx ->
            val last = (tabs.tabs.size - 1).toFloat()
            var to = scroll.value - dx / perCard
            // Past either end it gives way less, and springs back.
            if (to < 0f || to > last) to = scroll.value - dx / perCard * 0.3f
            scroll.set(to)
        }
        Box(
            Modifier.fillMaxSize().draggable(
                drag,
                Orientation.Horizontal,
                enabled = !leaving,
                onDragStopped = { velocity ->
                    val v = -velocity / perCard // cards a second
                    val to = settleOn(scroll.value, v).coerceIn(0, tabs.tabs.lastIndex)
                    scroll.animateTo(to.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f), velocity = v.coerceIn(-4f, 4f))
                },
            ),
        ) {
            // Only the cards that can be on screen.
            val reach = min(ceil((width / 2 - layout.centerGap) / (layout.sideGap * SIDE_SCALE)).toInt() + 2, 12)
            val s = scroll.value
            val first = max(0, floor(s - reach).toInt())
            val last = min(count - 1, ceil(s + reach).toInt())
            for (i in first..last) {
                val tab = tabs.tabs[i]
                key(tab.id) {
                    TabCard(
                        tab = tab,
                        modifier = Modifier
                            .offset { IntOffset((layout.centerX - layout.cardWidth / 2).roundToInt(), (layout.centerY - layout.cardHeight / 2).roundToInt()) }
                            .graphicsLayer {
                                val d = i - scroll.value + if (i >= gapFrom) gap.value else 0f
                                val z = zoom.value
                                val a = min(abs(d), 1f)
                                var tx = layout.x(d)
                                var ty = 0f
                                var sc = 1f - (1f - SIDE_SCALE) * a
                                var rot = -d.coerceIn(-1f, 1f) * SIDE_ANGLE
                                if (tab.id == zoomId) {
                                    // Grows from its place in the flow into the page.
                                    tx += (page.center.x - layout.centerX - tx) * z
                                    ty = (page.center.y - layout.centerY) * z
                                    scaleX = lerp(sc, page.width / layout.cardWidth, z)
                                    scaleY = lerp(sc, page.height / layout.cardHeight, z)
                                    rot *= 1f - z
                                    alpha = cover.value
                                } else {
                                    // The others fly off to their side and fade.
                                    tx += (if (d < 0f) -1f else 1f) * z * width * 0.6f
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = 1f - z
                                }
                                if (tab.id == liftId) {
                                    ty += lift.value
                                    alpha *= (1f + lift.value / layout.cardHeight).coerceIn(0f, 1f)
                                }
                                translationX = tx
                                translationY = ty
                                rotationY = rot
                                cameraDistance = 9f * density
                                // Turn and scale about the card's middle, not the reflection's.
                                transformOrigin = TransformOrigin(0.5f, 0.5f / (1f + REFLECTION))
                            },
                        picture = TabPreviews.get(tab),
                        startPage = { StartPage(app, scrolls = false) },
                        zIndex = if (tab.id == zoomId && zoom.value > 0f) 1000f else -abs(i - s),
                        cardW = cardW,
                        cardH = cardH,
                        reflH = reflH,
                        pageW = pageW,
                        pageH = pageH,
                        flat = { zoom.value },
                        centered = { abs(i - scroll.value) < 0.5f && !leaving },
                        onTap = {
                            when {
                                leaving -> Unit
                                abs(i - scroll.value) < 0.5f -> open(tab)
                                else -> scroll.animateTo(i.toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f))
                            }
                        },
                        onClose = { close(tab) },
                        onLift = { dy ->
                            if (liftId == -1L && abs(i - scroll.value) < 0.5f && !leaving) liftId = tab.id
                            if (liftId == tab.id) lift.set(min(0f, lift.value + dy))
                        },
                        onLiftEnd = { velocity ->
                            if (liftId == tab.id) {
                                if (lift.value < -layout.cardHeight * 0.3f || velocity < -1500f) close(tab)
                                else scope.launch { lift.animateTo(0f, spring()).join(); if (lift.value == 0f) liftId = -1L }
                            }
                        },
                    )
                }
            }
        }

        // The middle tab's title, as Cover Flow showed the album's, but above the card: under it,
        // the thumb flipping through the cards covered it.
        val middle by remember { derivedStateOf { scroll.value.roundToInt().coerceIn(0, (tabs.tabs.size - 1).coerceAtLeast(0)) } }
        val titleBottom = layout.centerY - layout.cardHeight / 2 - with(dens) { TITLE_GAP.dp.toPx() }
        tabs.tabs.getOrNull(middle)?.let { tab ->
            Column(
                Modifier
                    .fillMaxWidth()
                    // Its last line just above the card, however tall the text is.
                    .layout { m, c ->
                        val p = m.measure(c.copy(minHeight = 0))
                        this.layout(p.width, p.height) { p.place(0, (titleBottom - p.height).roundToInt()) }
                    }
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        // Fades as the card is pushed up over it to close.
                        val lifted = if (tab.id == liftId) (1f + lift.value / layout.cardHeight).coerceIn(0f, 1f) else 1f
                        alpha = (1f - 2f * abs(scroll.value - middle)).coerceIn(0f, 1f) * (1f - zoom.value) * lifted
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(cardTitle(tab), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                host(tab)?.let { Text(it, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        Text(
            if (count == 1) "1 tab" else "$count tabs",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp).graphicsLayer { alpha = 1f - zoom.value },
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer { alpha = 1f - zoom.value },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::newTab, enabled = !leaving) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
                Spacer(Modifier.size(6.dp))
                Text("New tab", color = Color.White)
            }
            TextButton(onClick = ::back, enabled = !leaving) { Text("Done", color = Color.White) }
        }
    }
}

@Composable
private fun TabCard(
    tab: Tab,
    modifier: Modifier,
    picture: Bitmap?,
    startPage: @Composable () -> Unit,
    zIndex: Float,
    cardW: androidx.compose.ui.unit.Dp,
    cardH: androidx.compose.ui.unit.Dp,
    reflH: androidx.compose.ui.unit.Dp,
    pageW: androidx.compose.ui.unit.Dp,
    pageH: androidx.compose.ui.unit.Dp,
    flat: () -> Float,
    centered: () -> Boolean,
    onTap: () -> Unit,
    onClose: () -> Unit,
    onLift: (Float) -> Unit,
    onLiftEnd: suspend (Float) -> Unit,
) {
    val image = remember(picture) { picture?.asImageBitmap() }
    // A blank tab shows the start page, as BrowserScreen does; it has no picture.
    val blank = image == null && !tab.openedByPage && !tab.downloadPage && (tab.url.isEmpty() || tab.url == "about:blank")
    val placeholder = if (blank) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    val lift = rememberDraggableState(onLift)
    Column(
        modifier
            .zIndex(zIndex)
            .semantics { contentDescription = "Tab: ${cardTitle(tab)}" }
            .clickable(interactionSource = null, indication = null, onClick = onTap)
            .draggable(lift, Orientation.Vertical, onDragStopped = { onLiftEnd(it) }),
    ) {
        Box(
            Modifier
                .size(cardW, cardH)
                .graphicsLayer {
                    shape = RoundedCornerShape(8.dp * (1f - flat()))
                    clip = true
                }
                .background(placeholder),
        ) {
            if (image != null) Canvas(Modifier.fillMaxSize()) { drawCover(image) }
            else if (blank) {
                // Laid out as on screen, then shrunk to the card, so it is the start page exactly.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(pageW, pageH)
                        .graphicsLayer {
                            scaleX = cardW / pageW
                            scaleY = cardH / pageH
                        },
                ) { startPage() }
            } else TitleCard(tab)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .graphicsLayer { alpha = if (centered()) 1f - flat() else 0f }
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .clickable { if (centered()) onClose() else onTap() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "Close tab", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        // The floor's reflection: the card upside down, with the card's rounded corners at the
        // top, fading into the black. Opaque, so the reflection of a card in front hides the one
        // behind it as the cards themselves do; see-through ones added up where cards overlap.
        Canvas(
            Modifier
                .size(cardW, reflH)
                .graphicsLayer {
                    val r = 8.dp * (1f - flat())
                    shape = RoundedCornerShape(topStart = r, topEnd = r)
                    clip = true
                    alpha = 1f - flat()
                },
        ) {
            val h = cardH.toPx()
            drawRect(Color.Black)
            withTransform({ scale(1f, -1f, pivot = Offset(size.width / 2, h / 2)) }) {
                if (image != null) drawCover(image, h) else drawRect(placeholder, size = size.copy(height = h))
            }
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black))
        }
    }
}

/** A tab without a picture (a new tab, a page not yet seen): its site and title. */
@Composable
internal fun TitleCard(tab: Tab) {
    val host = host(tab)
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (host == null) {
            Icon(painterResource(R.drawable.ic_shield), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        } else {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(host.removePrefix("www.").take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(cardTitle(tab), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** The picture filling the card, cut to the card's shape from the top, as the page sits under the address bar. */
internal fun DrawScope.drawCover(image: ImageBitmap, height: Float = size.height) {
    val w = size.width
    val s = max(w / image.width, height / image.height)
    val srcW = (w / s).roundToInt().coerceAtMost(image.width)
    val srcH = (height / s).roundToInt().coerceAtMost(image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcW) / 2, 0),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(w.roundToInt(), height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * A value moved by the finger and by animations. The finger sets it at once and stops any
 * animation. (With Animatable each drag step was a snapTo coroutine of its own; one that ran
 * after the finger lifted cancelled the settling animation, and the cards stopped half way.)
 */
private class Motion(initial: Float, private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(initial)
        private set
    private var job: Job? = null

    fun set(v: Float) {
        job?.cancel()
        job = null
        value = v
    }

    fun animateTo(target: Float, spec: AnimationSpec<Float>, velocity: Float = 0f): Job {
        job?.cancel()
        return scope.launch { animate(value, target, velocity, spec) { v, _ -> value = v } }.also { job = it }
    }
}

/**
 * The card a drag let go at [at] with [velocity] (cards a second) settles on. A flick moves one
 * card on, as a pager does; only a hard one goes further, a card for each 10 cards a second,
 * 3 at most. (Projecting the fling's distance skipped several cards on a slight flick; a card
 * for each 6 a second still skipped one on an ordinary swipe.)
 */
fun settleOn(at: Float, velocity: Float): Int = when {
    abs(velocity) < 1f -> at.roundToInt()
    velocity > 0f -> floor(at).toInt() + 1 + min((velocity / 10f).toInt(), 3)
    else -> ceil(at).toInt() - 1 - min((-velocity / 10f).toInt(), 3)
}

/**
 * Waits until [tab]'s WebView, just put on screen, has drawn its page: a frame for it to be
 * attached, then until its content is ready to draw, then that frame. At most 800 ms.
 */
internal suspend fun awaitDrawn(tab: Tab) {
    withTimeoutOrNull(800) {
        withFrameNanos { }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK) && tab.webView.isAttachedToWindow) {
            suspendCancellableCoroutine { c -> WebViewCompat.postVisualStateCallback(tab.webView, 0) { if (c.isActive) c.resume(Unit) } }
        }
        withFrameNanos { }
    }
}

/** What a tab is called on its card and in the "Closed …" bar: its title, else its site. */
internal fun cardTitle(tab: Tab) = tab.title.ifBlank { host(tab) ?: "New tab" }

private fun host(tab: Tab) = tab.url.takeIf { it.startsWith("http") }?.let { android.net.Uri.parse(it).host }

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

