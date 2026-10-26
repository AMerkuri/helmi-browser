package app.serene.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import app.serene.SereneApp
import app.serene.browser.Tab
import app.serene.browser.TabManager
import app.serene.browser.TabPreviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** A drag past this share of the width turns to the next tab when let go; less turns back. */
private const val COMMIT = 0.3f
/** With no tab that way, the cube gives this much of the drag and turns back. */
private const val RESIST = 0.15f
/** How dark a face gets as it turns away, edge on. */
private const val SHADE = 0.55f
/** The camera, in face widths in front of the page: the further, the flatter the cube looks. */
private const val CAMERA = 2f
/** Half way through a turn, the cube is this much smaller again than just fitting the page, so it stands clear of the edges. */
private const val MARGIN = 0.05f

/**
 * Turning to the tab beside this one, with a sideways drag on the bottom bar (as Chrome does with
 * its toolbar), drawn as a cube, as Instagram turns between stories: the cube turns about its
 * middle, the page going round one side as the next tab's page comes round on the next face,
 * and the cube shrinks while it turns so all of it stays in view. A drag to the left turns to
 * the next tab in the switcher's order, to the right to the one before it.
 *
 * The faces are pictures: the current page is taken (PixelCopy) as the drag starts, and the tab
 * turned to shows its tab switcher picture ([TabPreviews]), or its start page or title. Once the
 * turn ends, that face covers the tab's page until its WebView has drawn, then fades into it.
 */
class TabCube(private val tabs: TabManager, private val scope: CoroutineScope) {
    /** How far the cube has turned: 0 is the tab the drag started on, 1 the next one, -1 the one before. */
    var turn by mutableFloatStateOf(0f)
        private set
    /** The tab the drag started on, until the turn ends. */
    var from by mutableStateOf<Tab?>(null)
        private set
    /** The page as the drag started. The cube is drawn once there is one. */
    var picture by mutableStateOf<Bitmap?>(null)
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
    // Whether the cube counts in TabPreviews.covered now.
    private var covers = false
    private val main = Handler(Looper.getMainLooper())

    /** The tab [dir] (1 or -1) from the one the drag started on, if there is one. */
    fun beside(dir: Int): Tab? {
        val i = tabs.tabs.indexOf(from ?: return null)
        return if (i < 0 || dir == 0) null else tabs.tabs.getOrNull(i + dir)
    }

    /** A drag has started on the bar. Takes the page's picture; the cube shows once it is taken. */
    fun start(window: Window?) {
        val tab = tabs.current
        if (from != null || tab == null || width <= 0f || window == null || tab.swipe.active || tab.pull.active) return
        from = tab
        dragging = true
        dx = 0f
        turn = 0f
        // Its card in the switcher, as it is now: leaving the tab under the cube would take the cube.
        TabPreviews.capture(tab)
        copy(window, bounds) { bmp ->
            if (from !== tab || bmp == null) return@copy
            picture = bmp
            TabPreviews.cover(true)
            covers = true
        }
    }

    /** The finger moved [delta] px sideways. */
    fun drag(delta: Float) {
        if (!dragging) return
        dx += delta
        var t = -dx / width
        if (beside(t.sign.toInt()) == null) t *= RESIST
        turn = t.coerceIn(-1f, 1f)
    }

