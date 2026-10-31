package app.serene

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Tells the user when battery saver switches the model off (Settings › Skip the model in battery
 * saver), and when it comes back. Only while Helmi is on screen: [start] in onStart, [stop] in
 * onStop. A change made while Helmi was away is told on return; the state Helmi starts in is not.
 */
class BatterySaverNotice(private val app: SereneApp) {
    private val power = app.getSystemService(PowerManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = check(context)
    }

    fun start(context: Context) {
        // Sent to registered receivers only, not the manifest.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        check(context)
    }

    fun stop(context: Context) = context.unregisterReceiver(receiver)

    private fun check(context: Context) {
        val on = power.isPowerSaveMode
        val was = app.batterySaverSeen
        app.batterySaverSeen = on
        val s = app.settings
        val text = message(on, was, s.modelEnabled && s.skipModelOnBatterySaver && app.models.readyFile() != null) ?: return
        Toast.makeText(context, text, if (on) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val OFF = "Battery saver is on, so Helmi's model is off. Filter lists and heuristics still hide annoyances."
        const val BACK = "Battery saver is off. Helmi's model is back on."

        /**
         * The toast for battery saver now [on] after [was] (null: the first look since Helmi
         * started), or null when nothing changed or the model would not run either way.
         */
        fun message(on: Boolean, was: Boolean?, saverSkipsModel: Boolean): String? = when {
            was == null || on == was || !saverSkipsModel -> null
            on -> OFF
            else -> BACK
        }
    }
}
