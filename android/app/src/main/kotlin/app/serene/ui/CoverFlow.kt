package app.serene.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.serene.Haptics
import app.serene.R
import app.serene.SereneApp
import app.serene.Threshold
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
import kotlin.math.exp
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
/** The middle card pushed up this share of its height closes when let go. */
private const val LIFT_CLOSES = 0.3f
/**
 * Room above the middle card for its tab's title and site, in dp, the gap between them and the
 * card, and how far the card sits lower than it would without them.
 */
private const val TITLE_ROOM = 68f
private const val TITLE_GAP = 24f
private const val CARD_DROP = 40f
/**
 * The tab scrollbar under the cards, in dp: the strip you grab, how far under the reflection it
 * sits, the longest its track gets, its knob's height and narrowest and widest, and the bottom
 * buttons' row it stays above.
 */
private const val HANDLE_HEIGHT = 48f
private const val HANDLE_GAP = 8f
private const val HANDLE_TRACK = 280f
private const val KNOB_HEIGHT = 8f
private const val KNOB_MIN = 24f
private const val KNOB_MAX = 64f
private const val BUTTONS = 56f
/** How quickly the cards catch up with the scrollbar's knob, in seconds: quick, but a jump glides. */
private const val SCROLL_CATCH_UP = 0.045f

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
    /** The middle of the tab scrollbar's strip, under the reflection, and its track's width. */
    val handleY: Float,
    val handleWidth: Float,
) {
    /** A drag across this much moves one card: a card's width, so a swipe doesn't run past the next one. */
    val perCard get() = cardWidth

    /** The horizontal distance from the centre of a card [d] places from the centre one. */
    fun x(d: Float): Float {
        val a = abs(d)
        return sign(d) * if (a <= 1f) a * centerGap else centerGap + (a - 1f) * sideGap * SIDE_SCALE
    }

    companion object {
        /**
         * [density] as in DisplayMetrics, for the title's room. [navBottom] is the navigation
         * bar's height, which the bottom buttons sit on; the handle stays above them.
         */
        fun of(width: Float, height: Float, aspect: Float, density: Float, navBottom: Float = 0f): CoverFlowLayout {
            val title = TITLE_ROOM * density
            // Narrow (a phone, the Fold's cover screen): over half the width. Wide (the Fold open,
            // landscape): less, so more cards show at the sides. Never so tall that the title
            // doesn't fit above it, or the reflection under it. The title above moves the card
            // down, by a little over half its room.
            val h = min(height * 0.46f, height * 0.54f - title)
            val w = min(width * if (width > height) 0.42f else 0.58f, h / aspect)
            val y = height * 0.42f + CARD_DROP * density
            // Just under the reflection, where it fades out; on a low screen (the cover screen
            // turned sideways) over its faded end, so it stays above the buttons.
            val handleY = min(
                y + w * aspect * (0.5f + REFLECTION) + (HANDLE_GAP + HANDLE_HEIGHT / 2) * density,
                height - navBottom - (BUTTONS + HANDLE_HEIGHT / 2) * density,
            )
            val track = min(width * 0.7f, HANDLE_TRACK * density)
            return CoverFlowLayout(w, w * aspect, width / 2, y, w * 0.62f, w * 0.2f, title, handleY, track)
        }
    }
}

