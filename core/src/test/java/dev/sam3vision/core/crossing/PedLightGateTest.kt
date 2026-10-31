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
 * (tache plus large que haute) restent refusés — réalité par ancre.
 */
class PedLightGateTest {

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
    fun `seuil bas - 50 pourcent accepte, 20 pourcent refuse`() {
        assertTrue(PedLightGate.acceptGreen(barAt(5), 20, 20))
        assertFalse(PedLightGate.acceptGreen(barAt(2), 20, 20))
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
    fun `tel - bonhommes rouges telephone refuses`() {
        assertEquals(CrossingState.Red, verdict3Of("tel_r01.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r02.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r03.ppm"))
        assertEquals(CrossingState.Red, verdict3Of("tel_r04.ppm"))
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
