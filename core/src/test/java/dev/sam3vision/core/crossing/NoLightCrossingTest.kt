package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traversée sans feu : la porte confirme l'absence de boîte, l'annonceur dit
 * « Traversez. » sur calme confirmé, jamais sur un danger.
 */
class NoLightCrossingTest {

    @Test
    fun `porte - 4 frames sans boite puis silence feu`() {
        val gate = NoLightGate()

        assertFalse(gate.onFrame(null))
        assertFalse(gate.onFrame(null))
        assertFalse(gate.onFrame(null))
        assertTrue(gate.onFrame(null))
        assertTrue(gate.noLight)
    }

    @Test
    fun `porte - une boite rebascule aussitot cote feu`() {
        val gate = NoLightGate()
        repeat(4) { gate.onFrame(null) }
        assertTrue(gate.noLight)

        assertFalse(gate.onFrame(listOf(0.1f, 0.1f, 0.2f, 0.2f)))
        assertFalse(gate.noLight)
    }

    @Test
    fun `porte - reset rearme`() {
        val gate = NoLightGate()
        repeat(4) { gate.onFrame(null) }
        gate.reset()

        assertFalse(gate.noLight)
        assertFalse(gate.onFrame(null))
    }

    @Test
    fun `texte - traversez dans les deux langues`() {
        assertEquals("Traversez.", NoLightCrossingText.cross(SpeechLanguage.Fr))
        assertEquals("Cross.", NoLightCrossingText.cross(SpeechLanguage.En))
    }

    @Test
    fun `annonceur - silence avant 3 s calmes, traversez apres`() {
        val voice = NoLightAnnouncer()

        assertNull(voice.onFrame(0L, danger = false, SpeechLanguage.Fr))
        assertNull(voice.onFrame(200L, danger = false, SpeechLanguage.Fr))
        assertNull(voice.onFrame(2_000L, danger = false, SpeechLanguage.Fr))
        assertEquals("Traversez.", voice.onFrame(3_000L, danger = false, SpeechLanguage.Fr))
    }

    @Test
    fun `texte - attendez dans les deux langues`() {
        assertEquals("Attendez.", NoLightCrossingText.wait(SpeechLanguage.Fr))
        assertEquals("Wait.", NoLightCrossingText.wait(SpeechLanguage.En))
    }

    @Test
    fun `annonceur - danger rouvre la fenetre de 3 s`() {
        val voice = NoLightAnnouncer()
        voice.onFrame(0L, danger = false, SpeechLanguage.Fr)
        voice.onFrame(1_000L, danger = false, SpeechLanguage.Fr)

        // Danger isolé : tait et rouvre la fenêtre.
        assertNull(voice.onFrame(1_200L, danger = true, SpeechLanguage.Fr))
        // 2,8 s de calme après le danger : encore silence.
        assertNull(voice.onFrame(2_000L, danger = false, SpeechLanguage.Fr))
        assertNull(voice.onFrame(4_000L, danger = false, SpeechLanguage.Fr))
        // 3 s après le retour au calme : « Traversez. ».
        assertEquals("Traversez.", voice.onFrame(5_000L, danger = false, SpeechLanguage.Fr))
    }

    @Test
    fun `annonceur - attendez sur danger confirme, toutes les 2 s`() {
        val voice = NoLightAnnouncer()

        assertNull(voice.onFrame(0L, danger = true, SpeechLanguage.Fr))
        assertEquals("Attendez.", voice.onFrame(200L, danger = true, SpeechLanguage.Fr))
        assertNull(voice.onFrame(400L, danger = true, SpeechLanguage.Fr))
        assertEquals("Attendez.", voice.onFrame(2_300L, danger = true, SpeechLanguage.Fr))
    }

    @Test
    fun `annonceur - attendez coupe le delai apres traversez`() {
        val voice = NoLightAnnouncer()
        voice.onFrame(0L, danger = false, SpeechLanguage.Fr)
        voice.onFrame(1_000L, danger = false, SpeechLanguage.Fr)
        assertEquals("Traversez.", voice.onFrame(3_000L, danger = false, SpeechLanguage.Fr))

        assertNull(voice.onFrame(3_200L, danger = true, SpeechLanguage.Fr))
        assertEquals("Attendez.", voice.onFrame(3_400L, danger = true, SpeechLanguage.Fr))
    }

    @Test
    fun `annonceur - voix vehicules recule le verdict`() {
        val voice = NoLightAnnouncer()
        assertNull(voice.onFrame(0L, danger = true, SpeechLanguage.En))
        assertNull(voice.onFrame(200L, danger = true, SpeechLanguage.En, vehicleSpoke = true))

        assertNull(voice.onFrame(400L, danger = true, SpeechLanguage.En))
        assertNull(voice.onFrame(600L, danger = true, SpeechLanguage.En))
        assertEquals("Wait.", voice.onFrame(2_300L, danger = true, SpeechLanguage.En))
    }

    @Test
    fun `annonceur - traversez apres 3 s, repetition toutes les 2 s`() {
        val voice = NoLightAnnouncer()
        voice.onFrame(0L, danger = false, SpeechLanguage.En)
        assertNull(voice.onFrame(200L, danger = false, SpeechLanguage.En))
        assertEquals("Cross.", voice.onFrame(3_000L, danger = false, SpeechLanguage.En))

        assertNull(voice.onFrame(3_200L, danger = false, SpeechLanguage.En))
        assertEquals("Cross.", voice.onFrame(5_100L, danger = false, SpeechLanguage.En))
    }

    @Test
    fun `annonceur - phrase vehicule calme ne rouvre pas la fenetre`() {
        val voice = NoLightAnnouncer()
        voice.onFrame(0L, danger = false, SpeechLanguage.Fr)
        // Une phrase véhicules sur calme (ex. « à l'arrêt ») recule le
        // délai du verdict, sans rouvrir la fenêtre de 3 s.
        assertNull(voice.onFrame(1_000L, danger = false, SpeechLanguage.Fr, vehicleSpoke = true))
        assertNull(voice.onFrame(2_000L, danger = false, SpeechLanguage.Fr))
        assertEquals("Traversez.", voice.onFrame(3_100L, danger = false, SpeechLanguage.Fr))
    }

    @Test
    fun `annonceur - reset rearme`() {
        val voice = NoLightAnnouncer()
        voice.onFrame(0L, danger = false, SpeechLanguage.Fr)
        voice.onFrame(1_000L, danger = false, SpeechLanguage.Fr)
        voice.onFrame(3_000L, danger = false, SpeechLanguage.Fr)
        voice.reset()

        assertNull(voice.onFrame(3_200L, danger = false, SpeechLanguage.Fr))
        assertNull(voice.onFrame(3_400L, danger = false, SpeechLanguage.Fr))
        assertEquals("Traversez.", voice.onFrame(6_400L, danger = false, SpeechLanguage.Fr))
    }
}
