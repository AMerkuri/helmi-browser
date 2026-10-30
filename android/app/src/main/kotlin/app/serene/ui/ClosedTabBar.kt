package app.serene.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.serene.Haptics
import app.serene.Threshold
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The "Closed …" bar with Undo, as Chrome draws it: in the app's own colours (the default
 * snackbar is the inverse, a light bar on the dark theme), and swiped sideways to dismiss: it
 * buzzes once dragged far enough to go.
 */
@Composable
fun ClosedTabBar(data: SnackbarData) {
    var dx by remember(data) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val fling = with(LocalDensity.current) { 800.dp.toPx() }
    val view = LocalView.current
    val goes = remember(data) { Threshold { Haptics.threshold(view, it) } }
    val drag = rememberDraggableState {
        dx += it
        goes.update(abs(dx) > width / 3f)
    }
    val colors = MaterialTheme.colorScheme
    Snackbar(
        modifier = Modifier
            .padding(12.dp)
            .onSizeChanged { width = it.width }
            .offset { IntOffset(dx.roundToInt(), 0) }
            .graphicsLayer { alpha = if (width > 0) (1f - abs(dx) / width).coerceIn(0f, 1f) else 1f }
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStopped = { velocity ->
                    goes.set()
                    // Past a third of its width, or flung, it goes off that side; else it springs back.
                    val off = abs(dx) > width / 3f || abs(velocity) > fling
                    if (off) {
                        val side = if (abs(velocity) > fling) sign(velocity) else sign(dx)
                        animate(dx, side * width, animationSpec = tween(150)) { v, _ -> dx = v }
                        data.dismiss()
                    } else {
                        animate(dx, 0f, animationSpec = spring()) { v, _ -> dx = v }
                    }
                },
            ),
        action = {
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) { Text(label, color = colors.primary) }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerHighest,
        contentColor = colors.onSurface,
    ) {
        Text(data.visuals.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
