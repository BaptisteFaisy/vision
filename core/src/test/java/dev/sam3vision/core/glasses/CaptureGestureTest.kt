package dev.sam3vision.core.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le buffer d'appuis doit absorber le bug beta « SHORT_PRESS puis DOUBLE_PRESS ». */
class CaptureGestureTest {

    private val buffer = CaptureGestureBuffer(graceMs = 350)

    @Test
    fun `appui long declenche une question immediatement`() {
        assertEquals(CaptureAction.ASK, buffer.onPress(CapturePress.HOLD, 1_000))
        assertFalse(buffer.hasPending)
    }

    @Test
    fun `double appui declenche le mode direct sans photo parasite`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertEquals(CaptureAction.LIVE, buffer.onPress(CapturePress.DOUBLE, 1_120))
        assertNull(buffer.flush(2_000))
        assertFalse(buffer.hasPending)
    }

    @Test
    fun `appui court isole devient une photo apres le delai de grace`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertNull(buffer.flush(1_200))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350))
        assertFalse(buffer.hasPending)
    }

    @Test
    fun `le delai de grace est inclusif`() {
        buffer.onPress(CapturePress.SHORT, 1_000)
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_000 + 350))
    }

    @Test
    fun `un appui long annule un appui court en attente`() {
        buffer.onPress(CapturePress.SHORT, 1_000)
        assertEquals(CaptureAction.ASK, buffer.onPress(CapturePress.HOLD, 1_100))
        assertNull(buffer.flush(9_999))
    }

    @Test
    fun `seul l appui court prend une photo, l appui long ecoute`() {
        assertTrue(CaptureAction.PHOTO.takesPhoto)
        assertFalse(CaptureAction.ASK.takesPhoto)
        assertFalse(CaptureAction.LIVE.takesPhoto)
    }

    @Test
    fun `reset vide l attente`() {
        buffer.onPress(CapturePress.SHORT, 1_000)
        assertTrue(buffer.hasPending)
        buffer.reset()
        assertFalse(buffer.hasPending)
        assertNull(buffer.flush(9_999))
    }

    @Test
    fun `appui court dans le delai photo — jete, pas de seconde photo`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350))
        assertNull("trop près de la photo", buffer.onPress(CapturePress.SHORT, 1_500))
        assertFalse(buffer.hasPending)
        assertNull(buffer.flush(9_999))
    }

    @Test
    fun `appui court apres le delai photo — seconde photo`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350))
        assertNull(buffer.onPress(CapturePress.SHORT, 1_350 + CaptureGestureBuffer.DEFAULT_PHOTO_COOLDOWN_MS))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350 + CaptureGestureBuffer.DEFAULT_PHOTO_COOLDOWN_MS + 350))
    }

    @Test
    fun `delai photo — appui long et double non affectes`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350))
        assertEquals(CaptureAction.ASK, buffer.onPress(CapturePress.HOLD, 1_400))
        assertEquals(CaptureAction.LIVE, buffer.onPress(CapturePress.DOUBLE, 1_450))
    }

    @Test
    fun `reset oublie aussi le delai photo`() {
        assertNull(buffer.onPress(CapturePress.SHORT, 1_000))
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_350))
        buffer.reset()
        assertNull(buffer.onPress(CapturePress.SHORT, 1_500))
        assertTrue(buffer.hasPending)
        assertEquals(CaptureAction.PHOTO, buffer.flush(1_850))
    }
}