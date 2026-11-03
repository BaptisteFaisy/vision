package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.NoLightGate
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.TraverseVeto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branche sans-feu de la sonde : absence confirmée, les véhicules tranchent.
 * Extraits de `CrossingWiringTest` (plafond 500 lignes) : sans feu rouge ni
 * voiture en mouvement, la sonde dit « peut traverser » (ordre user du 03/11).
 */
class CrossingWiringNoLightTest {

    private fun feed(
        gate: NoLightGate,
        announcer: CrossingAnnouncer,
        veto: TraverseVeto,
        frame: LiveFrame,
        atMs: Long,
    ): CrossingPatch? = crossingPatchOf(
        announcer, veto, true, frame, LensSpec.Default, "", atMs,
        SpeechLanguage.Fr, noLightGate = gate,
    )

    @Test
    fun `absence de feu confirmee sans voiture - pret puis traversez`() {
        val gate = NoLightGate()
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        var patch: CrossingPatch? = null
        repeat(NoLightGate.NO_LIGHT_MIN_FRAMES) { i ->
            patch = feed(gate, announcer, veto, frame, i * 200L)
        }
        val confirmed = patch!!
        assertTrue("absence de feu confirmée", confirmed.noLight)
        assertNull("le feu se tait", confirmed.cue)
        assertFalse("pas de veto sans voiture", confirmed.veto)
        assertEquals(TraversePhase.PRET, confirmed.phase)
        assertEquals(CrossingState.Red, confirmed.state)
        val next = feed(gate, announcer, veto, frame, NoLightGate.NO_LIGHT_MIN_FRAMES * 200L)!!
        assertTrue(next.noLight)
        assertNull(next.cue)
        assertFalse(next.veto)
        assertEquals(TraversePhase.TRAVERSER, next.phase)
        assertEquals("peut traverser", CrossingState.Green, next.state)
    }

    @Test
    fun `feu rouge qui revient apres absence - rouge aussitot`() {
        // Asymétrie sûre : 5 frames vides (TRAVERSER, vert), puis un feu rouge
        // cadré — la porte retombe côté feu et le patch redevient rouge, phase
        // ATTENDRE, dès cette frame.
        val gate = NoLightGate()
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val empty = LiveFrame(640, 480, 0L, emptyList())
        repeat(NoLightGate.NO_LIGHT_MIN_FRAMES + 1) { i ->
            feed(gate, announcer, veto, empty, i * 200L)
        }
        assertEquals(TraversePhase.TRAVERSER, veto.currentState)
        val red = LiveFrame(
            640, 480, 0L,
            listOf(SegObject("pedestrian traffic light", 0.9f, listOf(10f, 10f, 100f, 200f), "")),
        )
        val patch = feed(gate, announcer, veto, red, (NoLightGate.NO_LIGHT_MIN_FRAMES + 1) * 200L)!!
        assertFalse(patch.noLight)
        assertEquals(TraversePhase.ATTENDRE, patch.phase)
        assertEquals(CrossingState.Red, patch.state)
    }
}
