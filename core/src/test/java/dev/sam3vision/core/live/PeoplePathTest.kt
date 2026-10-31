package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chemin entre humains : le cap optimal passe à côté des corps au lieu de
 * viser le côté le moins encombré — une personne pile en face ouvre un
 * passage à gauche à un demi-mètre, un mur de personnes avoue qu'il n'y a
 * pas de passage, et le couloir ([Guidance]) suit ce cap quand il existe.
 */
class PeoplePathTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    /** Label mesuré avec ses bords (boîte SAM complète). */
    private fun boxed(label: String, x0: Float, x1: Float, meters: Float?): PlacedLabel =
        PlacedLabel(label, (x0 + x1) / 2f, 0.5f, meters, x0, x1)

    @Test
    fun `personne pile en face on passe a gauche a un demi-metre`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.217f, path.headingX, 0.02f)
        assertEquals(0.5f, path.clearanceM, 0.02f)
    }

    @Test
    fun `personne decalee a gauche on passe a droite`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.4f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Right, path.side)
        assertTrue(path.headingX > 0.5f)
        assertEquals(0.5f, path.clearanceM, 0.02f)
    }

    @Test
    fun `deux personnes on longe le bord gauche`() {
        val labels = listOf(placed("personne", 0.35f, 2f), placed("personne", 0.75f, 2f))
        val path = PeoplePath.plan(labels)!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.5f, path.clearanceM, 0.01f)
    }

    @Test
    fun `mur de personnes aucun passage`() {
        val labels = listOf(
            placed("personne", 0.3f, 2f),
            placed("personne", 0.5f, 2f),
            placed("personne", 0.7f, 2f),
        )
        val path = PeoplePath.plan(labels)!!

        assertFalse(path.fits)
    }

    @Test
    fun `personne large connue le cap longe sa boite`() {
        val path = PeoplePath.plan(listOf(boxed("personne", 0.4f, 0.6f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(0.211f, path.headingX, 0.02f)
        assertEquals(0.5f, path.clearanceM, 0.02f)
    }

    @Test
    fun `distance inconnue pas de cap devine`() {
        assertNull(PeoplePath.plan(listOf(placed("personne", 0.5f))))
    }

    @Test
    fun `personne lointaine hors de portee pas de cap`() {
        assertNull(PeoplePath.plan(listOf(placed("personne", 0.5f, 10f))))
    }

    @Test
    fun `camion a cheval on longe sa gauche`() {
        val path = PeoplePath.plan(listOf(boxed("camion", 0.55f, 0.95f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.456f, path.headingX, 0.02f)
    }

    @Test
    fun `le couloir suit le cap entre deux personnes`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))
        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals("Gauche.", Guidance.phrase(decision, SpeechLanguage.Fr))
    }

    @Test
    fun `mur de personnes le couloir garde son cote historique`() {
        val labels = listOf(
            placed("personne", 0.3f, 2f),
            placed("personne", 0.5f, 2f),
            placed("personne", 0.7f, 2f),
        )
        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals("Gauche.", Guidance.phrase(decision, SpeechLanguage.Fr))
    }
}
