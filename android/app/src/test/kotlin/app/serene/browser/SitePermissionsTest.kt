package app.serene.browser

import app.serene.browser.SitePermission.Camera
import app.serene.browser.SitePermission.Location
import app.serene.browser.SitePermission.Microphone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SitePermissionsTest {
    @Test fun decide() {
        // Nothing chosen yet: ask about everything.
        assertEquals(PermissionDecision.Ask(setOf(Camera, Microphone)), decidePermission(setOf(Camera, Microphone), emptyMap()))
        // Only the undecided ones are asked about.
        assertEquals(PermissionDecision.Ask(setOf(Microphone)), decidePermission(setOf(Camera, Microphone), mapOf(Camera to true)))
        assertEquals(PermissionDecision.Grant, decidePermission(setOf(Camera, Microphone), mapOf(Camera to true, Microphone to true)))
        // One blocked kind blocks the call: getUserMedia fails as a whole without it.
        assertEquals(PermissionDecision.Deny, decidePermission(setOf(Camera, Microphone), mapOf(Camera to true, Microphone to false)))
        assertEquals(PermissionDecision.Deny, decidePermission(setOf(Camera, Microphone), mapOf(Microphone to false)))
        // Other kinds' choices don't matter.
        assertEquals(PermissionDecision.Grant, decidePermission(setOf(Location), mapOf(Location to true, Camera to false)))
        assertEquals(PermissionDecision.Deny, decidePermission(emptySet(), emptyMap()))
    }

    @Test fun origins() {
        assertEquals("https://meet.example.com", permissionOrigin("https://meet.example.com/abc-defg?x=1#y"))
        assertEquals("https://meet.example.com", permissionOrigin("https://Meet.Example.com:443/"))
        assertEquals("https://example.com:8443", permissionOrigin("https://example.com:8443/"))
        assertEquals("http://localhost:8080", permissionOrigin("http://localhost:8080"))
        assertEquals("http://localhost", permissionOrigin("http://localhost:80/"))
        assertNull(permissionOrigin("file:///sdcard/a.html"))
        assertNull(permissionOrigin("data:text/html,hi"))
        assertNull(permissionOrigin("about:blank"))
        assertEquals("maps.example.com", originLabel("https://maps.example.com"))
        assertEquals("http://localhost:8080", originLabel("http://localhost:8080"))
    }

    @Test fun storedChoices() {
        val choices = mapOf(Location to false, Camera to true)
        assertEquals("camera=1,location=0", encodeChoices(choices))
        assertEquals(choices, decodeChoices("camera=1,location=0"))
        // Unknown kinds and broken entries are dropped, not fatal.
        assertEquals(mapOf(Microphone to true), decodeChoices("mic=1,midi=1,camera=x,"))
        assertEquals(emptyMap<SitePermission, Boolean>(), decodeChoices(""))
    }
}
