package app.serene.browser

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/**
 * Swiping between pages of a tab's history, as Safari does. Going back, the page slides away
 * with the finger and the previous page shows underneath, coming in from a little to the side.
 * Going forward, the next page slides in over the current one. The pages that are not on screen
 * are snapshots ([Snapshots]), taken only when WebView keeps pages in its back/forward cache;
 * without it they show as a plain card with the page's title. Once the swipe lets go, the
 * snapshot or card stays over the WebView until the real page has drawn.
 *
 * Driven by the in-page swipe ([SereneWebView]) and by Android's back gesture (predictive back,
 * in BrowserScreen). The screen draws the layers from [direction], [progress] and [target]; the
 * WebView itself is moved here, with translationX.
 */
class HistorySwipe(private val tab: Tab) {
    /** [BACK], [FORWARD], or 0 when no swipe is on. */
    var direction by mutableIntStateOf(0)
        private set
    /** How far the swipe has gone, 0 to 1. */
    var progress by mutableFloatStateOf(0f)
        private set
    /** Which way the finger goes: 1 to the right, -1 to the left. The page on top moves with it. */
    var fingerSign by mutableIntStateOf(1)
        private set
    /** The page the swipe goes to, or null when there is no picture of it. */
    var target by mutableStateOf<Snapshot?>(null)
        private set
    /** Its title or host, for the plain page shown without a snapshot. */
    var targetLabel by mutableStateOf("")
        private set
    /** The navigation has happened: the target covers the WebView until the new page has drawn. */
    var covering by mutableStateOf(false)
        private set
    /** The cover's opacity while it fades out. */
    var coverAlpha by mutableFloatStateOf(1f)
        private set

    /** The width the pages move across, in px; set by the screen. */
    var width = 0f
    /** A fast swipe commits even when short, in px per second; set by the screen. */
    var flingVelocity = 1500f

    val active get() = direction != 0

    private var animator: ValueAnimator? = null

    /** Starts a swipe if the tab has a page that way. */
    fun start(dir: Int, fromRight: Boolean): Boolean {
        if (active || width <= 0f) return false
        val list = tab.webView.copyBackForwardList()
        val item = list.getItemAtIndex(list.currentIndex + dir) ?: return false
        direction = dir
        fingerSign = if (dir == FORWARD || fromRight) -1 else 1
        target = if (tab.backForwardCache) Snapshots.get(tab.id, list.currentIndex + dir, item.url) else null
        targetLabel = item.title?.takeIf { it.isNotBlank() } ?: android.net.Uri.parse(item.url).host ?: item.url
        covering = false
        coverAlpha = 1f
        set(0f)
        return true
    }

    /** The finger is [dx] px from where the swipe started (signed: right is positive). */
    fun drag(dx: Float) {
        if (!active || covering) return
        animator?.cancel()
        set(dx * fingerSign / width)
    }

    /** The finger let go at [velocityX] px/s: go on to the page or slide back. */
    fun release(velocityX: Float) {
        if (!active || covering) return
        val v = velocityX * fingerSign
        finish(commit = if (abs(v) > flingVelocity) v > 0 else progress > 0.35f)
    }

    fun cancel() = finish(commit = false)

    /** Ends the swipe: on to the target page, or back to where it was. */
    fun finish(commit: Boolean) {
        if (!active || covering) return
        val to = if (commit) 1f else 0f
        animate(progress, to, (300 * abs(to - progress)).toLong().coerceAtLeast(120)) {
            if (commit) navigate() else clear()
        }
    }

    private fun navigate() {
        covering = true
        tab.webView.translationX = 0f
        tab.go(direction)
        tab.whenShown(::uncover)
    }

    /** Fades the cover out: the new page has loaded, or the user touches it. */
    fun uncover() {
        if (!covering || fading) return
        fading = true
        animator = ValueAnimator.ofFloat(coverAlpha, 0f).apply {
            duration = 200
            addUpdateListener { coverAlpha = it.animatedValue as Float }
            doOnEnd {
                clear()
                tab.snapshotSoon() // a page that had no snapshot has one for next time
            }
            start()
        }
    }

    private var fading = false

    private fun clear() {
        animator = null
        fading = false
        direction = 0
        covering = false
        target = null
        set(0f)
    }

    /** Stops at once, the page back in place: the tab is closing or changing. */
    fun reset() {
        animator?.cancel()
        clear()
    }

    private fun animate(from: Float, to: Float, ms: Long, then: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { set(it.animatedValue as Float) }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) then() }
            })
            start()
        }
    }

    private fun set(p: Float) {
        progress = p.coerceIn(0f, 1f)
        tab.webView.translationX = pageOffset
    }

    /** Where the current page is, in px. Back: it leaves with the finger. Forward: it gives way a little. */
    val pageOffset: Float
        get() = when {
            !active || covering -> 0f
            direction == BACK -> fingerSign * progress * width
            else -> fingerSign * PARALLAX * progress * width
        }

    /** Where the target page is, in px: from a little to the side (back), or from off screen (forward). */
    val targetOffset: Float
        get() = when {
            covering -> 0f
            direction == BACK -> -fingerSign * PARALLAX * (1 - progress) * width
            else -> -fingerSign * (1 - progress) * width
        }

    /** The target is on top: going forward, or covering the WebView after the navigation. */
    val targetOnTop get() = direction == FORWARD || covering

    /** The dimming over the page underneath. */
    val scrim: Float
        get() = if (covering) 0f else SCRIM * if (direction == BACK) 1 - progress else progress

    companion object {
        const val BACK = -1
        const val FORWARD = 1
        private const val PARALLAX = 0.3f
        private const val SCRIM = 0.18f
    }
}
