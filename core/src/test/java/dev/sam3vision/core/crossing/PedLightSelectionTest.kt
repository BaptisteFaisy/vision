package dev.sam3vision.core.crossing

import dev.sam3vision.core.crossing.PedLightSelection.Candidate
import dev.sam3vision.core.crossing.PedLightSelection.Context
import dev.sam3vision.core.crossing.PedLightSelection.Lens
import dev.sam3vision.core.crossing.PedLightSelection.Veto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PedLightSelection] verrouillé sur les traits mesurés le 02/11/2026 sur
 * 58 séquences de rue (`~/.scratch/feux-sel/RAPPORT.md`) : une lentille ronde
 * de feu véhicule (remplissage 0,61–0,83) est refusée, un pictogramme
 * (0,23–0,57, portrait, troué) est retenu, un marquage au sol trop petit est
 * refusé, un feu au niveau de la chaussée aussi — et **tout refus est un
 * silence** : `choose` rend `null`, l'aval rend `Red`, jamais un vert.
 *
 * Les cotes reprennent les contre-exemples réels : `151727` (lentille 24 px,
 * remplissage 0,76, élongation 0,79, y = 0,62), `155503` (19 px qui saute à
 * 37 px), `161541` (tache de 8 px). Cadre : 480 × 853, crop de la taille de la
 * boîte (échelle 1), donc `sideRelative` = côté de lentille / 480.
 */
class PedLightSelectionTest {

    private fun ctx(
        prior: List<Float>? = null,
        priorLensSide: Float = 0f,
        since: Int = Int.MAX_VALUE,
    ) = Context(480, 853, prior, priorLensSide, since)

    /** Boîte de côté [side] px centrée en ([cx], [cy]) fractionnaires. */
    private fun at(cx: Float, cy: Float, side: Float): List<Float> {
        val halfX = side / 480f / 2f
        val halfY = side / 853f / 2f
        return listOf(cx - halfX, cy - halfY, cx + halfX, cy + halfY)
    }

    /** Candidat dont le crop fait la taille de la boîte (échelle 1). */
    private fun candidate(cy: Float, boxSide: Float, lens: Lens): Candidate =
        Candidate(at(0.5f, cy, boxSide), lens, boxSide.toInt(), boxSide.toInt())

    @Test
    fun vehicleDiscIsVetoed() {
        // 151727 : lentille ronde de feu véhicules, 24 px, remplissage 0,76.
        val disc = candidate(0.62f, 48f, Lens(24f, 30f, 0.76f, 0))
        assertEquals(Veto.DISC, PedLightSelection.rejection(disc, ctx()))
        assertNull(PedLightSelection.choose(listOf(disc), ctx()))
    }

    @Test
    fun pedestrianPictogramIsRetained() {
        // Bon vert mesuré : 26 × 46 px, remplissage 0,41, trous du bonhomme.
        val man = candidate(0.22f, 92f, Lens(26f, 46f, 0.41f, 6))
        assertNull(PedLightSelection.rejection(man, ctx()))
        assertEquals(0, PedLightSelection.choose(listOf(man), ctx()))
    }

    @Test
    fun groundMarkingIsTooSmall() {
        // 161541 : tache verte de 8 px, remplissage 0,70, élongation 0,8.
        val marking = candidate(0.10f, 16f, Lens(8f, 10f, 0.70f, 0))
        assertEquals(Veto.TOO_SMALL, PedLightSelection.rejection(marking, ctx()))
        assertNull(PedLightSelection.choose(listOf(marking), ctx()))
    }

    @Test
    fun stripAndMergedLensAreVetoed() {
        val strip = candidate(0.20f, 120f, Lens(60f, 30f, 0.50f, 0))
        assertEquals(Veto.STRIP, PedLightSelection.rejection(strip, ctx()))
        val merged = candidate(0.20f, 380f, Lens(170f, 190f, 0.50f, 2))
        assertEquals(Veto.MERGED, PedLightSelection.rejection(merged, ctx()))
    }

    @Test
    fun lightAtRoadLevelIsVetoed() {
        // 151727 : la boîte glisse à 0,62 de la hauteur de frame.
        val low = candidate(0.62f, 92f, Lens(26f, 46f, 0.41f, 6))
        assertEquals(Veto.ROAD_LEVEL, PedLightSelection.rejection(low, ctx()))
        assertNull(PedLightSelection.choose(listOf(low), ctx()))
    }

