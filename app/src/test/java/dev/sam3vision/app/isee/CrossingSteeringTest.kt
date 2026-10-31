package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePhase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité du guidage en Traversée (§ 25) : l'ordre d'évitement
 * (« Gauche. », « Droite. », stop) passe devant la voix rouge/vert —
 * sauf procédure scan en cours (le scan garde la voix) et stop urgent
 * « voiture en mouvement » (absolu). « Tout droit. » ne couvre jamais
 * le feu. Sonde branchée sur un faux client muet.
 */
class CrossingSteeringTest {

    @Test
    fun `preemption - virages et stop passent`() {
        assertTrue(preemptsCrossing("Gauche."))
        assertTrue(preemptsCrossing("Droite."))
        assertTrue(preemptsCrossing("Left."))
        assertTrue(preemptsCrossing("Right."))
        assertTrue(preemptsCrossing("gauche."))
        assertTrue(preemptsCrossing("droite."))
        assertTrue(preemptsCrossing("left."))
        assertTrue(preemptsCrossing("right."))
        assertTrue(preemptsCrossing("Stop."))
        assertTrue(preemptsCrossing("Stop, car moving."))
        assertTrue(preemptsCrossing("Stop, voiture en mouvement."))
    }

    @Test
    fun `preemption - tout droit et positions ne passent pas`() {
        assertFalse(preemptsCrossing("Tout droit."))
        assertFalse(preemptsCrossing("Straight."))
        assertFalse(preemptsCrossing("tout droit."))
        assertFalse(preemptsCrossing("straight ahead."))
        assertFalse(preemptsCrossing("Personne à 9 heures."))
        assertFalse(preemptsCrossing("Rouge, attendez."))
        assertFalse(preemptsCrossing(""))
    }

    @Test
    fun `le mot du guidage passe devant le feu`() {
        resetWatches()
        val cue = CrossingCue("Rouge, attendez.", false, CrossingState.Red)

        assertEquals("Gauche.", crossingPhraseOf(cue, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, steering = "Gauche."))
        resetWatches()
    }

    @Test
    fun `le mot du guidage parle sans feu ni vehicules`() {
        resetWatches()

        assertEquals("Droite.", crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, steering = "Droite."))
        resetWatches()
    }

    @Test
    fun `traversee guidee - le decalage couvre le feu`() = runTest {
        resetWatches()
        val probe = probe(guide = true)
        val personne = { meters: Float -> PlacedLabel(label = "personne", centerX = 0.1f, meters = meters) }

        assertEquals("Rouge, attendez.", phrase(probe, listOf(personne(2.9f)), 0L))
        assertEquals("Rouge, attendez.", phrase(probe, listOf(personne(2.7f)), 100L))
        assertEquals("Droite.", phrase(probe, listOf(personne(2.4f)), 200L))
        resetWatches()
    }

    @Test
    fun `traversee guidee - voie libre, le feu parle`() = runTest {
        resetWatches()
        val probe = probe(guide = true)

        assertEquals("Rouge, attendez.", phrase(probe, emptyList(), 0L))
        resetWatches()
    }

    @Test
    fun `traversee sans guidage - le feu parle`() = runTest {
        resetWatches()
        val probe = probe(guide = false, announce = true)
        val placed = listOf(PlacedLabel(label = "personne", centerX = 0.1f, meters = 2.4f))

        assertEquals("Rouge, attendez.", phrase(probe, placed, 0L))
        resetWatches()
    }

    @Test
    fun `traversee scan - le guidage se tait`() = runTest {
        resetWatches()
        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, scanTrigger = true)
        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 100L, SpeechLanguage.Fr, scanTrigger = true)
        assertTrue(scanRunning())
        val probe = probe(guide = true)
        val placed = listOf(PlacedLabel(label = "personne", centerX = 0.1f, meters = 2.4f))

        assertEquals("Rouge, attendez.", phrase(probe, placed, 200L))
        resetWatches()
    }

    /** Sonde en Traversée, branchée sur un faux client muet (aucun appel réseau). */
    private fun TestScope.probe(guide: Boolean, announce: Boolean = false): LiveProbe {
        val probe = LiveProbe(
            scope = this,
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, _, _ -> NoLiveClient() },
        )
        probe.configure("https://sam.test", guide = guide, announce = announce, crossingActive = true, language = SpeechLanguage.Fr)
        return probe
    }

    /** Phrase traversée d'une frame (feu rouge confirmé), guidage nourri. */
    private fun phrase(probe: LiveProbe, placed: List<PlacedLabel>, atMs: Long): String? {
        val cross = CrossingPatch(CrossingCue("Rouge, attendez.", false, CrossingState.Red), CrossingState.Red, null, TraversePhase.ATTENDRE)
        val frame = LiveFrame(width = 512, height = 1138, elapsedMs = 120L, objects = emptyList())
        return probe.phraseOf(cross, placed, emptyList(), emptyList(), frame, atMs, SpeechLanguage.Fr, LensSpec.DEFAULT_H_FOV_DEG)
    }

    private fun resetWatches() {
        CrosswalkScanWatch.reset()
        CrossingVehicleWatch.reset()
        CrossingPeopleWatch.reset()
    }

    /** Faux client : le direct n'est jamais pompé (`begin` non appelé), tout appel échoue. */
    private class NoLiveClient : LiveClient {
        override suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int) =
            Result.failure<LiveFrame>(ApiError.BadResponse("pas de direct en test"))

        override suspend fun health() =
            Result.failure<LiveHealth>(ApiError.BadResponse("pas de direct en test"))
    }
}
