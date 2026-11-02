package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte anti-confusion du vert piéton : pixels synthétiques (frontières des
 * seuils) + échantillons réels (`feuxfr/` et `feuxfr2/` Panoramax, `feuxfr3/`
 * téléphone, voir ATTRIBUTION.md). Les seuils sont calibrés sur feux
 * français (10/2026) : ces tests verrouillent 0 vert sur rouge et le rappel
 * bonhomme. Détection au plus large (31/10, ordre user) : élancés comme
 * ronds passent l'élancement 1,0 ; les disques au contour cyan complet
 * restent refusés quand leur tache est large **et pleine** ([WIDE_FILL_MAX]),
 * un bonhomme large restant ajouré — réalité par ancre.
 */
class PedLightGateTest {

    /** Cyan 200° : vert-de-mer français, refusé par l'ancienne borne 195. */
    private val CYAN_200 = 0xFF00AAFF.toInt()

    /**
     * Tache verte **plus large que haute** (16 x 8) dans la moitie basse :
     * pleine (disque de face, remplissage ~0,78) ou creuse (contour,
     * remplissage ~0,35) — meme boite englobante, seule la densite change.
     */
    private fun wideBlob(filled: Boolean): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 12 until 20) {
            for (x in 2 until 18) {
                val dx = (x - 9.5f) / 8f
                val dy = (y - 15.5f) / 4f
                val r = dx * dx + dy * dy
                val inside = if (filled) r <= 1f else r in 0.75f..1f
                if (inside) pixels[y * 20 + x] = GREEN
            }
        }
        return pixels
    }

    @Test
    fun `barre verte basse elancee - acceptee`() {
        assertTrue(PedLightGate.acceptGreen(barBottom(), 20, 20))
    }

    @Test
    fun `disque vert centre - admis (detection au plus large)`() {
        assertTrue(PedLightGate.acceptGreen(disc(10, 10, 5), 20, 20))
    }

    @Test
    fun `disque vert bas - admis (detection au plus large)`() {
        assertTrue(PedLightGate.acceptGreen(disc(10, 14, 5), 20, 20))
    }

    @Test
    fun `barre verte haute - refusee (vert confine en haut)`() {
        assertFalse(PedLightGate.acceptGreen(barTop(), 20, 20))
    }

    @Test
    fun `vert melange de rouge franc - refuse`() {
        val pixels = barBottom()
        paintDisc(pixels, 20, 20, 10, 5, 3, RED)
        assertFalse(PedLightGate.acceptGreen(pixels, 20, 20))
    }

    @Test
    fun `large et plein - disque de face refuse`() {
        assertFalse(PedLightGate.acceptGreen(wideBlob(filled = true), 20, 20))
    }

    @Test
    fun `large et ajoure - accepte (meme boite, tache creuse)`() {
        assertTrue(PedLightGate.acceptGreen(wideBlob(filled = false), 20, 20))
    }

    @Test
    fun `cadres vides ou incoherents - refuses`() {
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { BLACK }, 10, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { GRAY }, 10, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(0), 0, 0))
        assertFalse(PedLightGate.acceptGreen(IntArray(10), 10, 10))
    }

    @Test
    fun `reflet vert minuscule - refuse`() {
        val pixels = IntArray(400) { BLACK }
        pixels[15 * 20 + 10] = GREEN
        pixels[15 * 20 + 11] = GREEN
        assertFalse(PedLightGate.acceptGreen(pixels, 20, 20))
    }

    @Test
    fun `barre decalee a gauche ou droite - acceptee (translation)`() {
        assertTrue(PedLightGate.acceptGreen(barBottom(x0 = 2, x1 = 5), 20, 20))
        assertTrue(PedLightGate.acceptGreen(barBottom(x0 = 14, x1 = 17), 20, 20))
    }

    @Test
    fun `barre sous-exposee - acceptee puis refusee, jamais d invention`() {
        assertTrue(PedLightGate.acceptGreen(dim(barBottom(), 0.5f), 20, 20))
        assertFalse(PedLightGate.acceptGreen(dim(barBottom(), 0.25f), 20, 20))
    }

    @Test
    fun `verdict - cadre tout vert annonce vert (detection au plus large)`() {
        assertEquals(
            CrossingState.Green,
            PedLightGate.verdict(IntArray(100) { CYAN_GREEN }, 10, 10),
        )
    }

    @Test
    fun `verdict - le rouge ne devient jamais vert`() {
        assertNotEquals(
            CrossingState.Green,
            PedLightGate.verdict(disc(10, 10, 5, RED), 20, 20),
        )
    }

    @Test
    fun `verdict - disque vert admis (detection au plus large)`() {
        assertEquals(CrossingState.Green, PedLightGate.verdict(disc(10, 10, 5), 20, 20))
    }

    @Test
    fun `verdict - noir et gris attendent (binaire)`() {
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100) { BLACK }, 10, 10))
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100) { GRAY }, 10, 10))
    }

    @Test
    fun `verdict - bonhomme vert sombre accepte`() {
        assertEquals(CrossingState.Green, PedLightGate.verdict(barAt(4), 20, 20))
    }

    @Test
    fun `verdict - micro bonhomme bas accepte (fraction 0,0052)`() {
        // 48x48, barre 3x4 en bas : fraction 0,0052, dans l'ancienne bande
        // (rouge aux seuils 0,0035 et 0,0048) — miroir Python en vis-à-vis.
        // 3 px de large depuis le 02/11 : la barre de 2 px de la version
        // d'origine (5 % de 48 = 2,4 px) tombe sous le plancher de largeur,
        // voir le test suivant — c'est le seul cas de rappel perdu ici.
        val pixels = IntArray(48 * 48) { BLACK }
        for (y in 44 until 48) {
            for (x in 23..25) pixels[y * 48 + x] = GREEN
        }
        assertEquals(CrossingState.Green, PedLightGate.verdict(pixels, 48, 48))
    }

    @Test
    fun `largeur - micro bonhomme de 2 px sur 48 - refuse (plancher 2,4 px)`() {
        val pixels = IntArray(48 * 48) { BLACK }
        for (y in 44 until 48) {
            for (x in 23..24) pixels[y * 48 + x] = GREEN
        }
        assertEquals(CrossingState.Red, PedLightGate.verdict(pixels, 48, 48))
    }

    @Test
    fun `largeur - bandes vertes de 1 a 4 px sur crop 96 - refusees`() {
        // 5 % de 96 = 4,8 px : ce sont les bandes de 1 à 4 px du feu voisin
        // ou du panneau cycliste entré dans le crop — 22 faux verts sur 40
        // rouges à l'échelle de production (96 px) avant cette règle.
        for (widthPx in 1..4) {
            val pixels = band(96, widthPx, 20)
            assertFalse("acceptGreen, bande $widthPx px", PedLightGate.acceptGreen(pixels, 96, 96))
            assertEquals("verdict, bande $widthPx px", CrossingState.Red, PedLightGate.verdict(pixels, 96, 96))
        }
    }

    @Test
    fun `largeur - 4 px refuse, 5 px accepte sur crop 96 (frontiere 4,8)`() {
        assertFalse(PedLightGate.acceptGreen(band(96, 4, 20), 96, 96))
        assertTrue(PedLightGate.acceptGreen(band(96, 5, 20), 96, 96))
    }

    @Test
    fun `largeur - tache verte de 7 px sur crop 96 - acceptee`() {
        assertEquals(CrossingState.Green, PedLightGate.verdict(band(96, 7, 25), 96, 96))
    }

    @Test
    fun `largeur - vrai vert large sur crop 96 - accepte`() {
        assertTrue(PedLightGate.acceptGreen(band(96, 12, 30), 96, 96))
    }

    @Test
    fun `largeur - fraction, pas pixels - 4 px refuse a 96, accepte a 48`() {
        // Même bande, deux échelles : la règle suit le crop (5 % de 48 = 2,4).
        assertFalse(PedLightGate.acceptGreen(band(96, 4, 20), 96, 96))
        assertTrue(PedLightGate.acceptGreen(band(48, 4, 12), 48, 48))
    }

    @Test
    fun `largeur - crop 1x1 et degeneres - pas de crash`() {
        assertFalse(PedLightGate.acceptGreen(IntArray(1) { GREEN }, 1, 1))
        assertFalse(PedLightGate.acceptGreen(IntArray(1) { BLACK }, 1, 1))
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(1) { GREEN }, 1, 1))
    }

    @Test
    fun `largeur - crop tout vert - accepte (largeur = crop)`() {
        assertTrue(PedLightGate.acceptGreen(IntArray(96 * 96) { CYAN_GREEN }, 96, 96))
        assertEquals(CrossingState.Green, PedLightGate.verdict(IntArray(96 * 96) { CYAN_GREEN }, 96, 96))
    }

    /** Bande verte verticale de [w] px, [h] px de haut, calée en bas d'un crop carré. */
    private fun band(size: Int, w: Int, h: Int): IntArray {
        val pixels = IntArray(size * size) { BLACK }
        val x0 = size / 2
        for (y in size - h until size) {
            for (x in x0 until minOf(x0 + w, size)) pixels[y * size + x] = GREEN
        }
        return pixels
    }

    @Test
    fun `projection - un bonhomme cyan 200 degres passe la porte (plus de borne haute)`() {
        // Le vert-de-mer français monte au cyan : la projection lit p = 0,67 sur
        // 0x00AAFF là où la borne 195 refusait la teinte 200. La décision de
        // l'arbre reste sur ses traits de teinte (contrat séparé, pas réentraîné).
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 10 until 20) {
            for (x in 8..11) pixels[y * 20 + x] = CYAN_200
        }
        assertTrue(PedLightGate.acceptGreen(pixels, 20, 20))
    }

    @Test
    fun `lampe allumee - vert et rouge vrais, le reste faux`() {
        assertTrue(PedLightGate.hasLampSignal(barBottom(), 20, 20))
        assertTrue(PedLightGate.hasLampSignal(IntArray(100) { RED }, 10, 10))
        assertFalse(PedLightGate.hasLampSignal(IntArray(100) { BLACK }, 10, 10))
        assertFalse(PedLightGate.hasLampSignal(IntArray(100) { GRAY }, 10, 10))
        assertFalse(PedLightGate.hasLampSignal(IntArray(10), 10, 10))
        assertFalse(PedLightGate.hasLampSignal(IntArray(0), 0, 0))
    }

    @Test
    fun `seuil fraction - 3px elances passent, 1px refuse`() {
        assertTrue(PedLightGate.acceptGreen(bar(1, 3), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(1, 2), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(1, 1), 20, 20))
    }

    @Test
    fun `seuil elancement - 1,0 accepte, plus large que haut refuse`() {
        assertTrue(PedLightGate.acceptGreen(bar(4, 4), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(5, 4), 20, 20))
    }

    @Test
    fun `seuil bas - 50 pourcent accepte, 20 pourcent juge sur forme, 10 pourcent refuse`() {
        assertTrue(PedLightGate.acceptGreen(barAt(5), 20, 20))
        // 01/11 (ordre user : vert plus facile) : 20 % n'est plus un rejet
        // dur — tel_g07 réel (bottom 0,23) basculait rouge sur 1 px de
        // jitter de boîte. La forme tranche, une barre pleine passe.
        assertTrue(PedLightGate.acceptGreen(barAt(2), 20, 20))
        assertFalse(PedLightGate.acceptGreen(barAt(1), 20, 20))
    }

    @Test
    fun `feux fr - rouges sombres jamais verts`() {
        assertNotEquals(CrossingState.Green, verdictOf("fr_red_dim_138.ppm"))
        assertNotEquals(CrossingState.Green, verdictOf("fr_red_noise_732.ppm"))
    }

    @Test
    fun `feux fr - bonhomme rouge reste rouge`() {
        assertEquals(CrossingState.Red, verdictOf("fr_red_fig_548.ppm"))
    }

    @Test
    fun `feux fr - bonhommes verts acceptes`() {
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_21.ppm"))
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_302.ppm"))
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_243.ppm"))
    }

    @Test
    fun `feux fr - disque proche admis (detection au plus large)`() {
        assertEquals(CrossingState.Green, verdictOf("fr_cardisc_close_183.ppm"))
    }

    @Test
    fun `feux fr - disque rond refuse (tache plus large que haute)`() {
        assertEquals(CrossingState.Red, verdictOf("fr_cardisc_367.ppm"))
    }

    @Test
    fun `feux fr - pose large verte admise (rappel)`() {
        assertEquals(CrossingState.Green, verdictOf("fr_green_widepose_355.ppm"))
    }

    @Test
    fun `r1b - bonhommes cadres haut acceptes (refuses sans R1b)`() {
        for (name in listOf("r1b_g01.ppm", "r1b_g02.ppm", "r1b_g03.ppm", "r1b_g04.ppm", "r1b_g05.ppm")) {
            assertEquals(CrossingState.Green, verdict2Of(name))
        }
    }

    @Test
    fun `r1b - verts stables toujours acceptes`() {
        assertEquals(CrossingState.Green, verdict2Of("st_g01.ppm"))
        assertEquals(CrossingState.Green, verdict2Of("st_g02.ppm"))
    }

    @Test
    fun `r1b - rouges toujours refuses`() {
        assertEquals(CrossingState.Red, verdict2Of("st_r01.ppm"))
        assertEquals(CrossingState.Red, verdict2Of("st_r02.ppm"))
    }

    @Test
    fun `r1b - disque elance admis (detection au plus large)`() {
        assertEquals(CrossingState.Green, verdict2Of("st_c01.ppm"))
    }

    @Test
    fun `r1b - disque rond refuse (tache plus large que haute)`() {
        assertEquals(CrossingState.Red, verdict2Of("st_c02.ppm"))
    }

    @Test
    fun `tel - bonhommes verts telephone acceptes (cyan et delaves)`() {
        assertEquals(CrossingState.Green, verdict3Of("tel_g01.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g02.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g03.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g04.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g05.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g06.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g07.ppm"))
    }

    @Test
    fun `tel - verts larges et ajoures acceptes (tache plus large que haute)`() {
        // 01/11 avant correction : ces deux verts réels tombaient sur
        // ELONGATION_MIN (bonhomme large, halo). Un disque large est PLEIN
        // (remplissage 0,75-0,88), eux sont ajourés (0,09 et 0,16).
        assertEquals(CrossingState.Green, verdict3Of("tel_g08.ppm"))
        assertEquals(CrossingState.Green, verdict3Of("tel_g09.ppm"))
    }

    @Test
    fun `tel - bonhommes rouges telephone refuses`() {
        assertEquals(CrossingState.Red, verdict3Of("tel_r01.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r02.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r03.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r04.ppm"))
    }

    @Test
    fun `jitter 1px - verts cadres haut restent verts (plancher 15)`() {
        // 01/11 (ordre user : vert plus facile) : tel_g07 (bottom 0,23) et
        // st_g02 tombaient au rouge quand la boîte montait de 1 px
        // (jitter SAM d'une frame à l'autre) — le plancher 15 % + la forme
        // les gardent verts, sans toucher aux rouges.
        val (tel, telW, telH) = pixelsOf(javaClass.classLoader, "feuxfr3", "tel_g07.ppm")
        assertEquals(CrossingState.Green, PedLightGate.verdict(shiftedUp(tel, telW, telH), telW, telH))
        val (st, stW, stH) = pixelsOf(javaClass.classLoader, "feuxfr2", "st_g02.ppm")
        assertEquals(CrossingState.Green, PedLightGate.verdict(shiftedUp(st, stW, stH), stW, stH))
    }

    /** Barre verte verticale dans la moitié basse (bonhomme simplifié). */
    private fun barBottom(x0: Int = 8, x1: Int = 11): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 10 until 20) {
            for (x in x0..x1) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Même barre dans la moitié haute (mauvaise position). */
    private fun barTop(): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 0 until 10) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Barre `w` x `h` calée en bas (fraction et élancement réglables). */
    private fun bar(w: Int, h: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 20 - h until 20) {
            for (x in 10 - w / 2 until 10 - w / 2 + w) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Barre 4 x 10 placée en [y0] (part basse réglable). */
    private fun barAt(y0: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in y0 until y0 + 10) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Crop décalé de 1 px vers le haut, rempli de noir (jitter de boîte). */
    private fun shiftedUp(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size) { BLACK }
        for (y in 0 until height - 1) {
            System.arraycopy(pixels, (y + 1) * width, out, y * width, width)
        }
        return out
    }

    /** Verdict sur un crop réel `feuxfr/` (PPM ≤ 48 px, voir ATTRIBUTION.md). */
    private fun verdictOf(name: String): CrossingState {
        val (pixels, width, height) = pixelsOf(javaClass.classLoader, "feuxfr", name)
        return PedLightGate.verdict(pixels, width, height)
    }

    /** Verdict sur un crop réel `feuxfr2/` (lot R1b, voir ATTRIBUTION.md). */
    private fun verdict2Of(name: String): CrossingState {
        val (pixels, width, height) = pixelsOf(javaClass.classLoader, "feuxfr2", name)
        return PedLightGate.verdict(pixels, width, height)
    }

    /** Verdict sur un crop réel `feuxfr3/` (lot téléphone, voir ATTRIBUTION.md). */
    private fun verdict3Of(name: String): CrossingState {
        val (pixels, width, height) = pixelsOf(javaClass.classLoader, "feuxfr3", name)
        return PedLightGate.verdict(pixels, width, height)
    }
}
