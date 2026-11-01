package app.serene

import app.serene.DefaultBrowser.ASK_AT_LAUNCH
import app.serene.DefaultBrowser.NEW_LAUNCH_AFTER_MS
import app.serene.DefaultBrowser.isNewLaunch
import app.serene.DefaultBrowser.shouldAsk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBrowserTest {
    private val t = 1_800_000_000_000L

    @Test fun firstEverStartIsALaunch() = assertTrue(isNewLaunch(t, lastSeenAt = 0L))

    @Test fun quickSwitchIsNotALaunch() = assertFalse(isNewLaunch(t + 60_000, lastSeenAt = t))

    @Test fun comingBackLaterIsALaunch() = assertTrue(isNewLaunch(t + NEW_LAUNCH_AFTER_MS, lastSeenAt = t))

    // The clock was set back: count it rather than never counting again until it catches up.
    @Test fun clockSetBackIsALaunch() = assertTrue(isNewLaunch(t - 1, lastSeenAt = t))

    @Test fun notOnTheFirstLaunches() {
        for (n in 1 until ASK_AT_LAUNCH) assertFalse(shouldAsk(n, asked = false, isDefault = false, canAsk = true))
    }

    @Test fun asksOnTheThirdLaunch() = assertTrue(shouldAsk(ASK_AT_LAUNCH, asked = false, isDefault = false, canAsk = true))

    // Missed launches (opened from a link, a dialog was up) ask on the next one.
    @Test fun asksLaterIfNotAskedYet() = assertTrue(shouldAsk(ASK_AT_LAUNCH + 4, asked = false, isDefault = false, canAsk = true))

    @Test fun neverAgainAfterAsking() = assertFalse(shouldAsk(ASK_AT_LAUNCH + 1, asked = true, isDefault = false, canAsk = true))

    @Test fun notWhenAlreadyDefault() = assertFalse(shouldAsk(ASK_AT_LAUNCH, asked = false, isDefault = true, canAsk = true))

    // Before Android 10 there is no dialog, only the Settings line.
    @Test fun notWithoutAndroidsDialog() = assertFalse(shouldAsk(ASK_AT_LAUNCH, asked = false, isDefault = false, canAsk = false))
}
