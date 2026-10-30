package app.serene.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import app.serene.Haptics
import app.serene.SereneApp
import app.serene.Threshold
import app.serene.browser.Tab
import app.serene.browser.TabManager
import app.serene.browser.TabPreviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** A drag across this share of the width turns the cube a whole face: a thumb's short sweep on the bar is enough. */
private const val SPAN = 0.5f
/** A turn past this share of a face goes on to the next tab when let go; less turns back. */
private const val COMMIT = 0.2f
/** With no tab that way, the cube gives this much of the drag and turns back. */
private const val RESIST = 0.15f
/** How dark a face gets as it turns away, edge on. */
private const val SHADE = 0.55f
/** The camera, in face widths in front of the page: the further, the flatter the cube looks. */
private const val CAMERA = 2f

/**
 * Turning to the tab beside this one, with a sideways drag on the bottom bar (as Chrome does with
 * its toolbar), drawn as a cube, as Instagram turns between stories: the page turns away about
 * one edge and the next tab's page turns in on the cube's next face. A drag to the left turns to
 * the next tab in the switcher's order, to the right to the one before it.
 *
 * The faces are pictures: the current page is taken (PixelCopy) as the drag starts, and the tab
 * turned to shows its tab switcher picture ([TabPreviews]), or its start page or title. Once the
 * turn ends, that face covers the tab's page until its WebView has drawn, then fades into it.
 *
 * The drag buzzes as it passes the point where letting go turns to the tab beside, and as it
 * comes back under it; with no tab that way, it never gets there.
 */
class TabCube(private val tabs: TabManager, private val scope: CoroutineScope) {
    /** How far the cube has turned: 0 is the tab the drag started on, 1 the next one, -1 the one before. */
    var turn by mutableFloatStateOf(0f)
        private set
    /** The tab the drag started on, until the turn ends. */
    var from by mutableStateOf<Tab?>(null)
        private set
    /** The page as the drag started, at full resolution; null for the switcher's picture of [from]. */
    var picture by mutableStateOf<Bitmap?>(null)
        private set
    /** The cube is drawn: from when the page's picture is taken until the page it ends on shows. */
    var showing by mutableStateOf(false)
        private set
    /** The turn has ended on [arrived]: its face covers its page until the page has drawn. */
    var covering by mutableStateOf(false)
        private set
    /** The cover's opacity while it fades into the page. */
    var coverAlpha by mutableFloatStateOf(1f)
        private set
    /** The last tab a turn ended on. Not given the keyboard on arrival, as a new tab is otherwise. */
    var arrived by mutableStateOf<Tab?>(null)
        private set

    /** The page's width and its place in the window, in px; set by the screen. */
    var width = 0f
    var bounds = Rect.Zero
    /** A fast drag turns even when short, in px per second; set by the screen. */
    var flingVelocity = 1500f

    private var dragging = false
    private var dx = 0f
    private var job: Job? = null
    // Where the cube is turning to after the finger let go: 1, -1, or 0 back.
    private var target = 0
    // Whether the cube counts in TabPreviews.covered now.
    private var covers = false
    private val main = Handler(Looper.getMainLooper())
    // The window's view, for the buzz.
    private var view: View? = null
    private val commit = Threshold { on -> view?.let { Haptics.threshold(it, on) } }

    /** The tab [dir] (1 or -1) from the one the drag started on, if there is one. */
    fun beside(dir: Int): Tab? {
        val i = tabs.tabs.indexOf(from ?: return null)
        return if (i < 0 || dir == 0) null else tabs.tabs.getOrNull(i + dir)
    }

    /**
     * A drag has started on the bar. Takes the page's picture; the cube shows once it is taken.
     * During a turn the finger takes the cube where it is, so tabs can be flicked through quickly.
     */
    fun start(window: Window?) {
        view = window?.decorView
        if (from != null) return resume()
        val tab = tabs.current
        if (tab == null || width <= 0f || window == null || tab.swipe.active || tab.pull.active) return
        from = tab
        dragging = true
        dx = 0f
        turn = 0f
        commit.set()
        // Its card in the switcher, as it is now: leaving the tab under the cube would take the cube.
        TabPreviews.capture(tab)
        copy(window, bounds) { bmp ->
            if (from !== tab || bmp == null) return@copy
            picture = bmp
            showing = true
            TabPreviews.cover(true)
            covers = true
        }
    }

    /**
     * A drag during a turn. Turning back, the finger catches the cube. Turning on, or once
     * there, the tab turned to is the one dragged from now, its face as the switcher's picture
     * (what the face showed), and the cube stays where it is.
     */
    private fun resume() {
        if (dragging || !showing) return
        job?.cancel()
        job = null
        if (covering || target != 0) {
            val to = (if (covering) arrived else beside(target)) ?: return clear()
            if (covering) {
                turn = 0f
            } else {
                if (tabs.current !== from || to !in tabs.tabs) return clear()
                arrived = to
                tabs.select(to)
                turn -= target
            }
            covering = false
            from = to
            picture = null
            target = 0
        }
        dragging = true
        val back = if (beside(turn.sign.toInt()) == null) RESIST else 1f
        dx = -turn * width * SPAN / back
        commit.set(turns())
    }

