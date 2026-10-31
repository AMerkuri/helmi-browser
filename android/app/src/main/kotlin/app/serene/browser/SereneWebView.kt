package app.serene.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The tab's WebView, with the page gestures on top: swipe sideways for back and forward, pull
 * down at the top to reload.
 *
 * A gesture only becomes ours when the page itself would not use it: the WebView reports the
 * scroll the page could not take (overScrollBy), as Chrome's own swipe navigation does. A
 * carousel, a map or a zoomed-in page keeps its swipes (a zoomed-in page until it is panned to
 * its edge, as in Chrome), and so does a page that handles the touches in script. Once a gesture is ours, the page gets a cancel and no more of it.
 */
@SuppressLint("ViewConstructor")
class SereneWebView(context: Context, private val swipe: HistorySwipe, private val pull: PullRefresh) : android.webkit.WebView(context) {
    private enum class Mode { Deciding, Swipe, Pull, Page, Blocked }

    private var mode = Mode.Page
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var atTop = false
    // The direction of the scroll the page could not take in this touch: -1, 0 or 1.
    private var overX = 0
    private var overY = 0
    private var velocity: VelocityTracker? = null

    // Raw (screen) coordinates throughout: the view itself moves during a swipe.
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The snapshot still covering the new page: the page is in place, so show it.
                if (swipe.covering) swipe.uncover()
                // A transition still settling: its page is not where the finger thinks it is.
                else if (swipe.active) {
                    mode = Mode.Blocked
                    return true
                }
                mode = Mode.Deciding
                downX = ev.rawX
                downY = ev.rawY
                atTop = !canScrollVertically(-1)
                overX = 0
                overY = 0
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.Deciding) mode = Mode.Page // pinch
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                when (mode) {
                    Mode.Swipe -> swipe.drag(ev.rawX - startX)
                    Mode.Pull -> pull.drag(ev.rawY - startY)
                    Mode.Deciding -> decide(ev)
                    else -> Unit
                }
                if (mode == Mode.Swipe || mode == Mode.Pull) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = ev.actionMasked == MotionEvent.ACTION_CANCEL
                val ours = mode
                mode = Mode.Page
                when (ours) {
                    Mode.Swipe -> {
                        val v = velocity?.run { computeCurrentVelocity(1000); xVelocity } ?: 0f
                        if (cancel) swipe.cancel() else swipe.release(v)
                    }
                    Mode.Pull -> if (cancel) pull.cancel() else pull.release()
                    else -> Unit
                }
                velocity?.recycle()
                velocity = null
                if (ours == Mode.Swipe || ours == Mode.Pull || ours == Mode.Blocked) return true
            }
        }
        if (mode == Mode.Blocked) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun track(ev: MotionEvent) {
        val raw = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocity?.addMovement(raw)
        raw.recycle()
    }

    private fun decide(ev: MotionEvent) {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        val adx = abs(dx)
        val ady = abs(dy)
        // Finger to the right: the page would scroll left, and back is to the left.
        val dir = if (dx > 0) HistorySwipe.BACK else HistorySwipe.FORWARD
        val next = when {
            adx > slop && adx > 2 * ady && overX == dir && swipe.start(dir, fromRight = dx < 0) -> Mode.Swipe
            dy > slop && dy > 2 * adx && atTop && overY < 0 && pull.start() -> Mode.Pull
            // The page took the scroll (the overscroll report comes a frame or two late): it is
            // the page's gesture.
            adx > 6 * slop || ady > 6 * slop -> Mode.Page
            else -> return
        }
        mode = next
        if (next == Mode.Page) return
        startX = ev.rawX
        startY = ev.rawY
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // Called with the page's own scroll as well as with the scroll it could not use (WebView's
    // overscroll), while it runs. Only a scroll past the page's edge counts: a zoomed-in page
    // pans sideways inside its range, and that pan is the page's until it reaches the edge.
    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int, scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int, isTouchEvent: Boolean,
    ): Boolean {
        if (mode == Mode.Deciding) {
            val pastX = scrollX + deltaX < 0 || scrollX + deltaX > scrollRangeX
            if (deltaX != 0 && pastX && abs(deltaX) >= abs(deltaY)) overX = if (deltaX > 0) 1 else -1
            if (deltaY < 0 && scrollY + deltaY < 0 && abs(deltaY) > abs(deltaX)) overY = -1
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke()
    }

    /** The page scrolled: the tab takes a fresh snapshot once it rests. */
    var onScrolled: (() -> Unit)? = null

    // Also called as the view is attached to a window, with that window's visibility.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) onVisible?.invoke()
    }

    /** The view is now in a window on screen: attached to one that is visible, or its window became visible. */
    var onVisible: (() -> Unit)? = null
}
