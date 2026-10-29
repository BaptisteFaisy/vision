package dev.sam3vision.app.isee

import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'offre de traversée par la voix : une phrase par **apparition** du passage,
 * jamais une par frame. Une personne aveugle n'a que la voix — si l'annonce se
 * répète à chaque frame, elle devient inaudible.
 */
class CrossingOfferVoiceTest {

    @Test
    fun `une phrase quand le passage apparait, pas a chaque frame`() = runTest {
        val offers = MutableStateFlow(false)
        val said = mutableListOf<String>()
        observeCrossingOffer(offers, backgroundScope, { SpeechLanguage.Fr }) { said.add(it) }

        offers.value = false
        offers.value = true
        offers.value = true
        offers.value = true
        runCurrent()               // le collecteur vit sur le dispatcher de test

        assertEquals(listOf("Passage piéton. Dites traverser."), said)
    }

    @Test
    fun `le passage qui revient apres une disparition est reannonce`() = runTest {
        val offers = MutableStateFlow(false)
        val said = mutableListOf<String>()
        observeCrossingOffer(offers, backgroundScope, { SpeechLanguage.Fr }) { said.add(it) }

        offers.value = true
        runCurrent()
        offers.value = false
        runCurrent()
        offers.value = true
        runCurrent()

        assertEquals(2, said.size)
    }

    @Test
    fun `la langue suit les reglages`() = runTest {
        val offers = MutableStateFlow(false)
        val said = mutableListOf<String>()
        observeCrossingOffer(offers, backgroundScope, { SpeechLanguage.En }) { said.add(it) }

        offers.value = true
        runCurrent()

        assertEquals(listOf("Crosswalk. Say cross for crossing mode."), said)
    }
}