    /** The finger moved [delta] px sideways. */
    fun drag(delta: Float) {
        if (!dragging) return
        dx += delta
        var t = -dx / (width * SPAN)
        if (beside(t.sign.toInt()) == null) t *= RESIST
        turn = t.coerceIn(-1f, 1f)
        commit.update(turns())
    }

    /** Let go now, the cube would turn on to the tab beside. */
    private fun turns() = abs(turn) > COMMIT && beside(turn.sign.toInt()) != null

    /** The finger let go at [velocity] px/s: on to the tab beside, or back. */
    fun release(velocity: Float) {
        if (!dragging) return
        dragging = false
        val dir = turn.sign.toInt()
        val to = beside(dir)
        val v = -velocity
        val go = to != null && showing && if (abs(v) > flingVelocity) v.sign.toInt() == dir else abs(turn) > COMMIT
        target = if (go) dir else 0
        job = scope.launch {
            // Critically damped: no swing past the face, and a fling keeps its speed.
            animate(turn, target.toFloat(), (v / (width * SPAN)).coerceIn(-6f, 6f), spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 0.001f)) { t, _ ->
                turn = t.coerceIn(-1f, 1f)
            }
            if (go) arrive(to) else clear()
        }
    }

    /** The turn has ended on [to]: switch to it under its face, then uncover its page once drawn. */
    private suspend fun arrive(to: Tab) {
        if (tabs.current !== from || to !in tabs.tabs) return clear()
        arrived = to
        coverAlpha = 1f
        covering = true
        target = 0
        tabs.select(to)
        val page = to.hasPage && !to.downloadPage && to.url.isNotEmpty() && to.url != "about:blank"
        if (page) awaitDrawn(to) else repeat(2) { withFrameNanos { } }
        animate(1f, 0f, animationSpec = tween(120)) { a, _ -> coverAlpha = a }
        clear()
    }

    /** Stops at once, the page as it is: the screen is going. */
    fun reset() {
        job?.cancel()
        clear()
    }

    private fun clear() {
        job = null
        dragging = false
        from = null
        picture = null
        showing = false
        target = 0
        covering = false
        turn = 0f
        if (covers) TabPreviews.cover(false)
        covers = false
    }

    /** The window's pixels in [rect], at full resolution, as the page will be under the cube. */
    private fun copy(window: Window, rect: Rect, done: (Bitmap?) -> Unit) {
        val r = android.graphics.Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        if (r.width() <= 0 || r.height() <= 0) return done(null)
        val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, r, bmp, { result -> done(bmp.takeIf { result == PixelCopy.SUCCESS }) }, main)
        } catch (e: IllegalArgumentException) { // window not attached to a surface yet
            done(null)
        }
    }
}

/**
 * The cube over the page: the page the drag started on and the tab beside it on the next face,
 * turned about the edge they share, on black. After the turn, the tab turned to, flat, fading
 * into its page.
 */
@Composable
fun TabCubeFaces(cube: TabCube, app: SereneApp) {
    val from = cube.from
    val arrived = cube.arrived
    // In a Box of their own, always there: as siblings of the page's WebView and start page,
    // the faces changing in the frame the tab changed (the end of a turn) once left a face in
    // the start page's place, and the start page was gone for every tab after.
    Box(Modifier.fillMaxSize()) {
        when {
            !cube.showing || from == null -> Unit
            cube.covering && arrived != null -> Face(arrived, null, app, Modifier.graphicsLayer { alpha = cube.coverAlpha }, shade = { 0f })
            else -> Turning(cube, from, app)
        }
    }
}

/** The page the drag started on and the tab beside it, turned about the edge they share, on black. */
@Composable
private fun Turning(cube: TabCube, from: Tab, app: SereneApp) {
    val picture = cube.picture
    val image = remember(picture) { picture?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Face(from, image, app, Modifier.cubeFace { -cube.turn }, shade = { SHADE * abs(cube.turn) })
        val dir by remember { derivedStateOf { cube.turn.sign.toInt() } }
        cube.beside(dir)?.let { tab ->
            key(tab.id) { Face(tab, null, app, Modifier.cubeFace { dir - cube.turn }, shade = { SHADE * (1f - abs(cube.turn)) }) }
        }
    }
}

/**
 * A face [pos] faces from the front: 0 facing you, 1 the face to the right, -1 to the left. It
 * turns about the edge it shares with the face in front, away from you, as the outside of a cube.
 */
private fun Modifier.cubeFace(pos: () -> Float) = graphicsLayer {
    val p = pos()
    translationX = p * size.width
    // Positive turns the left edge towards you (Cover Flow's cards show it): the far edge goes back.
    rotationY = 90f * p
    transformOrigin = TransformOrigin(if (p < 0f) 1f else 0f, 0.5f)
    // RenderNode's camera distance is in units of 72 px.
    cameraDistance = CAMERA * size.width / 72f
}

/** A tab's page on a face: [image], else its switcher picture, else its start page or title. */
@Composable
private fun Face(tab: Tab, image: ImageBitmap?, app: SereneApp, modifier: Modifier, shade: () -> Float) {
    val preview = TabPreviews.get(tab)
    val shown = image ?: remember(preview) { preview?.asImageBitmap() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when {
            shown != null -> Canvas(Modifier.fillMaxSize()) { drawCover(shown) }
            tab.url.isEmpty() || tab.url == "about:blank" -> StartPage(app)
            else -> TitleCard(tab)
        }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = shade()) })
    }
}