/**
 * The open tabs as Cover Flow: each tab a picture of its page, the one in the middle facing you
 * and the others turned towards it on either side, standing on a black floor that reflects them.
 * Drag or fling sideways to flip through; tap a card at the side to bring it to the middle, and
 * the middle one to open it; push it up (or its ✕) to close it. The scrollbar under the cards
 * shows where the middle card is among all the tabs; drag its knob, or tap the track, to go
 * straight to a tab by its place. Each card that comes to the middle ticks, as a picker
 * wheel does, and a card pushed up buzzes once it is far enough to close.
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
    // Where the tab scrollbar's knob is held, in tabs (the cards catch up with it); null when it isn't.
    var held by remember { mutableStateOf<Float?>(null) }
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

    val view = LocalView.current
    // A tick for each card that comes to the middle. Not while a card flies there to open: that
    // is no longer the user flipping through.
    LaunchedEffect(Unit) {
        var last = tabs.currentIndex
        snapshotFlow { scroll.value.roundToInt() }.collect { i ->
            // Past either end (the flow giving way) is no card.
            if (i !in tabs.tabs.indices || i == last) return@collect
            last = i
            if (!leaving) Haptics.tick(view)
        }
    }
    val liftCloses = remember { Threshold { Haptics.threshold(view, it) } }

    // Light status and navigation bar icons on the black, the theme's again on the way out.
    val dark by remember { derivedStateOf { zoom.value < 0.5f } }
    SystemBarIcons(dark = if (dark) true else null)

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
        val navBottom = WindowInsets.navigationBars.getBottom(dens).toFloat()
        val layout = remember(width, height, page.width, page.height, dens.density, navBottom) { CoverFlowLayout.of(width, height, page.height / page.width, dens.density, navBottom) }
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
                            if (liftId == -1L && abs(i - scroll.value) < 0.5f && !leaving) {
                                liftId = tab.id
                                liftCloses.set()
                            }
                            if (liftId == tab.id) {
                                lift.set(min(0f, lift.value + dy))
                                liftCloses.update(lift.value < -layout.cardHeight * LIFT_CLOSES)
                            }
                        },
                        onLiftEnd = { velocity ->
                            if (liftId == tab.id) {
                                liftCloses.set()
                                if (lift.value < -layout.cardHeight * LIFT_CLOSES || velocity < -1500f) close(tab)
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
                if (tab.isPrivate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_private), null, tint = PRIVATE_MARK, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Private", color = PRIVATE_MARK, style = MaterialTheme.typography.labelMedium)
                    }
                }
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

        // The tab scrollbar: the knob's place along the track is the middle card's place among
        // the tabs, and dragging it takes the cards there, a tab for each step of a knob's width
        // (fewer tabs, longer steps). A press on the track off the knob brings the knob under the
        // finger, and the cards glide there. Let go, the cards settle on the nearest tab.
        val scrolls = count > 1 && !leaving
        val track = layout.handleWidth
        val knobW = knobWidth(track, count, dens.density)
        // Closing a tab or Undo changes the count: the knob slides and resizes rather than jumps.
        val shownCount by animateFloatAsState(count.toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f), label = "tab count")
        val shownKnob by animateFloatAsState(knobW, spring(dampingRatio = 0.85f, stiffness = 300f), label = "knob width")
        LaunchedEffect(scrolls) { if (!scrolls) held = null }
        ScrollBar(
            modifier = Modifier
                .offset { IntOffset(((width - track) / 2).roundToInt(), (layout.handleY - HANDLE_HEIGHT / 2 * density).roundToInt()) }
                .size(with(dens) { track.toDp() }, HANDLE_HEIGHT.dp)
                .graphicsLayer { alpha = if (count > 1) 1f - zoom.value else 0f }
                .semantics {
                    contentDescription = "Tab scrollbar: drag it or tap along it to go to a tab"
                    stateDescription = "Tab ${middle + 1} of $count"
                    if (scrolls) {
                        progressBarRangeInfo = ProgressBarRangeInfo(middle.toFloat(), 0f..(count - 1).toFloat(), steps = max(count - 2, 0))
                        setProgress { v ->
                            scroll.animateTo(v.roundToInt().coerceIn(0, count - 1).toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f))
                            true
                        }
                    }
                }
                .pointerInput(scrolls, count, knobW, track) {
                    if (!scrolls) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // Grabbed on the knob, it stays where the finger took it; off it, it comes under the finger.
                        val at = knobCenter(scroll.value, count.toFloat(), track, knobW)
                        val grab = if (abs(down.position.x - at) <= knobW / 2 + 8.dp.toPx()) down.position.x - at else 0f
                        held = scrollAt(down.position.x - grab, count, track, knobW)
                        val follow = scope.launch {
                            var last = withFrameNanos { it }
                            while (true) {
                                val now = withFrameNanos { it }
                                val to = held ?: break
                                // A long frame (a page drawing behind) doesn't jump the cards.
                                val dt = min((now - last) / 1e9f, 0.05f)
                                last = now
                                val d = to - scroll.value
                                if (d != 0f) scroll.set(if (abs(d) < 0.002f) to else scroll.value + d * (1f - exp(-dt / SCROLL_CATCH_UP)))
                            }
                        }
                        try {
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                                held = scrollAt(c.position.x - grab, count, track, knobW)
                            }
                        } finally {
                            follow.cancel()
                            val to = held
                            held = null
                            if (to != null && !leaving) {
                                scroll.animateTo(to.roundToInt().coerceIn(0, tabs.tabs.lastIndex).toFloat(), spring(dampingRatio = 0.85f, stiffness = 300f))
                            }
                        }
                    }
                },
            // Held, the knob is under the finger; otherwise where the cards are.
            center = { held?.let { knobCenter(it, count.toFloat(), track, knobW) } ?: knobCenter(scroll.value, shownCount, track, shownKnob) },
            knobWidth = { shownKnob },
            held = { held != null },
        )
    }
}

/**
 * A faint track with a plain, muted grey knob: [center] is where the knob's middle is from the
 * track's left end and [knobWidth] its width, in px; [held] lights it a little while the finger
 * is on it. Thin to look at; the strip you grab is taller.
 */
