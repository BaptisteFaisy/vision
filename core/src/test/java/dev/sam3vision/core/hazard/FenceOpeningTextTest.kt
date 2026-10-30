package dev.sam3vision.core.hazard

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phrases grillage : fermé, portail nommé, ouverture située, passage au milieu — FR exactes, EN miroirs. */
class FenceOpeningTextTest {

    private fun phrase(
        state: FenceOpeningState,
        position: FenceOpeningPosition,
        language: SpeechLanguage,
        meters: Float? = null,
        centerX: Float? = null,
        isGate: Boolean = false,
        middle: Boolean = false,
    ): String = FenceOpeningText.phrase(state, position, language, meters, centerX, isGate, middle)

    @Test
    fun `ferme FR exact`() {
        assertEquals(
            "Grillage fermé devant vous.",
            phrase(FenceOpeningState.Closed, FenceOpeningPosition.Ahead, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `portail ouvert FR exact devant gauche droite`() {
        assertEquals(
            "Portail ouvert devant vous, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.Fr, 2f, isGate = true),
        )
        assertEquals(
            "Portail ouvert à gauche, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, 2f, isGate = true),
        )
        assertEquals(
            "Portail ouvert à droite, à 3 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Right, SpeechLanguage.Fr, 2.6f, isGate = true),
        )
    }

    @Test
    fun `ouverture FR exacte devant gauche droite`() {
        assertEquals(
            "Ouverture devant, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.Fr, 2f),
        )
        assertEquals(
            "Ouverture à gauche, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, 2f),
        )
        assertEquals(
            "Ouverture à droite, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Right, SpeechLanguage.Fr, 2f),
        )
    }

    @Test
    fun `passage au milieu FR exact`() {
        assertEquals(
            "Passage au milieu.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.Fr, 2f, middle = true),
        )
    }

    @Test
    fun `portail au milieu = portail nomme`() {
        assertEquals(
            "Portail ouvert devant vous, à 2 mètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.Fr, 2f, isGate = true, middle = true),
        )
    }

    @Test
    fun `distance courte parlee`() {
        assertEquals(
            "Ouverture à gauche, à 50 centimètres.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, 0.5f),
        )
        assertEquals(
            "Ouverture à gauche, à 1 mètre.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, 1.2f),
        )
    }

    @Test
    fun `cadran explicite sur demande`() {
        assertEquals(
            "Ouverture à gauche, à 2 mètres, à 9 heures.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, 2f, centerX = 0.1f),
        )
    }

    @Test
    fun `sans distance = sans clause`() {
        assertEquals(
            "Ouverture à gauche.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.Fr, null),
        )
        assertEquals(
            "Portail ouvert à droite.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Right, SpeechLanguage.Fr, Float.NaN, isGate = true),
        )
    }

    @Test
    fun `phrases EN miroirs`() {
        assertEquals(
            "Fence closed ahead.",
            phrase(FenceOpeningState.Closed, FenceOpeningPosition.Ahead, SpeechLanguage.En),
        )
        assertEquals(
            "Gate open on the left, 2 meters away.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Left, SpeechLanguage.En, 2f, isGate = true),
        )
        assertEquals(
            "Opening ahead, 2 meters away.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.En, 2f),
        )
        assertEquals(
            "Gap in the middle.",
            phrase(FenceOpeningState.Open, FenceOpeningPosition.Ahead, SpeechLanguage.En, 2f, middle = true),
        )
    }
}