    /** The finger let go at [velocity] px/s: on to the tab beside, or back. */
    fun release(velocity: Float) {
        if (!dragging) return
        dragging = false
        val dir = turn.sign.toInt()
        val to = beside(dir)
        val v = -velocity
        val go = to != null && picture != null && if (abs(v) > flingVelocity) v.sign.toInt() == dir else abs(turn) > COMMIT
        val target = if (go) dir.toFloat() else 0f
        job = scope.launch {
            // Critically damped: no swing past the face, and a fling keeps its speed.
            animate(turn, target, (v / width).coerceIn(-6f, 6f), spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 0.001f)) { t, _ ->
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
 * the cube turning about its middle, on black. Turning, its front edge comes towards you, so it
 * shrinks to keep all of it on the page, and grows back as the next face comes to the front.
 * After the turn, the tab turned to, flat, fading into its page.
 */
@Composable
fun TabCubeFaces(cube: TabCube, app: SereneApp) {
    val picture = cube.picture ?: return
    val image = remember(picture) { picture.asImageBitmap() }
    val from = cube.from ?: return
    val arrived = cube.arrived
    if (cube.covering && arrived != null) {
        Face(arrived, null, app, Modifier.graphicsLayer { alpha = cube.coverAlpha }, shade = { 0f })
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val dir by remember { derivedStateOf { cube.turn.sign.toInt() } }
        // The tab beside only while there is one: at the end of the tabs the page turns alone.
        val beside = cube.beside(dir)
        val faces = { if (beside != null) intArrayOf(0, dir) else intArrayOf(0) }
        Face(from, image, app, Modifier.onCube(0, { cube.turn }, faces), shade = { SHADE * abs(cube.turn) })
        beside?.let { tab ->
            key(tab.id) { Face(tab, null, app, Modifier.onCube(dir, { cube.turn }, faces), shade = { SHADE * (1f - abs(cube.turn)) }) }
        }
    }
}

/**
 * Draws this as face [k] of the cube (0 the page the drag started on, 1 the next tab's, -1 the
 * one before), the cube [turn]ed: a perspective transform from the page's rectangle onto where
 * the face's corners are on screen. [faces] are the faces drawn, which the cube is sized to fit.
 */
private fun Modifier.onCube(k: Int, turn: () -> Float, faces: () -> IntArray) = drawWithContent {
    val t = turn()
    val w = size.width
    val h = size.height
    val corners = cubeCorners((k - t) * 90f, w, h) ?: return@drawWithContent
    // Scaled about the page's centre until every face drawn is inside the page, and a little
    // more half way.
    var reachX = w / 2
    var reachY = h / 2
    for (f in faces()) {
        val c = cubeCorners((f - t) * 90f, w, h) ?: continue
        for (i in 0 until 4) {
            reachX = max(reachX, abs(c[2 * i] - w / 2))
            reachY = max(reachY, abs(c[2 * i + 1] - h / 2))
        }
    }
    val s = min(w / 2 / reachX, h / 2 / reachY) * (1f - MARGIN * sin(PI.toFloat() * abs(t)))
    for (i in 0 until 4) {
        corners[2 * i] = w / 2 + (corners[2 * i] - w / 2) * s
        corners[2 * i + 1] = h / 2 + (corners[2 * i + 1] - h / 2) * s
    }
    val m = android.graphics.Matrix()
    m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, corners, 0, 4)
    drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(m) }
    drawContent()
    drawIntoCanvas { it.nativeCanvas.restore() }
}

/**
 * Where the corners of a face [angle] degrees round the cube from the front are on screen
 * (top left, top right, bottom right, bottom left), for faces [w] x [h]; null when it faces away.
 * The cube's axis is upright, half a face behind the page's middle; positive angles are to the
 * right. The camera is [CAMERA] face widths in front of the page, over its middle.
 */
private fun cubeCorners(angle: Float, w: Float, h: Float): FloatArray? {
    if (abs(angle) >= 89.9f) return null
    val a = Math.toRadians(angle.toDouble())
    val cos = cos(a).toFloat()
    val sin = sin(a).toFloat()
    val camera = CAMERA * w
    val out = FloatArray(8)
    for (i in 0 until 4) {
        val u = if (i == 0 || i == 3) -w / 2 else w / 2
        val v = if (i < 2) -h / 2 else h / 2
        // Across the face from its middle (u), turned round the axis; z is away from you.
        val x = u * cos + w / 2 * sin
        val z = w / 2 - w / 2 * cos + u * sin
        val p = camera / (camera + z)
        out[2 * i] = w / 2 + x * p
        out[2 * i + 1] = h / 2 + v * p
    }
    return out
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
