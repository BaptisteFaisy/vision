package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voix douce des personnes en mode Traversée : le feu parle en premier, le
 * décalage opposé aux obstacles proches ensuite (miroir du guidage normal),
 * les positions des personnes au cadran sinon — et silence doux après un
 * ordre du feu nouveau.
 */
class CrossingPeopleTest {

    private fun person(centerX: Float, meters: Float? = null) =
        PlacedLabel(label = "personne", centerX = centerX, meters = meters)

    private fun red() = CrossingCue("Rouge, attendez.", false, CrossingState.Red)

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
            PlacedLabel("pedestrian traffic light", centerX = 0.5f),
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

    @Test
    fun `des gens proches a gauche disent d aller a droite`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNotNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.7f)), 100L, SpeechLanguage.Fr))
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(person(0.3f, 2.4f)), 200L, SpeechLanguage.Fr))
    }

    @Test
    fun `des gens proches a droite disent d aller a gauche`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNotNull(CrossingPeopleWatch.update(listOf(person(0.7f, 2.9f)), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.7f, 2.7f)), 100L, SpeechLanguage.Fr))
        assertEquals("Gauche.", CrossingPeopleWatch.update(listOf(person(0.7f, 2.4f)), 200L, SpeechLanguage.Fr))
    }

    @Test
    fun `un poteau proche a gauche dit d aller a droite`() {
        CrossingPeopleWatch.reset()
        fun poteau(meters: Float) = PlacedLabel(label = "poteau", centerX = 0.3f, meters = meters)

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(poteau(2.9f)), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(poteau(2.7f)), 100L, SpeechLanguage.Fr))
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(poteau(2.4f)), 200L, SpeechLanguage.Fr))
    }

    @Test
    fun `le decalage prime sur des positions fraiches`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNotNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), 0L, SpeechLanguage.Fr))
        // La piste reste tiède (mêmes mesures, pas de baisse) pendant que le
        // délai des positions s'écoule.
        for (at in 500L..3500L step 500L) {
            assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), at, SpeechLanguage.Fr))
        }
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.7f)), 3900L, SpeechLanguage.Fr))
        // Les positions pourraient parler (délai écoulé, heure changée) : le mot gagne.
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(person(0.28f, 2.4f)), 4100L, SpeechLanguage.Fr))
    }

    @Test
    fun `des gens proches des deux cotes annoncent les positions`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.3f, 2.0f), person(0.7f, 2.0f))

        val phrase = CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr)

        assertNotNull(phrase)
        assertTrue(phrase!!.startsWith("Personne"))
    }

    @Test
    fun `un ordre du feu nouveau fait taire la voix douce`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.1f))

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 100L, SpeechLanguage.Fr, red()))
        // L'annonceur est vierge (jamais nourri) : ce silence est le quiet, pas le cooldown.
        assertNull(CrossingPeopleWatch.update(placed, 200L, SpeechLanguage.Fr))
        assertEquals("Personne à 9 heures.", CrossingPeopleWatch.update(placed, 2700L, SpeechLanguage.Fr))
    }

    @Test
    fun `la repetition du rouge ne rearme pas le silence`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.1f))

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 100L, SpeechLanguage.Fr, red()))
        assertNull(CrossingPeopleWatch.update(placed, 2600L, SpeechLanguage.Fr, red()))
        assertEquals("Personne à 9 heures.", CrossingPeopleWatch.update(placed, 2700L, SpeechLanguage.Fr))
    }

    @Test
    fun `le decalage traverse le silence et parle apres`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), 0L, SpeechLanguage.Fr, red()))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.7f)), 100L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.4f)), 200L, SpeechLanguage.Fr))
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(person(0.3f, 2.2f)), 2700L, SpeechLanguage.Fr))
    }

    @Test
    fun `le feu prime sur la voix douce`() {
        CrossingPeopleWatch.reset()
        val cue = CrossingCue("Vert, traversez.", false, CrossingState.Green)

        assertEquals("Vert, traversez.", crossingPhraseOf(cue, listOf(person(0.1f)), emptyList(), emptyList(), 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `silence du feu, les personnes parlent`() {
        CrossingPeopleWatch.reset()

        assertEquals("Personne à 9 heures.", crossingPhraseOf(null, listOf(person(0.1f)), emptyList(), emptyList(), 0L, SpeechLanguage.Fr))
    }

    private fun seg(label: String, box: List<Float>) =
        SegObject(label = label, score = 0.8f, box = box, maskPngB64 = "")

    private fun onPathLeft() = listOf(
        seg("crosswalk", listOf(100f, 600f, 900f, 950f)),
        seg("person", listOf(350f, 400f, 450f, 800f)),
    )

    @Test
    fun `evitement sur passage parle des la confirmation`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr, null, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(emptyList(), 200L, SpeechLanguage.Fr, null, objects, 1000, 1000))
    }

    @Test
    fun `evitement prime sur les positions`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.1f))
        val objects = onPathLeft()

        assertEquals("Personne à 9 heures.", CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr, null, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(placed, 200L, SpeechLanguage.Fr, null, objects, 1000, 1000))
    }

    @Test
    fun `ordre du feu neuf tait l evitement puis le rend`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr, null, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(emptyList(), 200L, SpeechLanguage.Fr, null, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.update(emptyList(), 300L, SpeechLanguage.Fr, red(), objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.update(emptyList(), 2900L, SpeechLanguage.Fr, null, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(emptyList(), 3100L, SpeechLanguage.Fr, null, objects, 1000, 1000))
    }

    @Test
    fun `evitement parle via crossingPhraseOf`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
    }
}
