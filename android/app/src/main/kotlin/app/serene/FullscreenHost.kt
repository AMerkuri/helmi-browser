package app.serene

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.serene.browser.Fullscreen
import app.serene.browser.fullscreenOrientation
import app.serene.browser.letterbox

/**
 * Shows a page's fullscreen element over the whole window, as Chrome does: over the bars, the
 * tab switcher and the snackbars, with the system bars hidden (a swipe from the edge brings them
 * back for a moment), the camera cutout left black, and the screen turned to the video's
 * orientation on a phone. Back leaves fullscreen. Leaving puts the bars and the orientation back.
 * The activity handles screen changes in place (the manifest's configChanges), so turning the
 * phone or folding the Fold keeps the video playing; the orientation is picked again for the new
 * screen.
 * In picture-in-picture (PictureInPictureHost) the box is all the small window shows.
 */
class FullscreenHost(private val activity: ComponentActivity) {
    private var shown: Fullscreen? = null
    private var box: FrameLayout? = null
    private var video: IntSize? = null
    private var orientationBefore = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Added when fullscreen starts, after the browser's own handlers, so back reaches it first.
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { shown?.exit() }
    }

    private val bars get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    val isShown get() = shown != null

    fun show(f: Fullscreen) {
        if (shown === f) return
        hide()
        shown = f
        orientationBefore = activity.requestedOrientation
        val b = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            // Touches stay on the video; nothing under it is reachable.
            isClickable = true
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                v.setPadding(c.left, c.top, c.right, c.bottom)
                insets
            }
            addView(f.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        (activity.window.decorView as ViewGroup).addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box = b
        activity.onBackPressedDispatcher.addCallback(back)
        hideBars()
        f.view.requestFocus()
    }

    fun hide() {
        shown ?: return
        shown = null
        video = null
        back.remove()
        box?.let { b ->
            (b.parent as? ViewGroup)?.removeView(b)
            b.removeAllViews()
        }
        box = null
        bars.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = orientationBefore
    }

    /** The fullscreen video's size, once its frame says (null: no video, or not yet). */
    fun orient(size: IntSize?) {
        video = size
        val f = shown ?: return
        // On its way to a small window, or in one: turning the screen would only shake it.
        if (f.pictureInPicture || activity.isInPictureInPictureMode) return
        val o = fullscreenOrientation(size, activity.resources.configuration.smallestScreenWidthDp)
        activity.requestedOrientation = if (o == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) orientationBefore else o
    }

    /** A new screen (folded, unfolded, turned): a phone-sized screen turns to the video, a big one doesn't. */
    fun onConfigurationChanged() {
        if (shown != null) orient(video)
    }

    /**
     * Where the video is drawn in the window: the box less the cutout's black, then as big as the
     * video's shape fits. Null before the box is laid out.
     */
    fun videoBounds(size: IntSize?): Rect? {
        val b = box ?: return null
        if (b.width == 0 || b.height == 0) return null
        val xy = IntArray(2)
        b.getLocationInWindow(xy)
        val inner = IntRect(xy[0] + b.paddingLeft, xy[1] + b.paddingTop, xy[0] + b.width - b.paddingRight, xy[1] + b.height - b.paddingBottom)
        val r = if (size == null) inner else letterbox(inner, size)
        return if (r.width <= 0 || r.height <= 0) null else Rect(r.left, r.top, r.right, r.bottom)
    }

    /** Back from another app or a system dialog: the bars it showed go again. */
    fun onWindowFocused() {
        if (shown != null) hideBars()
    }

    private fun hideBars() = bars.run {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}
