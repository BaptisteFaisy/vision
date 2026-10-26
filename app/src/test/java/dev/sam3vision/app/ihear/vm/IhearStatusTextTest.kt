package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.ihear.IhearViewModel
import dev.sam3vision.app.ihear.PhotoMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Les motifs affichés par l'écran iSee : chaque échec de la photo du double tap est
 * dit dans le chat au lieu de rester muet, et les phrases sont écrites en un seul
 * endroit. Ces tests fixent le texte exact — la découpe du ViewModel ne doit rien
 * changer de ce que lit l'utilisateur.
 */
class IhearStatusTextTest {

    @Test
    fun `pastilles posees — rien a dire`() {
        val measured = PhotoMeasure.Measured(DistanceLayer(320, 240, emptyList()))

        assertNull(photoMeasureMessage(measured))
    }

    @Test
    fun `overlay coupe — le motif renvoie aux Reglages`() {
        assertEquals("Photo: distance overlay off (Settings)", photoMeasureMessage(PhotoMeasure.OverlayOff))
    }

    @Test
    fun `endpoint SAM absent — le motif renvoie a la Region`() {
        assertEquals(
            "Photo: SAM endpoint missing (Settings → Region)",
            photoMeasureMessage(PhotoMeasure.NoEndpoint),
        )
    }

    @Test
    fun `photo illisible — le motif est dit`() {
        assertEquals("Photo: image unreadable", photoMeasureMessage(PhotoMeasure.Unreadable))
    }

    @Test
    fun `aucun objet mesurable — le motif est dit`() {
        assertEquals("Photo: no measurable objects", photoMeasureMessage(PhotoMeasure.NoObjects))
    }

    @Test
    fun `echec d'analyse — la raison est reprise`() {
        assertEquals(
            "Photo analysis failed: SAM unreachable",
            photoMeasureMessage(PhotoMeasure.Failed("SAM unreachable")),
        )
    }

    @Test
    fun `motifs d'erreur — le message du moteur, sinon le repli`() {
        assertEquals("Dictation: réseau coupé", IhearStatusText.dictationFailed(Exception("réseau coupé")))
        assertEquals("Dictation: failed", IhearStatusText.dictationFailed(Exception()))
        assertEquals("Voice: unavailable", IhearStatusText.voiceFailed(Exception()))
        assertEquals("Question failed: error", IhearStatusText.questionFailed(Exception()))
    }

    @Test
    fun `contrat public — les constantes du ViewModel ne bougent pas`() {
        assertEquals(20, IhearViewModel.CHAT_HISTORY)
        assertEquals(2_600L, IhearViewModel.PHOTO_SHOW_MS)
        assertEquals(6_000L, IhearViewModel.PHOTO_MEASURED_MS)
    }

    @Test
    fun `motifs deja vus sur le telephone — identiques apres decoupe`() {
        assertEquals("Microphone unavailable", IhearStatusText.MIC_UNAVAILABLE)
        assertEquals("Camera not ready — try again", IhearStatusText.CAMERA_NOT_READY)
        assertEquals("Busy speaking — try again", IhearStatusText.BUSY_SPEAKING)
        assertEquals("Nothing heard", IhearStatusText.NOTHING_HEARD)
        assertEquals("Answer still coming — ask again after it", IhearStatusText.ANSWER_PENDING)
        assertEquals("No LLM key configured (Settings)", IhearStatusText.NO_LLM_KEY)
        assertEquals("Recording lost", IhearStatusText.RECORDING_LOST)
        assertEquals(
            "No dictation engine configured (Settings)",
            IhearStatusText.NO_DICTATION_ENGINE,
        )
    }
}