@Composable
private fun ScrollBar(modifier: Modifier, center: () -> Float, knobWidth: () -> Float, held: () -> Boolean) {
    Canvas(modifier) {
        val midY = size.height / 2
        val knob = Size(knobWidth(), KNOB_HEIGHT.dp.toPx())
        val trackH = knob.height + 2.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.1f),
            topLeft = Offset(0f, midY - trackH / 2),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2),
        )
        val at = Offset(center() - knob.width / 2, midY - knob.height / 2)
        drawRoundRect(Color(if (held()) 0xFFA0A0A0 else 0xFF7A7A7A), topLeft = at, size = knob, cornerRadius = CornerRadius(knob.height / 2))
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
    val placeholder = if (tab.isPrivate) PRIVATE_CARD else MaterialTheme.colorScheme.surface
    val lift = rememberDraggableState(onLift)
    Column(
        modifier
            .zIndex(zIndex)
            .semantics { contentDescription = "${if (tab.isPrivate) "Private tab" else "Tab"}: ${cardTitle(tab)}" }
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
            else {
                // Laid out as on screen, then shrunk to the card, so it is what the cube and the page show.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(pageW, pageH)
                        .graphicsLayer {
                            scaleX = cardW / pageW
                            scaleY = cardH / pageH
                        },
                ) { PageFallback(tab, startPage) }
            }
            // A private tab's mark, on every card, so they stand out among the others.
            if (tab.isPrivate) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(36.dp)
                        .graphicsLayer { alpha = 1f - flat() }
                        .background(PRIVATE_CARD.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_private), "Private tab", tint = PRIVATE_MARK, modifier = Modifier.size(22.dp))
                }
            }
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

/**
 * A tab without a picture, at page size: a blank tab's start page (as BrowserScreen shows it),
 * else its site and title. The cube's faces show it as it is and Cover Flow's cards shrink it.
 */
@Composable
internal fun PageFallback(tab: Tab, startPage: @Composable () -> Unit) {
    PrivateTheme(tab.isPrivate) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (!tab.openedByPage && !tab.downloadPage && (tab.url.isEmpty() || tab.url == "about:blank")) {
                if (tab.isPrivate) PrivateStartPage(scrolls = false) else startPage()
            } else TitleCard(tab)
        }
    }
}

/** A tab without a picture (a page not yet seen): its site and title. */
@Composable
private fun TitleCard(tab: Tab) {
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
 * card on, as a pager does; only a hard one goes further, a card for each 8 cards a second,
 * 3 at most. (Projecting the fling's distance skipped several cards on a slight flick; a card
 * for each 6 a second still skipped one on an ordinary swipe, and 10 took too hard a flick.)
 */
fun settleOn(at: Float, velocity: Float): Int = when {
    abs(velocity) < 1f -> at.roundToInt()
    velocity > 0f -> floor(at).toInt() + 1 + min((velocity / 8f).toInt(), 3)
    else -> ceil(at).toInt() - 1 - min((-velocity / 8f).toInt(), 3)
}

/**
 * The tab scrollbar's knob for [count] tabs on a [track] px track: a tab's share of the track, as
 * a scrollbar's knob is the share on screen, but no narrower than a fingertip finds (24 dp) and
 * no wider than 64 dp, so with few tabs it still moves a long way.
 */
fun knobWidth(track: Float, count: Int, density: Float): Float =
    min(track / max(count, 1), KNOB_MAX * density).coerceAtLeast(min(KNOB_MIN * density, track))

/**
 * Where the middle of a [knob] px wide knob is, in px from the left end of a [track] px track,
 * with the cards at [at] (a tab's index; between two while they move) of [count] tabs: the
 * first tab at the left end, the last at the right.
 */
fun knobCenter(at: Float, count: Float, track: Float, knob: Float): Float {
    val last = count - 1f
    val t = if (last <= 0f) 0.5f else (at / last).coerceIn(0f, 1f)
    return knob / 2 + t * max(track - knob, 0f)
}

/** The other way: where the cards are, in tabs, with the knob's middle at [center] px. */
fun scrollAt(center: Float, count: Int, track: Float, knob: Float): Float {
    if (count <= 1 || track <= knob) return 0f
    return ((center - knob / 2) / (track - knob)).coerceIn(0f, 1f) * (count - 1)
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
internal fun cardTitle(tab: Tab) = tab.title.ifBlank { host(tab) ?: if (tab.isPrivate) "New private tab" else "New tab" }

/** A private tab's card behind its page, and its mark: the private tabs' blue and brass (Theme.kt). */
private val PRIVATE_CARD = Color(0xFF1A1F2B)
private val PRIVATE_MARK = Color(0xFFD9A441)

private fun host(tab: Tab) = tab.url.takeIf { it.startsWith("http") }?.let { android.net.Uri.parse(it).host }

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

