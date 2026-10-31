package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs
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

    /** Écart vrai du cap [x] aux corps VRAIS (scène non bruitée, épaules 0,25 m). */
    private fun vraieClearance(vrais: List<PlacedLabel>, x: Float): Float {
        val champ = 2f * LensSpec.halfTangent(LensSpec.DEFAULT_H_FOV_DEG)
        return vrais.minOf { abs(x - it.centerX) * it.meters!! * champ - PeoplePath.BODY_HALF_WIDTH_M }
    }

    @Test
    fun `bruit +10 pourcent personne centree cap stable clearance 0,43`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2.2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.242f, path.headingX, 0.005f)
        assertEquals(0.432f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `bruit +30 pourcent personne centree clearance 0,33`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2.6f)))!!

        assertEquals(0.282f, path.headingX, 0.005f)
        assertEquals(0.327f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `bruit centre 0,03 personne centree clearance 0,42 des deux cotes`() {
        val gauche = PeoplePath.plan(listOf(placed("personne", 0.53f, 2f)))!!

        assertEquals(0.247f, gauche.headingX, 0.005f)
        val droite = PeoplePath.plan(listOf(placed("personne", 0.47f, 2f)))!!

        assertEquals(GuidanceSide.Right, droite.side)
        assertEquals(0.753f, droite.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.5f, 2f))

        assertEquals(0.421f, vraieClearance(vrais, gauche.headingX), 0.01f)
        assertEquals(0.421f, vraieClearance(vrais, droite.headingX), 0.01f)
    }

    @Test
    fun `bruit combine +30 pourcent et centre personne centree clearance 0,25`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.53f, 2.6f)))!!

        assertEquals(0.312f, path.headingX, 0.005f)
        assertEquals(0.247f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `sous-estimation -30 pourcent cap conservateur clearance 0,82`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 1.4f)))!!

        assertEquals(0.095f, path.headingX, 0.005f)
        assertTrue(vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX) > 0.5f)
    }

    @Test
    fun `deux personnes +30 pourcent cap stable clearance 0,33`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.35f, 2.6f), placed("personne", 0.75f, 2.6f)))!!

        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.132f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.35f, 2f), placed("personne", 0.75f, 2f))

        assertEquals(0.327f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `deux personnes +30 pourcent et ecartees faux creneau falaise`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.32f, 2.6f), placed("personne", 0.78f, 2.6f)))!!

        assertTrue(path.fits)
        assertEquals(0.538f, path.headingX, 0.005f) // Saut depuis 0,132 : créneau fantôme.
        assertEquals(0.5f, path.clearanceM, 0.01f) // Annonce fausse et confiante.
        val vrais = listOf(placed("personne", 0.35f, 2f), placed("personne", 0.75f, 2f))

        assertEquals(0.248f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `deux personnes +20 pourcent et ecartees pas de creneau`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.32f, 2.4f), placed("personne", 0.78f, 2.4f)))!!

        assertEquals(GuidanceSide.Left, path.side) // Seuil falaise : +23,2 %.
        assertEquals(0.084f, path.headingX, 0.005f)
    }

    @Test
    fun `discriminant proche +10 pourcent cap stable a gauche`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.65f))
        val path = PeoplePath.plan(labels)!!

        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.158f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.5f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant proche +30 pourcent bascule a droite falaise`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.95f))
        val path = PeoplePath.plan(labels)!! // Seuil bascule : +29,51 %.

        assertEquals(GuidanceSide.Right, path.side)
        assertEquals(0.841f, path.headingX, 0.005f)
        assertEquals(0.5f, path.clearanceM, 0.01f) // Annonce fausse.
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.327f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant lointain +0,03 cap glisse clearance 0,43`() {
        val labels = listOf(placed("personne", 0.33f, 4f), placed("personne", 0.55f, 1.5f))
        val path = PeoplePath.plan(labels)!!

        assertEquals(0.172f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.426f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant proche centre +-0,03 reste valide`() {
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))
        val vers = PeoplePath.plan(listOf(placed("personne", 0.30f, 4f), placed("personne", 0.52f, 1.5f)))!!

        assertEquals(0.142f, vers.headingX, 0.005f)
        assertEquals(0.560f, vraieClearance(vrais, vers.headingX), 0.01f)
        val loin = PeoplePath.plan(listOf(placed("personne", 0.30f, 4f), placed("personne", 0.58f, 1.5f)))!!

        assertEquals(0.158f, loin.headingX, 0.005f)
        assertEquals(0.5f, vraieClearance(vrais, loin.headingX), 0.01f)
    }
}
