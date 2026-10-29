package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Consigne latérale : une personne proche qui s'approche par un côté conseille
 * un décalage de l'autre. Ni statique ni lointaine ni hors champ avant : le
 * suivi exige des diminutions consécutives (pas un bond).
 */
class LateralNudgeTest {

    private fun personne(x: Float, meters: Float?) = PlacedLabel("personne", x, 0.5f, meters)

    /** 3 frames d'approche à droite : le 3e conseille la gauche. */
    private fun approcheDroite(suivi: LateralNudgeTracker): LateralNudge? {
        suivi.onFrame(0L, listOf(personne(0.65f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.6f)))
        return suivi.onFrame(500L, listOf(personne(0.65f, 2.2f)))
    }

    @Test
    fun `personne qui s approche a droite conseille la gauche`() {
        val nudge = approcheDroite(LateralNudgeTracker())

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(2.2f, nudge.meters, 0.001f)
    }

    @Test
    fun `personne qui s approche a gauche conseille la droite`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.35f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.35f, 2.6f)))

        val nudge = suivi.onFrame(500L, listOf(personne(0.35f, 2.2f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `personne statique ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 2.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.0f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.0f))))
    }

    @Test
    fun `personne qui s eloigne ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 2.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.4f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.8f))))
    }

    @Test
    fun `objet non personne ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        val voiture = { m: Float -> PlacedLabel("car", 0.65f, 0.5f, m) }
        suivi.onFrame(0L, listOf(voiture(3.0f)))
        suivi.onFrame(250L, listOf(voiture(2.6f)))

        assertNull(suivi.onFrame(500L, listOf(voiture(2.2f))))
    }

    @Test
    fun `distance inconnue ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, null)))
        suivi.onFrame(250L, listOf(personne(0.65f, null)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, null))))
    }

    @Test
    fun `des deux cotes a la fois on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        val deux = { m: Float -> listOf(personne(0.35f, m), personne(0.65f, m)) }
        suivi.onFrame(0L, deux(3.0f))
        suivi.onFrame(250L, deux(2.6f))

        assertNull(suivi.onFrame(500L, deux(2.2f)))
    }

    @Test
    fun `hors champ avant on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.1f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.1f, 2.6f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.1f, 2.2f))))
    }

    @Test
    fun `au dela de trois metres on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 4.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 3.6f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 3.2f))))
    }

    @Test
    fun `reset oublie l approche`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.6f)))
        suivi.reset()

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.2f))))
    }

    @Test
    fun `phrase du decalage`() {
        val nudge = LateralNudge(SteeringCap.Left, 2.0f)

        assertEquals("Décalez-vous à gauche.", LateralNudgeText.phrase(nudge.side, SpeechLanguage.Fr))
        assertEquals("Move left.", LateralNudgeText.phrase(nudge.side, SpeechLanguage.En))
    }

    @Test
    fun `cle au seau de distance`() {
        assertEquals("n|left|2", LateralNudge.key(LateralNudge(SteeringCap.Left, 2.2f)))
        assertEquals("n|right|1", LateralNudge.key(LateralNudge(SteeringCap.Right, 1.4f)))
    }
}
