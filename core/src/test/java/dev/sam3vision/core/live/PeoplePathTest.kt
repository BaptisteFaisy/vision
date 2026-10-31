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
 * passage à gauche à 0,8 mètre, un mur de personnes avoue qu'il n'y a
 * pas de passage, et le couloir ([Guidance]) suit ce cap quand il existe.
 */
class PeoplePathTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    /** Label mesuré avec ses bords (boîte SAM complète). */
    private fun boxed(label: String, x0: Float, x1: Float, meters: Float?): PlacedLabel =
        PlacedLabel(label, (x0 + x1) / 2f, 0.5f, meters, x0, x1)

    @Test
    fun `personne pile en face on passe a gauche a 0,8 metre`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.103f, path.headingX, 0.02f)
        assertEquals(0.8f, path.clearanceM, 0.02f)
    }

    @Test
    fun `personne decalee a gauche on passe a droite`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.4f, 2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Right, path.side)
        assertTrue(path.headingX > 0.5f)
        assertEquals(0.8f, path.clearanceM, 0.02f)
    }

    @Test
    fun `deux personnes a deux profondeurs on longe le bord gauche`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.75f, 2f))
        val path = PeoplePath.plan(labels)!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.102f, path.headingX, 0.005f)
        assertEquals(0.8f, path.clearanceM, 0.01f)
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
        assertEquals(0.098f, path.headingX, 0.02f)
        assertEquals(0.8f, path.clearanceM, 0.02f)
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
    fun `bruit +10 pourcent personne centree cap stable clearance 0,70`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2.2f)))!!

        assertTrue(path.fits)
        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.140f, path.headingX, 0.005f)
        assertEquals(0.70f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `bruit +30 pourcent personne centree clearance 0,56`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 2.6f)))!!

        assertEquals(0.195f, path.headingX, 0.005f)
        assertEquals(0.56f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `bruit centre 0,03 personne centree clearance 0,72 des deux cotes`() {
        val gauche = PeoplePath.plan(listOf(placed("personne", 0.53f, 2f)))!!

        assertEquals(0.133f, gauche.headingX, 0.005f)
        val droite = PeoplePath.plan(listOf(placed("personne", 0.47f, 2f)))!!

        assertEquals(GuidanceSide.Right, droite.side)
        assertEquals(0.867f, droite.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.5f, 2f))

        assertEquals(0.72f, vraieClearance(vrais, gauche.headingX), 0.01f)
        assertEquals(0.72f, vraieClearance(vrais, droite.headingX), 0.01f)
    }

    @Test
    fun `bruit combine +30 pourcent et centre personne centree clearance 0,48`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.53f, 2.6f)))!!

        assertEquals(0.225f, path.headingX, 0.005f)
        assertEquals(0.48f, vraieClearance(listOf(placed("personne", 0.5f, 2f)), path.headingX), 0.01f)
    }

    @Test
    fun `sous-estimation -30 pourcent aucun passage a 0,8 m`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.5f, 1.4f)))!!

        // À 1,4 m lus, 1,05 m de chaque côté ne tiennent pas dans le champ :
        // le planificateur avoue, le couloir garde son côté historique.
        assertFalse(path.fits)
    }

    @Test
    fun `deux personnes +30 pourcent cap stable clearance 0,56`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.35f, 2.6f), placed("personne", 0.75f, 2.6f)))!!

        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.045f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.35f, 2f), placed("personne", 0.75f, 2f))

        assertEquals(0.56f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `deux personnes lues ecartees faux creneau falaise`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.24f, 2.6f), placed("personne", 0.86f, 2.6f)))!!

        assertTrue(path.fits)
        assertEquals(0.545f, path.headingX, 0.005f) // Créneau fantôme.
        assertEquals(0.8f, path.clearanceM, 0.01f) // Annonce fausse et confiante.
        val vrais = listOf(placed("personne", 0.30f, 2f), placed("personne", 0.75f, 2f))

        assertEquals(0.29f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `deux personnes +20 pourcent et ecartees plus de passage`() {
        val path = PeoplePath.plan(listOf(placed("personne", 0.32f, 2.4f), placed("personne", 0.78f, 2.4f)))!!

        assertFalse(path.fits)
    }

    @Test
    fun `discriminant proche +10 pourcent cap a gauche clearance 0,70`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.65f))
        val path = PeoplePath.plan(labels)!!

        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.069f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.70f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant proche +30 pourcent reste a gauche clearance 0,64`() {
        val labels = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.95f))
        val path = PeoplePath.plan(labels)!!

        assertEquals(GuidanceSide.Left, path.side)
        assertEquals(0.102f, path.headingX, 0.005f)
        assertEquals(0.8f, path.clearanceM, 0.01f) // Annonce : 0,8 mesurés, 0,64 vrais.
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.64f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant lointain +0,03 cap colle au bord clearance 0,80`() {
        val labels = listOf(placed("personne", 0.33f, 4f), placed("personne", 0.55f, 1.5f))
        val path = PeoplePath.plan(labels)!!

        assertEquals(0.021f, path.headingX, 0.005f)
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))

        assertEquals(0.80f, vraieClearance(vrais, path.headingX), 0.01f)
    }

    @Test
    fun `discriminant proche centre -0,03 plus de passage, +0,03 valide`() {
        val vrais = listOf(placed("personne", 0.30f, 4f), placed("personne", 0.55f, 1.5f))
        val vers = PeoplePath.plan(listOf(placed("personne", 0.30f, 4f), placed("personne", 0.52f, 1.5f)))!!

        assertFalse(vers.fits)
        val loin = PeoplePath.plan(listOf(placed("personne", 0.30f, 4f), placed("personne", 0.58f, 1.5f)))!!

        assertEquals(0.051f, loin.headingX, 0.005f)
        assertEquals(0.74f, vraieClearance(vrais, loin.headingX), 0.01f)
    }

    @Test
    fun `clearsSide personne proche bloque son cote seul`() {
        val proche = listOf(placed("personne", 0.3f, 2f))

        assertFalse(PeoplePath.clearsSide(proche, GuidanceSide.Left))
        assertTrue(PeoplePath.clearsSide(proche, GuidanceSide.Right))
    }

    @Test
    fun `clearsSide personne loin passe`() {
        val loin = listOf(placed("personne", 0.1f, 4f))

        assertTrue(PeoplePath.clearsSide(loin, GuidanceSide.Left))
    }

    @Test
    fun `clearsSide personne non mesuree bloque son cote seul`() {
        val inconnue = listOf(placed("personne", 0.3f, null))

        assertFalse(PeoplePath.clearsSide(inconnue, GuidanceSide.Left))
        assertTrue(PeoplePath.clearsSide(inconnue, GuidanceSide.Right))
    }

    @Test
    fun `clearsSide personne centree non mesuree bloque les deux`() {
        val centre = listOf(placed("personne", 0.5f, null))

        assertFalse(PeoplePath.clearsSide(centre, GuidanceSide.Left))
        assertFalse(PeoplePath.clearsSide(centre, GuidanceSide.Right))
    }

    @Test
    fun `clearsSide ignore les obstacles non humains`() {
        val poteau = listOf(placed("poteau", 0.3f, 1f))

        assertTrue(PeoplePath.clearsSide(poteau, GuidanceSide.Left))
        assertTrue(PeoplePath.clearsAhead(poteau))
    }

    @Test
    fun `clearsAhead ecart a l axe, bande centrale si inconnue`() {
        assertTrue(PeoplePath.clearsAhead(emptyList()))
        assertFalse(PeoplePath.clearsAhead(listOf(placed("personne", 0.4f, 2f))))
        assertTrue(PeoplePath.clearsAhead(listOf(placed("personne", 0.1f, 4f), placed("personne", 0.9f, 4f))))
        assertFalse(PeoplePath.clearsAhead(listOf(placed("personne", 0.5f, null))))
        assertTrue(PeoplePath.clearsAhead(listOf(placed("personne", 0.1f, null))))
    }

    @Test
    fun `egalite de distance le plus grand ecart gagne`() {
        val perX = 2f * 2f * LensSpec.halfTangent(LensSpec.DEFAULT_H_FOV_DEG)
        val labels = listOf(
            placed("personne", 0.9f - 1.05f / perX, 2f),
            placed("poteau", 0.1f + 0.25f / perX, 2f),
        )
        val path = PeoplePath.plan(labels)!!

        assertTrue(path.fits)
        assertEquals(0.9f, path.headingX, 0.01f)
        assertEquals(GuidanceSide.Right, path.side)
    }
}
