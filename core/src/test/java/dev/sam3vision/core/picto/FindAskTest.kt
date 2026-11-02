package dev.sam3vision.core.picto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L'écoute « Quoi chercher ? » du balayage vers la droite : la fin de la parole
 * tient lieu de relâchement, un silence n'est jamais transcrit, et la réponse
 * est ramenée à l'objet nu que la chasse sait reconnaître.
 */
class FindAskTest {

    @Test
    fun `sans parole, l'ecoute est jetee au bout du delai`() {
        assertEquals(FindAskStep.LISTEN, FindAskPolicy.step(heardSpeech = false, speaking = false, elapsedMs = 0))
        assertEquals(FindAskStep.LISTEN, FindAskPolicy.step(heardSpeech = false, speaking = false, elapsedMs = 4_900))
        assertEquals(FindAskStep.DISCARD, FindAskPolicy.step(heardSpeech = false, speaking = false, elapsedMs = 5_000))
    }

    @Test
    fun `la parole finie envoie la transcription, des le premier silence`() {
        assertEquals(FindAskStep.SEND, FindAskPolicy.step(heardSpeech = true, speaking = false, elapsedMs = 1_200))
        assertEquals(FindAskStep.LISTEN, FindAskPolicy.step(heardSpeech = true, speaking = true, elapsedMs = 1_200))
    }

    @Test
    fun `le plafond envoie ce qui a ete entendu`() {
        assertEquals(FindAskStep.SEND, FindAskPolicy.step(heardSpeech = true, speaking = true, elapsedMs = 8_000))
        assertEquals(FindAskStep.DISCARD, FindAskPolicy.step(heardSpeech = false, speaking = true, elapsedMs = 8_000))
    }

    @Test
    fun `la reponse est ramenee a l'objet nu`() {
        assertEquals("cles", FindLabelFromSpeech.of("mes clés"))
        assertEquals("cles", FindLabelFromSpeech.of("Clés."))
        assertEquals("medicine box", FindLabelFromSpeech.of("the medicine box please"))
        assertEquals("boite de medicaments", FindLabelFromSpeech.of("ma boîte de médicaments"))
    }

    @Test
    fun `une reponse sans objet ne cherche rien`() {
        assertNull(FindLabelFromSpeech.of(""))
        assertNull(FindLabelFromSpeech.of("   "))
        assertNull(FindLabelFromSpeech.of("mes"))
        assertNull(FindLabelFromSpeech.of("my please"))
    }
}
