package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Test

/** Arbitrage de la voix unique : danger grave > consigne > info, jamais de coupe (ordre user du 02/11). */
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
}
