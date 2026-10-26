package app.serene.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.serene.browser.HistorySwipe
import app.serene.browser.Tab
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Android's back gesture goes back through the tab's history with the same animation as the
 * in-page swipe: the page follows the finger from either edge. A back button press (three-button
 * navigation) sends no progress and just goes back.
 */
@Composable
fun HistoryBackHandler(tab: Tab) {
    val swipe = tab.swipe
    PredictiveBackHandler(enabled = tab.canGoBack) { events ->
        var started = false
        var startX = 0f
        try {
            events.collect { e ->
                if (!started && !swipe.active) {
                    started = swipe.start(HistorySwipe.BACK, fromRight = e.swipeEdge == BackEventCompat.EDGE_RIGHT)
                    startX = e.touchX
                }
                if (started) swipe.drag(e.touchX - startX)
            }
            if (started) swipe.finish(commit = true) else if (!swipe.active) tab.goBack()
        } catch (e: CancellationException) {
            if (started) swipe.cancel()
            throw e
        }
    }
}

/** The page the swipe goes to, under the current page (back) or sliding in over it (forward). */
@Composable
fun SwipeTarget(swipe: HistorySwipe) {
    val shot = swipe.target
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(swipe.targetOffset.roundToInt(), 0) }
            .graphicsLayer { alpha = if (swipe.covering) swipe.coverAlpha else 1f }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (shot != null) {
            Image(
                shot.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                swipe.targetLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        // Dimmed while it comes in, either way.
        if (!swipe.covering) Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = swipe.scrim) })
    }
}

/** A soft shadow along the edge of the page on top, where it meets the one underneath. */
@Composable
fun SwipeShadow(swipe: HistorySwipe) {
    val shadow = with(LocalDensity.current) { 16.dp.toPx() }
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (swipe.covering) return@drawBehind
            val x = if (swipe.targetOnTop) swipe.targetOffset else swipe.pageOffset
            if (x == 0f) return@drawBehind
            // The top page's left edge when it is to the right, its right edge when it is to the left.
            val edge = if (x > 0) x else size.width + x
            val alpha = 0.25f * (1 - swipe.progress * 0.5f)
            val (from, to) = if (x > 0) edge - shadow to edge else edge to edge + shadow
            val colors = if (x > 0) listOf(Color.Transparent, Color.Black.copy(alpha = alpha)) else listOf(Color.Black.copy(alpha = alpha), Color.Transparent)
            drawRect(Brush.horizontalGradient(colors, startX = from, endX = to), topLeft = Offset(from, 0f), size = Size(shadow, size.height))
        },
    )
}

/** The pull-to-reload indicator: comes down from the top edge, turning, then spins while the page loads. */
@Composable
fun BoxScope.PullIndicator(tab: Tab) {
    val pull = tab.pull
    if (!pull.active) return
    val size = 40.dp
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val frac = if (pull.trigger > 0f) (pull.distance / pull.trigger).coerceIn(0f, 1f) else 0f
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .size(size)
            .offset { IntOffset(0, (pull.distance - sizePx).roundToInt()) }
            .graphicsLayer {
                val f = if (pull.refreshing) 1f else frac
                alpha = 0.3f + 0.7f * f
                scaleX = 0.6f + 0.4f * f
                scaleY = scaleX
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pull.refreshing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}
