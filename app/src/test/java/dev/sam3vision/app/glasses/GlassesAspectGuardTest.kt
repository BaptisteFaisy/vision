package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde d'aspect W3 : frame brute supposée 9:16 portrait (HIGH 720x1280,
 * MEDIUM 504x896). Écart = journalisé ([glassesAspectNote]) + marqué
 * ([JpegFrame.aspectOff]), jamais jeté.
 */
class GlassesAspectGuardTest {

    @Test
    fun `le HIGH 720x1280 tient le 9-16`() {
        assertEquals(0f, glassesAspectDeviation(720, 1280)!!, 1e-6f)
        assertFalse(isGlassesAspectOff(720, 1280))
        assertNull(glassesAspectNote(720, 1280))
    }

    @Test
    fun `le MEDIUM 504x896 tient le 9-16`() {
        assertFalse(isGlassesAspectOff(504, 896))
        assertNull(glassesAspectNote(504, 896))
    }

    @Test
    fun `un paysage signale un flux retourne`() {
        assertTrue(isGlassesAspectOff(1280, 720))
        val note = glassesAspectNote(1280, 720)
        assertNotNull(note)
        assertTrue(note!!.contains("1280x720"))
        assertTrue(note.contains("9:16"))
    }

    @Test
    fun `un carre et un 4-3 sont ecartes`() {
        assertTrue(isGlassesAspectOff(500, 500))
        assertTrue(isGlassesAspectOff(720, 960))
    }

    @Test
    fun `la tolerance couvre les arrondis pas les derives`() {
        assertFalse(isGlassesAspectOff(725, 1280))
        assertTrue(isGlassesAspectOff(745, 1280))
        assertFalse(isGlassesAspectOff(745, 1280, tolerance = 0.05f))
    }

    @Test
    fun `des dimensions inexploitables ne declenchent pas la garde`() {
        assertNull(glassesAspectDeviation(0, 480))
        assertNull(glassesAspectNote(640, 0))
        assertFalse(isGlassesAspectOff(-2, -2))
    }

    @Test
    fun `le flag compare les dimensions brutes pas le jpeg recadre`() {
        val jpeg = JpegFrame(base64 = "", width = 720, height = 960, capturedAtMs = 1L)
        assertFalse(jpeg.withAspectFlag(720, 1280).aspectOff)
        assertTrue(jpeg.withAspectFlag(1280, 720).aspectOff)
    }
}
