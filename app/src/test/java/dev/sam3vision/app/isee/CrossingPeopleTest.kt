package dev.sam3vision.app.isee

import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voix des personnes en mode Traversée : le feu parle en premier (la sonde
 * ne consulte ici que sur son silence), les personnes ensuite — positions au
 * cadran, débouncées comme toute annonce.
 */
class CrossingPeopleTest {

    @Test
    fun `une personne a gauche est annoncee au cadran`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("personne", centerX = 0.1f))

        assertEquals("Personne à 9 heures.", CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `une personne a droite est annoncee au cadran`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("person", centerX = 0.9f))

        assertEquals("Person à 3 heures.", CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `voitures feu et passage restent muets`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(
            PlacedLabel("car", centerX = 0.5f),
            PlacedLabel("traffic light", centerX = 0.5f),
            PlacedLabel("crosswalk", centerX = 0.5f),
        )

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `la meme scene ne se repete pas`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("personne", centerX = 0.1f))

        assertEquals("Personne à 9 heures.", CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 100L, SpeechLanguage.Fr))
    }

    @Test
    fun `sans personne le silence ne dit rien`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
    }
}
