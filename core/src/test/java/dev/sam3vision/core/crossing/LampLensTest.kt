package dev.sam3vision.core.crossing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LampLens] verrouillé sur la spécification mesurée (`a4/lens.py`, 02/11/2026) :
 * crops synthétiques (noir, disque, grille de LED, deux taches, gris) puis
 * parité avec la référence Python à ±5 % sur neuf crops réels `feuxfr*`.
 *
 * Cadre des tests : 40 × 40 ⇒ rayon de fermeture k = 4 (`CLOSE_FRACTION × 40`),
 * les formes restent donc à plus de k du bord (la fermeture y érode, comme la
 * référence scipy).
 */
class LampLensTest {

    private fun blank(): IntArray = IntArray(SIDE * SIDE) { BLACK }

    private fun put(pixels: IntArray, x: Int, y: Int, color: Int) {
        pixels[y * SIDE + x] = color
    }

    /** Disque plein de rayon 6 au centre : la lentille d'un feu allumé. */
    private fun disc(): IntArray {
        val pixels = blank()
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            if ((x - 20) * (x - 20) + (y - 20) * (y - 20) <= 36) put(pixels, x, y, GREEN)
        }
        return pixels
    }

    /** Quatre points de LED espacés de 4 px, soit exactement le rayon k. */
    private fun ledGrid(): IntArray {
        val pixels = blank()
        for (y in intArrayOf(18, 22)) for (x in intArrayOf(18, 22)) put(pixels, x, y, GREEN)
        return pixels
    }

    /** Deux rectangles : 12 px (3× plus de flux) contre 4 px, tous deux verts. */
    private fun twoBlobs(): IntArray {
        val pixels = blank()
        for (y in 14..15) for (x in 6..11) put(pixels, x, y, GREEN)
        for (y in 14..15) for (x in 26..27) put(pixels, x, y, GREEN)
        return pixels
    }

    /** Le plus petit des deux rectangles de [twoBlobs] (4 px). */
    private fun dimBlob(): IntArray {
        val pixels = blank()
        for (y in 14..15) for (x in 26..27) put(pixels, x, y, GREEN)
        return pixels
    }

    @Test
    fun blackCropHasNoFlux() {
        assertEquals(0f, LampLens.flux(blank(), SIDE, SIDE), 0f)
    }

    @Test
    fun greenDiscBeatsTheLitFloor() {
        val flux = LampLens.flux(disc(), SIDE, SIDE)
        assertTrue("disque plein : $flux", flux > LampLens.MIN_FLUX)
        assertEquals(113f, flux, 0.01f)
    }

    @Test
    fun closingStitchesLedDotsIntoOneLens() {
        val single = LampLens.flux(ledGridSingle(), SIDE, SIDE)
        val grid = LampLens.flux(ledGrid(), SIDE, SIDE)
        assertEquals("un point seul", 1f, single, 0.01f)
        assertTrue("grille recousue $grid ≤ point seul $single", grid > 3.5f * single)
    }

    @Test
    fun brightestBlobWins() {
        val both = LampLens.flux(twoBlobs(), SIDE, SIDE)
        val dim = LampLens.flux(dimBlob(), SIDE, SIDE)
        assertEquals("les deux taches", 12f, both, 0.01f)
        assertEquals("la petite seule", 4f, dim, 0.01f)
        assertEquals("c'est la sienne qui est renvoyée", 3f * dim, both, 0.01f)
    }

    @Test
    fun grayCropHasNoFlux() {
        assertEquals(0f, LampLens.flux(IntArray(SIDE * SIDE) { GRAY }, SIDE, SIDE), 0f)
    }

    @Test
    fun fluxIsDeterministicAndLeavesInputUntouched() {
        val pixels = twoBlobs()
        val before = pixels.copyOf()
        val first = LampLens.flux(pixels, SIDE, SIDE)
        val second = LampLens.flux(pixels, SIDE, SIDE)
        assertEquals(first, second, 0f)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun tinyCropsDoNotCrash() {
        for ((w, h) in listOf(1 to 1, 1 to 5, 5 to 1, 2 to 2)) {
            val flux = LampLens.flux(IntArray(w * h) { GREEN }, w, h)
            assertEquals("${w}x$h : la fermeture mange le bord", 0f, flux, 0f)
        }
        assertEquals("dimensions incohérentes", 0f, LampLens.flux(IntArray(4), 3, 3), 0f)
    }

    @Test
    fun fluxMatchesPythonReference() {
        for ((path, expected) in PYTHON_FLUX) {
            val (dir, name) = path.split('/')
            val (pixels, w, h) = pixelsOf(javaClass.classLoader, dir, name)
            val flux = LampLens.flux(pixels, w, h)
            val tolerance = expected * 0.05f
            assertTrue(
                "$path : flux $flux, référence Python $expected (±5 %)",
                Math.abs(flux - expected) <= tolerance,
            )
        }
    }

    /** Un seul des quatre points de [ledGrid] : le témoin « un point seul ». */
    private fun ledGridSingle(): IntArray {
        val pixels = blank()
        put(pixels, 18, 18, GREEN)
        return pixels
    }

    private companion object {
        /** Rayon k = 4 : `CLOSE_FRACTION × 40`. */
        const val SIDE = 40

        /** Flux mesuré par `a4/lens.py` (fermeture 0,10, 8-connexe) sur 9 crops réels. */
        val PYTHON_FLUX = mapOf(
            "feuxfr/fr_cardisc_close_183.ppm" to 184.0196f,
            "feuxfr/fr_cardisc_367.ppm" to 103.1765f,
            "feuxfr/fr_red_fig_548.ppm" to 83.6980f,
            "feuxfr/fr_green_widepose_355.ppm" to 77.3020f,
            "feuxfr/fr_green_fig_302.ppm" to 67.2078f,
            "feuxfr/fr_green_fig_21.ppm" to 41.8196f,
            "feuxfr2/r1b_g02.ppm" to 63.8667f,
            "feuxfr2/st_c01.ppm" to 33.6549f,
            "feuxfr3/tel_g02.ppm" to 13.4118f,
        )
    }
}
