package app.serene

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.serene.browser.Fullscreen
import app.serene.browser.FullscreenMedia
import app.serene.browser.Tab
import app.serene.browser.autoPictureInPicture
import app.serene.browser.pipAspect

/**
 * Picture-in-picture for a page's video, as Chrome has it: a video playing in fullscreen goes
 * along into a small window when the user leaves Helmi (home, recent apps), with play and pause
 * buttons; back in Helmi it is in fullscreen again. Back still leaves fullscreen, as in Chrome.
 * A page can ask for the window itself (video.requestPictureInPicture(), see PIP_JS): the video
 * goes through fullscreen into the window at once, and back in Helmi it plays in the page again.
 * Closing the window pauses the video and leaves fullscreen. The page leaving fullscreen while
 * in the window closes it, as there would be nothing left to show but a small browser.
 *
 * The window shows what the fullscreen box shows (FullscreenHost), which covers everything else.
 * The activity handles screen changes in place (configChanges), so the video plays on through
 * going in and out of the window, and folding the Fold.
 */
class PictureInPictureHost(private val activity: ComponentActivity, private val fullscreen: FullscreenHost) {
    /** Android Go phones and some TVs have none, and the activity can't ask for it there. */
    private val supported by lazy { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private var tab: Tab? = null
    private var full: Fullscreen? = null
    private var media: FullscreenMedia? = null
    /** In the window since it last opened, until closed or opened out. */
    private var inWindow = false
    /** Out of the window, and not yet told whether it was opened out (the activity resumes) or closed (it stops). */
    private var leftWindow = false
    /** A link from another app is opening the window out: the fullscreen it ends is no reason to close it. */
    private var openingOut = false

    private val buttons = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MEDIA) return
            tab?.playFullscreenVideo(intent.getBooleanExtra(EXTRA_PLAY, true))
        }
    }

    fun start() {
        ContextCompat.registerReceiver(activity, buttons, IntentFilter(ACTION_MEDIA), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() = runCatching { activity.unregisterReceiver(buttons) }

    /** The page in fullscreen and its video, whenever either changes (null: nothing in fullscreen). */
    fun update(tab: Tab?, full: Fullscreen?, media: FullscreenMedia?) {
        this.tab = if (full == null) null else tab
        this.full = full
        this.media = if (full == null) null else media
        if (!supported) return
        setParams()
        // The page asked for the window: go now (once the activity is on screen to go from).
        if (full?.pictureInPicture == true && !activity.isInPictureInPictureMode &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) enter()
    }

    /** The window's size changed (fold, turn): where the video is changed with it. */
    fun refresh() {
        if (supported && full != null) setParams()
    }

    /**
     * The user is leaving (home, recent apps). From Android 12 the window opens by itself
     * (setAutoEnterEnabled), with the system's own animation; before that it is asked for here.
     */
    fun onUserLeaveHint() {
        if (!supported || Build.VERSION.SDK_INT >= 31) return
        if (full != null && autoPictureInPicture(media)) enter()
    }

    fun onModeChanged(inPictureInPicture: Boolean) {
        if (inPictureInPicture) {
            inWindow = true
            return
        }
        if (!inWindow) return
        // Opened out, the activity resumes next; closed (the window's X, or dragged away), it
        // stops, or has already.
        leftWindow = true
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) closed()
    }

    fun onResume() {
        openingOut = false
        if (leftWindow) openedOut()
    }

    /** Stopped: if the window went with it, it was closed (with the screen off it stays). */
    fun onStop() {
        if (leftWindow || (inWindow && !activity.isInPictureInPictureMode)) closed()
    }

    fun onNewIntent() {
        if (activity.isInPictureInPictureMode) openingOut = true
    }

    /** The fullscreen went: the page left it, or Helmi took it down. */
    fun onFullscreenGone() {
        if (!activity.isInPictureInPictureMode || openingOut || activity.isFinishing || activity.isDestroyed) return
        // Nothing for the window to show but a small browser: close it, as Chrome does. What
        // still plays stops, as the window's X would do.
        val t = tab
        t?.playFullscreenVideo(false)
        activity.moveTaskToBack(true)
    }

    private fun openedOut() {
        inWindow = false
        leftWindow = false
        // Asked for by the page: back to the page, the video in it, as Chrome's "back to tab".
        // Otherwise the video stays in fullscreen, as it was before leaving.
        val t = tab
        if (full?.pictureInPicture == true) t?.exitFullscreen()
    }

    private fun closed() {
        inWindow = false
        leftWindow = false
        val t = tab ?: return
        t.playFullscreenVideo(false)
        t.exitFullscreen()
    }

    private fun enter() {
        val ok = runCatching { activity.enterPictureInPictureMode(params()) }
            .onFailure { Log.w(TAG, "picture-in-picture refused: ${it.message}") }
            .getOrDefault(false)
        // Turned off for Helmi in Android's settings: the video stays in fullscreen.
        if (!ok) Log.i(TAG, "no picture-in-picture window")
    }

    private fun setParams() {
        runCatching { activity.setPictureInPictureParams(params()) }
            .onFailure { Log.w(TAG, "picture-in-picture params refused: ${it.message}") }
    }

    private fun params(): PictureInPictureParams {
        val m = media
        val (w, h) = pipAspect(m?.size)
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            .setActions(if (full != null && m != null) listOf(playPause(m.playing)) else emptyList())
        if (full != null) fullscreen.videoBounds(m?.size)?.let(b::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(full != null && autoPictureInPicture(m))
            // Video: the window cross-fades on a resize instead of redrawing as it goes.
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun playPause(playing: Boolean): RemoteAction {
        val play = !playing
        val label = if (play) "Play" else "Pause"
        val icon = Icon.createWithResource(activity, if (play) R.drawable.ic_pip_play else R.drawable.ic_pip_pause)
        val intent = Intent(ACTION_MEDIA).setPackage(activity.packageName).putExtra(EXTRA_PLAY, play)
        val pending = PendingIntent.getBroadcast(activity, if (play) 1 else 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(icon, label, label, pending)
    }

    private companion object {
        const val TAG = "Helmi/PiP"
        const val ACTION_MEDIA = "app.helmi.browser.PIP_MEDIA"
        const val EXTRA_PLAY = "play"
    }
}
