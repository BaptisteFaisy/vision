package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voix des personnes en mode Traversée : l'évitement parle en premier
 * (§ 25 — mot jamais tu par le feu, répété toutes les 300 ms), « Tout
 * droit. » sinon (queue de chaîne hors scan) — la voix ne se tait jamais
 * (ordre user du 31/10) — et les personnes ne sont jamais décrites,
 * seulement évitées.
 */
class CrossingPeopleTest {

    private fun person(centerX: Float, meters: Float? = null) =
        PlacedLabel(label = "personne", centerX = centerX, meters = meters)

    private fun red() = CrossingCue("Rouge, attendez.", false, CrossingState.Red)

    @Test
    fun `une personne seule a gauche reste muette`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("personne", centerX = 0.1f))

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `une personne seule a droite reste muette`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("person", centerX = 0.9f))

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
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
    fun `des personnes seules ne parlent jamais`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(PlacedLabel("personne", centerX = 0.1f))

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
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
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.7f)), 100L, SpeechLanguage.Fr))
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(person(0.3f, 2.4f)), 200L, SpeechLanguage.Fr))
    }

    @Test
    fun `des gens proches a droite disent d aller a gauche`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.7f, 2.9f)), 0L, SpeechLanguage.Fr))
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
    fun `le decalage parle seul, sans positions`() {
        CrossingPeopleWatch.reset()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), 0L, SpeechLanguage.Fr))
        // La piste reste tiède (mêmes mesures, pas de baisse) : silence, sans
        // annonce de positions.
        for (at in 500L..3500L step 500L) {
            assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.9f)), at, SpeechLanguage.Fr))
        }
        assertNull(CrossingPeopleWatch.update(listOf(person(0.3f, 2.7f)), 3900L, SpeechLanguage.Fr))
        // L'approche confirmée dit le mot, le seul que la traversée parle.
        assertEquals("Droite.", CrossingPeopleWatch.update(listOf(person(0.28f, 2.4f)), 4100L, SpeechLanguage.Fr))
    }

    @Test
    fun `des gens proches des deux cotes restent muets`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.3f, 2.0f), person(0.7f, 2.0f))

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `une personne seule se tait toujours`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.1f))

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 100L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 200L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.update(placed, 2700L, SpeechLanguage.Fr))
    }

    @Test
    fun `le feu prime sur le silence`() {
        CrossingPeopleWatch.reset()
        CrossingLightDueWatch.reset()
        val cue = CrossingCue("Vert, traversez.", false, CrossingState.Green)

        assertEquals("Vert, traversez.", crossingPhraseOf(cue, listOf(person(0.1f)), emptyList(), emptyList(), 0L, SpeechLanguage.Fr))
    }

    @Test
    fun `silence du feu, tout droit parle`() {
        CrossingPeopleWatch.reset()
        CrosswalkScanWatch.reset()

        assertEquals("Tout droit.", crossingPhraseOf(null, listOf(person(0.1f)), emptyList(), emptyList(), 0L, SpeechLanguage.Fr))
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

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `evitement seul parle, silence avant confirmation`() {
        CrossingPeopleWatch.reset()
        val placed = listOf(person(0.1f))
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.update(placed, 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.update(placed, 200L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `evitement parle via crossingPhraseOf`() {
        CrossingPeopleWatch.reset()
        CrosswalkScanWatch.reset()
        val objects = onPathLeft()

        assertEquals("Tout droit.", crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `le mot evitement couvre le feu frais, pas le feu du`() {
        CrossingPeopleWatch.reset()
        CrosswalkScanWatch.reset()
        CrossingLightDueWatch.reset()
        val objects = onPathLeft()

        assertEquals("Tout droit.", crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Rouge, attendez.", crossingPhraseOf(red(), emptyList(), emptyList(), emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", crossingPhraseOf(red(), emptyList(), emptyList(), emptyList(), 600L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `mots tus - l evitement se tait puis reparle frais`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.update(emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.update(emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000, wordsMuted = true))
        assertEquals("Droite.", CrossingPeopleWatch.update(emptyList(), 400L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `le repli tout droit parle aussitot`() {
        CrossingPeopleWatch.reset()

        assertEquals("Tout droit.", CrossingPeopleWatch.straight(0L, SpeechLanguage.Fr))
    }

    @Test
    fun `le repli tout droit parle anglais aussi`() {
        CrossingPeopleWatch.reset()

        assertEquals("Straight.", CrossingPeopleWatch.straight(0L, SpeechLanguage.En))
    }

    @Test
    fun `le tout droit se repete toutes les 300 ms`() {
        CrossingPeopleWatch.reset()

        assertEquals("Tout droit.", CrossingPeopleWatch.straight(0L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.straight(100L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.straight(200L, SpeechLanguage.Fr))
        assertEquals("Tout droit.", CrossingPeopleWatch.straight(300L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.straight(400L, SpeechLanguage.Fr))
        assertEquals("Tout droit.", CrossingPeopleWatch.straight(600L, SpeechLanguage.Fr))
    }

    @Test
    fun `l evitement se repete toutes les 300 ms`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.avoidance(emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 300L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.avoidance(emptyList(), 500L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 600L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `entre deux battements d evitement, le tout droit se tait`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.avoidance(emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.straight(300L, SpeechLanguage.Fr))
        assertEquals("Droite.", CrossingPeopleWatch.avoidance(emptyList(), 500L, SpeechLanguage.Fr, objects, 1000, 1000))
    }

    @Test
    fun `le tout droit reprend quand l evitement s efface`() {
        CrossingPeopleWatch.reset()
        val objects = onPathLeft()

        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 0L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertEquals("Droite.", CrossingPeopleWatch.avoidance(emptyList(), 200L, SpeechLanguage.Fr, objects, 1000, 1000))
        assertNull(CrossingPeopleWatch.avoidance(emptyList(), 400L, SpeechLanguage.Fr))
        assertNull(CrossingPeopleWatch.straight(400L, SpeechLanguage.Fr))
        assertEquals("Tout droit.", CrossingPeopleWatch.straight(500L, SpeechLanguage.Fr))
    }

    @Test
    fun `procedure scan - le repli se tait pendant l attente silencieuse`() {
        CrossingPeopleWatch.reset()
        CrosswalkScanWatch.reset()
        val fr = SpeechLanguage.Fr
        fun scan(at: Long) = CrosswalkScanWatch.update(at, trigger = true, movingNow = true, feu = false, fr)

        // Confirmation + Stop.
        assertNull(scan(0L))
        assertEquals("Stop.", scan(100L))
        // Côté gauche : consigne, timeout, observation mouvement, constat danger.
        assertEquals("Regardez à gauche.", scan(900L))
        assertEquals("Regardez à gauche.", scan(6900L))
        assertEquals("Regardez à gauche.", scan(8000L))
        assertEquals("Attention, voiture à gauche.", scan(9400L))
        // Côté droit pareil.
        assertEquals("Regardez à droite.", scan(11400L))
        assertEquals("Regardez à droite.", scan(17400L))
        assertEquals("Regardez à droite.", scan(18500L))
        assertEquals("Attention, voiture à droite.", scan(19900L))
        // Fin de cycle danger → attente silencieuse : le repli se tait.
        assertNull(scan(21900L))
        assertTrue(scanRunning())
        assertNull(crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 22000L, fr, scanTrigger = true))
        CrosswalkScanWatch.reset()
    }
}
