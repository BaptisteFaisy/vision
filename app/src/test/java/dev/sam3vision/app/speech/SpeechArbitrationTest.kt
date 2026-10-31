package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arbitrage de la voix unique : danger > consigne > info, urgent coupe tout. */
class SpeechArbitrationTest {

    @Test
    fun `sorties trace recalcul arret arrivee sont danger`() {
        assertEquals(SpeechPriority.Danger, SpeechArbitration.priorityOf(VoiceNavEvent.OffRoute))
        assertEquals(SpeechPriority.Danger, SpeechArbitration.priorityOf(VoiceNavEvent.Rerouted))
        assertEquals(SpeechPriority.Danger, SpeechArbitration.priorityOf(VoiceNavEvent.Stopped))
        assertEquals(SpeechPriority.Danger, SpeechArbitration.priorityOf(VoiceNavEvent.Arrival("gare")))
    }

    @Test
    fun `consignes de route sont instruction`() {
        assertEquals(
            SpeechPriority.Instruction,
            SpeechArbitration.priorityOf(VoiceNavEvent.Turn(SteeringCap.Left, 100f)),
        )
        assertEquals(
            SpeechPriority.Instruction,
            SpeechArbitration.priorityOf(VoiceNavEvent.ContinueStraight(200f)),
        )
        assertEquals(
            SpeechPriority.Instruction,
            SpeechArbitration.priorityOf(VoiceNavEvent.Started("gare")),
        )
        assertEquals(
            SpeechPriority.Instruction,
            SpeechArbitration.priorityOf(VoiceNavEvent.Literal("Faites demi-tour")),
        )
    }

    @Test
    fun `retour sur trace est info`() {
        assertEquals(SpeechPriority.Info, SpeechArbitration.priorityOf(VoiceNavEvent.BackOnRoute))
    }

    @Test
    fun `danger coupe consigne et info`() {
        assertTrue(SpeechArbitration.interrupts(SpeechPriority.Instruction, SpeechPriority.Danger))
        assertTrue(SpeechArbitration.interrupts(SpeechPriority.Info, SpeechPriority.Danger))
    }

    @Test
    fun `danger coupe meme un danger en cours, la plus fraiche gagne`() {
        assertTrue(SpeechArbitration.interrupts(SpeechPriority.Danger, SpeechPriority.Danger))
    }

    @Test
    fun `consigne coupe info mais pas danger`() {
        assertTrue(SpeechArbitration.interrupts(SpeechPriority.Info, SpeechPriority.Instruction))
        assertFalse(SpeechArbitration.interrupts(SpeechPriority.Danger, SpeechPriority.Instruction))
    }

    @Test
    fun `info ne coupe rien et doux de meme niveau s enchaine`() {
        assertFalse(SpeechArbitration.interrupts(SpeechPriority.Danger, SpeechPriority.Info))
        assertFalse(SpeechArbitration.interrupts(SpeechPriority.Instruction, SpeechPriority.Info))
        assertFalse(SpeechArbitration.interrupts(SpeechPriority.Info, SpeechPriority.Info))
        assertFalse(SpeechArbitration.interrupts(SpeechPriority.Instruction, SpeechPriority.Instruction))
    }
}