    @Test
    fun sizeJumpBreaksTheTrack() {
        // 155503 : la lentille passe de 19 à 37 px entre deux frames.
        val prior = at(0.2f, 0.28f, 38f)
        val jumped = Candidate(at(0.2f, 0.28f, 74f), Lens(37f, 37f, 0.42f, 1), 74, 74)
        assertEquals(Veto.SIZE_JUMP, PedLightSelection.rejection(jumped, ctx(prior, 19f, 0)))
        assertNull(PedLightSelection.choose(listOf(jumped), ctx(prior, 19f, 0)))
        val same = Candidate(at(0.2f, 0.28f, 40f), Lens(19f, 20f, 0.41f, 4), 40, 40)
        assertNull(PedLightSelection.rejection(same, ctx(prior, 19f, 0)))
        assertEquals(0, PedLightSelection.choose(listOf(same), ctx(prior, 19f, 0)))
    }

    @Test
    fun priorGateRefusesFarJumps() {
        val prior = at(0.2f, 0.30f, 40f)
        val near = Candidate(at(0.24f, 0.30f, 40f), Lens(26f, 46f, 0.41f, 6), 40, 40)
        assertEquals(0, PedLightSelection.choose(listOf(near), ctx(prior, 26f, 0)))
        val far = Candidate(at(0.9f, 0.30f, 40f), Lens(26f, 46f, 0.41f, 6), 40, 40)
        assertNull(PedLightSelection.choose(listOf(far), ctx(prior, 26f, 0)))
    }

    @Test
    fun withoutPriorThePictogramWins() {
        // Deux candidats : un disque de voiture et un bonhomme éloigné.
        val disc = Candidate(at(0.3f, 0.40f, 50f), Lens(25f, 26f, 0.78f, 0), 50, 50)
        val man = Candidate(at(0.7f, 0.22f, 92f), Lens(26f, 46f, 0.41f, 6), 92, 92)
        assertEquals(1, PedLightSelection.choose(listOf(disc, man), ctx()))
        assertNull(PedLightSelection.choose(listOf(disc), ctx()))
        assertNull(PedLightSelection.choose(emptyList(), ctx()))
    }

    @Test
    fun stalePriorDoesNotAuthoriseAnUnlikelyLens() {
        val disc = Candidate(at(0.3f, 0.40f, 50f), Lens(25f, 26f, 0.78f, 0), 50, 50)
        val prior = at(0.3f, 0.40f, 50f)
        assertNull(PedLightSelection.choose(listOf(disc), ctx(prior, 25f, 30)))
    }

    @Test
    fun scaleOfTheCropDoesNotChangeTheSizeVeto() {
        // Même lentille vue à travers un crop rééchantillonné ×2 : même verdict.
        val box = at(0.5f, 0.10f, 16f)
        val raw = Candidate(box, Lens(8f, 10f, 0.70f, 0), 16, 16)
        val scaled = Candidate(box, Lens(4f, 5f, 0.70f, 0), 8, 8)
        assertEquals(Veto.TOO_SMALL, PedLightSelection.rejection(raw, ctx()))
        assertEquals(Veto.TOO_SMALL, PedLightSelection.rejection(scaled, ctx()))
        assertTrue(PedLightSelection.sideRelative(scaled, ctx()) <= 0.033f)
    }

    // ------------------------------------------------------------------ lentille

    @Test
    fun lensOfEmptyOrInconsistentCropIsNull() {
        assertNull(PedLightSelection.LensMeasure.of(IntArray(16) { BLACK }, 4, 4))
        assertNull(PedLightSelection.LensMeasure.of(IntArray(4) { GREEN }, 3, 3))
        assertNull(PedLightSelection.LensMeasure.of(IntArray(0), 0, 0))
    }

    /** Disque vert plein dans un cadre carré noir. */
    private fun discIn(side: Int, radius: Int): IntArray {
        val pixels = IntArray(side * side) { BLACK }
        paintDisc(pixels, side, side, side / 2, side / 2, radius, GREEN)
        return pixels
    }

    @Test
    fun lensOfDiscFillsItsBoxAndHasNoHole() {
        val lens = PedLightSelection.LensMeasure.of(discIn(80, 14), 80, 80)
        assertNotNull(lens)
        assertTrue("remplissage ${lens!!.fill}", lens.fill in 0.62f..0.90f)
        assertEquals(0, lens.holes)
        // Boîte de 80 px (16,7 % de la frame) dans un crop de 80 px.
        val candidate = Candidate(at(0.5f, 0.20f, 80f), lens, 80, 80)
        assertEquals(Veto.DISC, PedLightSelection.rejection(candidate, ctx()))
    }

    @Test
    fun lensOfHollowBlobHasAHole() {
        val pixels = IntArray(40 * 40) { BLACK }
        for (y in 12 until 28) for (x in 16 until 24) {
            if (y in 18..21 && x in 18..21) continue
            pixels[y * 40 + x] = GREEN
        }
        val lens = PedLightSelection.LensMeasure.of(pixels, 40, 40)
        assertNotNull(lens)
        assertTrue("trous ${lens!!.holes}", lens.holes >= 4)
        assertTrue("remplissage ${lens.fill}", lens.fill < 0.95f)
    }
}
