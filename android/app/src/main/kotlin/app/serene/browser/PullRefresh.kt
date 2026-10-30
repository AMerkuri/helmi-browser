package app.serene.browser

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serene.Haptics
import app.serene.Threshold
import kotlin.math.exp

/**
 * Pull down at the top of a page to reload it. The indicator comes down with the finger, slower
 * the further it goes, and reloads past [trigger] (a buzz as it gets there, and as it goes back
 * under); it spins until the page has loaded.
 * Driven by [SereneWebView]; drawn by the screen from [distance] and [refreshing].
 */
class PullRefresh(private val tab: Tab) {
    /** How far the indicator has come down, in px. */
    var distance by mutableFloatStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** The pull that reloads, in px of indicator travel; set by the screen. */
    var trigger = 0f

    val active get() = distance > 0f || refreshing

    private var animator: ValueAnimator? = null
    private var since = 0L
    private val reloads = Threshold { Haptics.threshold(tab.webView, it) }
    private val main = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (active || trigger <= 0f || tab.swipe.active) return false
        animator?.cancel()
        reloads.set()
        return true
    }

    /** The finger is [dy] px below where the pull started. */
    fun drag(dy: Float) {
        // Resistance: the indicator follows the finger at first, then less and less.
        val max = trigger * 1.6f
        distance = if (dy <= 0f) 0f else max * (1 - exp(-dy / max))
        reloads.update(distance >= trigger)
    }

    fun release() {
        if (distance >= trigger) {
            refreshing = true
            since = SystemClock.uptimeMillis()
            animateTo(trigger * 0.8f)
            tab.reload()
            main.postDelayed(::done, 15_000) // a page that never finishes
        } else {
            animateTo(0f)
        }
    }

    fun cancel() = animateTo(0f)

    /** The page has loaded: put the indicator away, after a moment so it does not just flash. */
    fun done() {
        if (!refreshing) return
        main.removeCallbacksAndMessages(null)
        val wait = MIN_SPIN - (SystemClock.uptimeMillis() - since)
        if (wait > 0) return run { main.postDelayed(::done, wait) }
        refreshing = false
        animateTo(0f)
    }

    fun reset() {
        main.removeCallbacksAndMessages(null)
        animator?.cancel()
        refreshing = false
        distance = 0f
    }

    private fun animateTo(to: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(distance, to).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { distance = it.animatedValue as Float }
            start()
        }
    }

    private companion object {
        const val MIN_SPIN = 500L
    }
}
