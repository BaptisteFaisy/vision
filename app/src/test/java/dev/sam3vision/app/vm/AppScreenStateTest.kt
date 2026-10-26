package dev.sam3vision.app.vm

import dev.sam3vision.app.DictationState
import dev.sam3vision.app.Phase
import dev.sam3vision.app.SpeakState
import dev.sam3vision.app.UiState
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'état d'écran est le contrat public des écrans Compose : à la découpe du ViewModel,
 * ses valeurs de départ et ses dérivés ne bougent pas d'un iota.
 */
class AppScreenStateTest {

    @Test
    fun `etat initial — rien de pret, phase idle`() {
        val state = UiState()

        assertNull("aucune photo", state.photo)
        assertEquals("", state.photoB64)
        assertNull(state.analysis)
        assertNull(state.statusLine)
        assertNull(state.segmentationNote)
        assertEquals(Phase.Idle, state.phase)
        assertFalse(state.segmentationActive)
        assertFalse(state.asking)
        assertFalse(state.settingsReady)
        assertTrue("masques affichés par défaut", state.showMasks)
        assertTrue(state.overlays.isEmpty())
        assertTrue(state.chat.isEmpty())
        assertTrue(state.objects.isEmpty())
        assertEquals(SpeakState.Idle, state.speak)
        assertEquals(DictationState(), state.dictation)
        assertEquals(Settings.Default, state.settings)
    }

    @Test
    fun `derives — photo et cle LLM commandent canAsk`() {
        assertFalse("sans photo", UiState(settings = ConfiguredSettings).canAsk)
        assertFalse("sans clé LLM", UiState(photoB64 = "photo").canAsk)
        assertFalse(
            "question en cours",
            UiState(photoB64 = "photo", settings = ConfiguredSettings, asking = true).canAsk,
        )
        assertTrue(UiState(photoB64 = "photo", settings = ConfiguredSettings).canAsk)
        assertFalse("hasPhoto lit l image, pas son base64", UiState(photoB64 = "photo").hasPhoto)
    }

    @Test
    fun `objets — vides sans analyse, ceux de l analyse sinon`() {
        val object1 = SegObject("chaise", 0.8f, listOf(0f, 0f, 1f, 1f), "m")

        assertTrue(UiState().objects.isEmpty())
        assertEquals(listOf(object1), UiState(analysis = analysis(objects = listOf(object1))).objects)
    }
}