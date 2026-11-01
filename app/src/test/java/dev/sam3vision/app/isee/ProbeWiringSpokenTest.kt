package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La voix de la sonde (SPEC v4) : le stop part au bus niveau 4, les mots
 * du guidage au repli 12 ; les émissions gardent l'arbitrage (répétition
 * douce identique en vol gardée, phrase fraîche dernier-gagnant, urgence
 * jamais annulée par une douce).
 */
class ProbeWiringSpokenTest {

    /** Porte-parole faux : dits enregistrés (niveau, texte, clip). */
    private class FakeBusSpeaker : VoiceBusSpeaker {
        data class Said(val level: Int, val text: String, val clipId: String?)

        val said = mutableListOf<Said>()
        override fun idle(): Boolean = true
        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += Said(level, text, clipId)
        }

        override fun cut() = Unit
    }

    private val speaker = FakeBusSpeaker()

    @Before
    fun brancher() {
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
    }


    @Test
    fun `stop poste niveau 4 avec clip`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val probe = LiveProbe(scope = this, clock = { 0L }, background = StandardTestDispatcher(testScheduler))
            probe.observeSpoken(scope)
            probe._state.value = LiveProbeState(spoken = "Stop.", spokenUrgent = true, spokenSeq = 1)
            advanceUntilIdle()
        } finally {
            scope.cancel()
        }

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.FENCE, speaker.said.single().level)
        assertEquals("Stop.", speaker.said.single().text)
        assertEquals(spokenClipId("Stop."), speaker.said.single().clipId)
    }

    @Test
    fun `mot doux va au repli 12 sur silence`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val probe = LiveProbe(scope = this, clock = { 0L }, background = StandardTestDispatcher(testScheduler))
            probe.observeSpoken(scope)
            probe._state.value = LiveProbeState(spoken = "Gauche.", spokenUrgent = false, spokenSeq = 1)
            advanceUntilIdle()
        } finally {
            scope.cancel()
        }

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.FALLBACK, speaker.said.single().level)
        assertEquals("Gauche.", speaker.said.single().text)
    }

    @Test
    fun `mots du guidage partent en clip`() {
        assertEquals("guide_straight", spokenClipId("Straight."))
        assertEquals("guide_right", spokenClipId("Droite."))
        assertEquals("tl_left", spokenClipId("left."))
        assertNull(spokenClipId("Personne à 1 mètre."))
    }

    @Test
    fun `repetition douce identique en vol gardee`() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val job = observeSpokenEmissions(
            flowOf(
                SpokenEmission("left.", urgent = false, seq = 1),
                SpokenEmission("left.", urgent = false, seq = 2),
            ),
        ) { text, _ ->
            gate.await()
            said += text
        }

        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("left."), said)
    }

    @Test
    fun `urgence non annulee par une douce plus fraiche`() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val job = observeSpokenEmissions(
            flowOf(
                SpokenEmission("Stop.", urgent = true, seq = 1),
                SpokenEmission("Gauche.", urgent = false, seq = 2),
            ),
        ) { text, urgent ->
            if (urgent) gate.await()
            said += text
        }

        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("Stop.", "Gauche."), said)
    }
}
