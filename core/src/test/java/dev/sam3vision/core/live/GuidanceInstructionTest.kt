package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Consigne affichée du battement : mot + mètres droit devant. */
class GuidanceInstructionTest {

    private fun pulse(
        word: GuidanceWord = GuidanceWord.Left,
        nearest: Float? = 2.4f,
        stale: Boolean = false,
    ) = GuidancePulse(
        atMs = 1_500L,
        ageMs = 500L,
        stale = stale,
        word = word,
        side = GuidanceSide.Left,
        shiftMeters = 1.5f,
        nearestMeters = nearest,
    )

    @Test
    fun `gauche avec mesure donne mot et metres`() {
        assertEquals("Gauche · ≈ 2,4 m", GuidanceInstruction.text(pulse()))
    }

    @Test
    fun `sans mesure le mot seul`() {
        assertEquals("Tout droit", GuidanceInstruction.text(pulse(GuidanceWord.Straight, null)))
    }

    @Test
    fun `perime prefixe points de suspension`() {
        assertEquals("… Gauche · ≈ 2,4 m", GuidanceInstruction.text(pulse(stale = true)))
    }

    @Test
    fun `stop sous un metre en centimetres`() {
        assertEquals("Stop · ≈ 40 cm", GuidanceInstruction.text(pulse(GuidanceWord.Stop, 0.4f)))
    }

    @Test
    fun `mot anglais mesure francaise`() {
        assertEquals(
            "Right · ≈ 2,4 m",
            GuidanceInstruction.text(pulse(GuidanceWord.Right), SpeechLanguage.En),
        )
    }

    @Test
    fun `mesure infinie ignoree mot seul`() {
        assertEquals("Gauche", GuidanceInstruction.text(pulse(nearest = Float.NaN)))
    }
}
