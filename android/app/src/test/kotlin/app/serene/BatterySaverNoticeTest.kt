package app.serene

import app.serene.BatterySaverNotice.Companion.BACK
import app.serene.BatterySaverNotice.Companion.OFF
import app.serene.BatterySaverNotice.Companion.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatterySaverNoticeTest {
    @Test fun saverOnTurnsTheModelOff() = assertEquals(OFF, message(on = true, was = false, saverSkipsModel = true))

    @Test fun saverOffBringsItBack() = assertEquals(BACK, message(on = false, was = true, saverSkipsModel = true))

    @Test fun noChangeSaysNothing() {
        assertNull(message(on = true, was = true, saverSkipsModel = true))
        assertNull(message(on = false, was = false, saverSkipsModel = true))
    }

    // The state Helmi starts in is not news.
    @Test fun firstLookSaysNothing() = assertNull(message(on = true, was = null, saverSkipsModel = true))

    // Model off, not downloaded, or the setting lets it run in battery saver.
    @Test fun nothingWhenTheModelWouldNotChange() {
        assertNull(message(on = true, was = false, saverSkipsModel = false))
        assertNull(message(on = false, was = true, saverSkipsModel = false))
    }
}
