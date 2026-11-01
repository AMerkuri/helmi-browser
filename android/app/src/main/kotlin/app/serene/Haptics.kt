package app.serene

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback, as Android's haptics guidelines have it: a gesture crossing the point where
 * letting go acts (and coming back under it), and a tick for each item flipped past. Never for
 * something the user didn't do, and not for plain button taps.
 *
 * Through View.performHapticFeedback, so the phone's touch feedback setting turns it off. The
 * gesture constants are Android 14's; before it the nearest older ones stand in. A long press
 * on a link or picture needs nothing here: View.performLongClick buzzes when the listener takes it.
 */
object Haptics {
    /** A drag crossed the point where letting go acts ([on]), or went back under it. */
    fun threshold(view: View, on: Boolean) {
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                on -> HapticFeedbackConstants.CONTEXT_CLICK
                else -> HapticFeedbackConstants.CLOCK_TICK
            },
        )
    }

    /**
     * What was typed found nothing (find in page, as in Chrome): one heavy click, two steps firmer
     * than the cube's tick (LONG_PRESS plays the heavy click effect, VIRTUAL_KEY the plain click,
     * the gesture constants the lighter tick).
     */
    fun miss(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** One item of a row went past, as on a picker wheel. */
    fun tick(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }
}

/**
 * Whether a drag is past the point where letting go acts. [buzz] goes off each time that changes
 * during the drag, with the new state, so the finger feels where the gesture will act.
 */
class Threshold(private val buzz: (Boolean) -> Unit) {
    var past = false
        private set

    /** The drag moved: [now] is whether it is past the point now. */
    fun update(now: Boolean) {
        if (now == past) return
        past = now
        buzz(now)
    }

    /** Without a buzz: a drag picked up where it already is, or ended. */
    fun set(now: Boolean = false) {
        past = now
    }
}
