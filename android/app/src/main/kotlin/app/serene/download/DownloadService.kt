package app.serene.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.serene.MainActivity
import app.serene.SereneApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs while a download does: keeps the process alive when Serene goes to the background and
 * shows the progress notification. Stops itself when nothing is running.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val downloads = (application as SereneApp).downloads
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progress(this, downloads),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (loop?.isActive != true) loop = scope.launch {
            while (true) {
                if (downloads.running.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                    return@launch
                }
                post(this@DownloadService, PROGRESS_ID, progress(this@DownloadService, downloads))
                delay(1000)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+: a dataSync service gets about 6 hours a day. Pause, so the user can resume later.
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SereneApp).downloads.pauseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val PROGRESS_ID = 0x5e7e
        /** MainActivity opens the downloads screen for this action. */
        const val ACTION_SHOW = "app.serene.action.SHOW_DOWNLOADS"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Started from the background (not allowed since Android 12): the download still runs while the app does.
                Log.w("SereneDownloads", "no foreground service: ${e.message}")
            }
        }

        /** "Download complete"; tapping it opens the file with [open]. */
        fun notifyDone(context: Context, d: Download, open: Intent) {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context, d.id.toInt(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(d.name)
                .setContentText("Download complete" + if (d.total > 0) " • ${DownloadText.size(d.total)}" else "")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            post(context, PROGRESS_ID + d.id.toInt(), n)
        }

        private fun progress(context: Context, downloads: Downloads): Notification {
            val running = downloads.running
            val d = running.firstOrNull()
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).setAction(ACTION_SHOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (running.size > 1) "Downloading ${running.size} files" else d?.name ?: "Downloading")
                .setContentIntent(show)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (d != null) {
                b.setContentText(DownloadText.progress(d.received, d.total, d.bytesPerSec))
                val f = d.fraction
                b.setProgress(1000, ((f ?: 0f) * 1000).toInt(), f == null)
            }
            return b.build()
        }

        @SuppressLint("MissingPermission") // checked: notifications may be off (Android 13+ asks)
        private fun post(context: Context, id: Int, n: Notification) {
            val nm = NotificationManagerCompat.from(context)
            if (nm.areNotificationsEnabled()) runCatching { nm.notify(id, n) }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
