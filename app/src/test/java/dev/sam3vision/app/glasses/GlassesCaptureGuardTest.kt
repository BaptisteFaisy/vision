package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Garde de la photo ([photoRefusal]) : le flux arrêté refuse d'abord, le
 * direct ensuite — `capturePhoto` + HEIC ne disputent jamais la radio au H.265
 * du chemin chaud. `GlassesCamera.capture` elle-même touche le SDK : seul son
 * verdict s'éprouve en JVM.
 */
class GlassesCaptureGuardTest {

    @Test
    fun `sans flux la photo est refusee flux arrete`() {
        assertEquals(GlassesMessages.streamStopped(), photoRefusal(hasStream = false, live = false))
        assertEquals(GlassesMessages.streamStopped(), photoRefusal(hasStream = false, live = true))
    }

    @Test
    fun `pendant le direct la photo est refusee direct en cours`() {
        assertEquals(GlassesMessages.photoDuringLive(), photoRefusal(hasStream = true, live = true))
    }

    @Test
    fun `hors direct avec flux la photo est autorisee`() {
        assertNull(photoRefusal(hasStream = true, live = false))
    }
}
