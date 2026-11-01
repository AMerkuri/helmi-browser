package app.serene

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Making Helmi the default browser. As Chrome does, Helmi asks once with Android's own dialog
 * (RoleManager, Android 10+), on a later launch rather than the first, and never again after it
 * was shown, whatever the answer (MainActivity). Settings › Make Helmi the default browser asks
 * again, or opens Android's default apps screen.
 */
object DefaultBrowser {
    /** The launch Helmi first asks on: the third, so it isn't the first thing a new user sees. */
    const val ASK_AT_LAUNCH = 3

    /** Coming back after this long away counts as a new launch; a quick switch to another app doesn't. */
    const val NEW_LAUNCH_AFTER_MS = 30 * 60_000L

    /** Whether Helmi opens links from other apps. */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) return roles.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        return context.packageManager.resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    /** Android's "Set Helmi as your default browser app?" dialog, or null where there is none (before Android 10). */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
    }

    /** Android's default apps screen, where the user picks the browser themselves. */
    fun openSystemSettings(context: Context) {
        val screens = listOf(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, android.provider.Settings.ACTION_SETTINGS)
        for (action in screens) {
            try {
                context.startActivity(Intent(action))
                return
            } catch (e: ActivityNotFoundException) {
                // Not on this phone: the next one.
            }
        }
    }

    /** Whether Helmi coming on screen at [now] is a new launch, after it was last seen at [lastSeenAt] (0: never). */
    fun isNewLaunch(now: Long, lastSeenAt: Long) =
        lastSeenAt == 0L || now < lastSeenAt || now - lastSeenAt >= NEW_LAUNCH_AFTER_MS

    /**
     * Whether to ask now: on launch [ASK_AT_LAUNCH] or later, never after it [asked] once, not when
     * Helmi [isDefault] already, and only where Android has the dialog ([canAsk]).
     */
    fun shouldAsk(launches: Int, asked: Boolean, isDefault: Boolean, canAsk: Boolean) =
        !asked && !isDefault && canAsk && launches >= ASK_AT_LAUNCH
}
