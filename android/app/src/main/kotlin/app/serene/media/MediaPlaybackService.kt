package app.serene.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.serene.MainActivity
import app.serene.R
import app.serene.browser.Tab
import java.lang.ref.WeakReference

/**
 * Sound that plays on after the picture-in-picture window closed (Settings › Flags › Keep playing
 * after closing picture-in-picture): a foreground service of type mediaPlayback, as Chrome has for
 * the media a page plays, so Android doesn't freeze Helmi once it is out of sight. Its
 * notification has play or pause and close, and its media session puts the same on the lock screen
 * and in the quick settings player. Close pauses the video and ends the service.
 *
 * Started while the window is open (Helmi is still on screen then, which Android 12+ asks of a
 * foreground service), and ended when Helmi is opened again, the tab goes, or the user closes it.
 * Play and pause go to the tab's last fullscreen video ([Tab.playFullscreenVideo]); the page says
 * when it plays or pauses by itself (FULLSCREEN_JS, [Tab]), and the notification follows.
 */
class MediaPlaybackService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "Helmi").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(true)
                override fun onPause() = command(false)
                override fun onStop() = close()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // Foreground first, every time: a start from startForegroundService must get there.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        when (intent?.action) {
            ACTION_PLAY -> command(true)
            ACTION_PAUSE -> command(false)
            ACTION_CLOSE -> close()
        }
        if (tab() == null) end()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun command(play: Boolean) {
        val t = tab() ?: return end()
        t.playFullscreenVideo(play)
        playing = play
        refresh()
    }

    private fun close() {
        tab()?.playFullscreenVideo(false)
        current = null
        end()
    }

    private fun end() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val t = tab()
        val text = MediaText.of(t?.title.orEmpty(), t?.url.orEmpty(), t?.isPrivate == true)
        val s = session
        s?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text.site)
                .build(),
        )
        s?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
                .build(),
        )
        val playPause = if (playing) {
            action(R.drawable.ic_pip_pause, "Pause", ACTION_PAUSE, 1)
        } else {
            action(R.drawable.ic_pip_play, "Play", ACTION_PLAY, 2)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        s?.let { style.setMediaSession(it.sessionToken) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_helm)
            .setContentTitle(text.title)
            .setContentText(text.site)
            .setContentIntent(open)
            .setDeleteIntent(service(ACTION_CLOSE, 3))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            // A private tab's page stays off the lock screen.
            .setVisibility(if (t?.isPrivate == true) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
            .addAction(playPause)
            .addAction(action(R.drawable.ic_media_close, "Close", ACTION_CLOSE, 3))
            .build()
    }

    private fun action(icon: Int, label: String, what: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, service(what, code)).build()

    private fun service(what: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, MediaPlaybackService::class.java).setAction(what),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "Helmi/Media"
        private const val CHANNEL = "media"
        private const val NOTIFICATION_ID = 0x4d31
        private const val ACTION_PLAY = "app.helmi.browser.media.PLAY"
        private const val ACTION_PAUSE = "app.helmi.browser.media.PAUSE"
        private const val ACTION_CLOSE = "app.helmi.browser.media.CLOSE"

        // The tab whose sound plays on, and whether it plays (as last asked or reported).
        private var current: WeakReference<Tab>? = null
        private var playing = true
        private var running = false

        private fun tab(): Tab? = current?.get()

        /**
         * [tab]'s video went into the picture-in-picture window: keep the process alive from now
         * on, so its sound can go on after the window closes. Helmi is on screen, as starting
         * one needs.
         */
        fun start(context: Context, tab: Tab, playing: Boolean) {
            current = WeakReference(tab)
            this.playing = playing
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java))
                running = true
            } catch (e: IllegalStateException) {
                // Not allowed from the background: the sound stops with the window, as before.
                Log.w(TAG, "no media service: ${e.message}")
                current = null
            }
        }

        /** The page played or paused [tab]'s video by itself. */
        fun update(context: Context, tab: Tab, playing: Boolean) {
            if (!running || tab() !== tab || this.playing == playing) return
            this.playing = playing
            runCatching { context.startService(Intent(context, MediaPlaybackService::class.java)) }
        }

        /** Helmi is on screen again, or the sound isn't wanted any more: the notification goes. */
        fun stop(context: Context) {
            if (!running && current == null) return
            current = null
            running = false
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }

        /** [tab] is going away: so does its notification. */
        fun forget(context: Context, tab: Tab) {
            if (tab() === tab) stop(context)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing in the background", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Sound that plays on after you close the picture-in-picture window"
                    setShowBadge(false)
                },
            )
        }
    }
}

/** What the media notification says: the page's title (or its site), and the site. A private tab's stay hidden. */
data class MediaText(val title: String, val site: String) {
    companion object {
        fun of(title: String, url: String, private: Boolean): MediaText {
            if (private) return MediaText("Playing in a private tab", "Helmi")
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
            val t = title.trim()
            return MediaText(t.ifEmpty { host.ifEmpty { "Helmi" } }, host.ifEmpty { "Helmi" })
        }
    }
}
