package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Fuzz anti-inversion de la porte : un rouge ne rend JAMAIS vert —
 * photométrie, géométrie, bruit et balance des blancs, sur crops réels
 * (`feuxfr/`, `feuxfr2/`) et synthétiques. Binaire (ordre user du 31/10) :
 * tout non-vert attend rouge.
 *
 * Tous déterministes (graines fixées, pas d'ImageIO) et pré-validés sur un
 * modèle Python fidèle du pipeline (arbre + porte) : 412/413 sans vert, le
 * seul écart expliqué (ligne 1 px : doute tri-state du modèle, rouge binaire
 * en Kotlin — la porte la refuse par sa hauteur minimale).
 *
 * Pixels et transforms partagés : voir CrossingTestPixels.kt.
 */
class PedLightGateFuzzTest {

    @Test
    fun `fuzz - rouges et photometrie jamais verts`() {
        // La couleur peut s'écraser ou saturer, jamais s'inverser.
        for ((pixels, w, h) in redSources()) {
            for ((name, op) in photoOps()) {
                assertNotEquals(
                    "$name",
                    CrossingState.Green,
                    PedLightGate.verdict(op(pixels), w, h),
                )
            }
        }
    }

    @Test
    fun `fuzz - rouges et geometrie jamais verts`() {
        for ((pixels, w, h) in redSources()) {
            val geos = listOf(
                "blur" to boxBlur(pixels, w, h),
                "poster4" to posterize(pixels, 4),
                "block8" to blockAverage(pixels, w, h, 8),
                "shift" to shift(pixels, w, h, 2, 1),
                "occl7" to occlude(pixels, w, h, 7),
            )
            for ((name, out) in geos) {
                assertNotEquals("$name", CrossingState.Green, PedLightGate.verdict(out, w, h))
            }
            for ((zoomed, zw, zh) in scaled(pixels, w, h)) {
                assertNotEquals("scale", CrossingState.Green, PedLightGate.verdict(zoomed, zw, zh))
            }
        }
    }

    @Test
    fun `fuzz - rouges et bruit jamais verts, graines 0 a 19`() {
        // Poivre-et-sel, pixels verts parasites, gigue uniforme : le rouge
        // bruité reste rouge (binaire), jamais vert.
        val sources = listOf(
            pixelsOf(javaClass.classLoader, "feuxfr", "fr_red_fig_548.ppm"),
            pixelsOf(javaClass.classLoader, "feuxfr2", "st_r01.ppm"),
        )
        for ((pixels, w, h) in sources) {
            for (seed in 0 until 20) {
                assertNotEquals("sp$seed", CrossingState.Green, PedLightGate.verdict(saltPepper(pixels, seed, 0.02f), w, h))
                assertNotEquals("gn$seed", CrossingState.Green, PedLightGate.verdict(greenNoise(pixels, seed, 0.05f), w, h))
                assertNotEquals("uj$seed", CrossingState.Green, PedLightGate.verdict(uniformJitter(pixels, seed, 24), w, h))
            }
        }
    }

    @Test
    fun `fuzz - rouges et balance des blancs jamais verts`() {
        val sources = listOf(
            pixelsOf(javaClass.classLoader, "feuxfr", "fr_red_fig_548.ppm"),
            pixelsOf(javaClass.classLoader, "feuxfr2", "st_r01.ppm"),
        )
        for ((pixels, w, h) in sources) {
            for ((rf, gf, bf) in listOf(Triple(1.3f, 0.9f, 0.9f), Triple(0.9f, 1.2f, 0.9f), Triple(1.1f, 1.1f, 0.8f))) {
                assertNotEquals("$rf,$gf,$bf", CrossingState.Green, PedLightGate.verdict(whiteBalance(pixels, rf, gf, bf), w, h))
            }
        }
    }

    @Test
    fun `fuzz - melange rouge dominant et ligne verte attendent rouge`() {
        // Mécanisme certain : le rouge franc (75 %) fait refuser la porte, la
        // ligne d'1 px échoue la hauteur minimale — verdict rouge, pas vert.
        assertEquals(CrossingState.Red, PedLightGate.verdict(redDominant(20, 20), 20, 20))
        assertEquals(CrossingState.Red, PedLightGate.verdict(hline(20, 20), 20, 20))
    }

    /** Sources rouges du fuzz : réelles (feuxfr/, feuxfr2/) + synthétiques. */
    private fun redSources(): List<Triple<IntArray, Int, Int>> = listOf(
        pixelsOf(javaClass.classLoader, "feuxfr", "fr_red_fig_548.ppm"),
        pixelsOf(javaClass.classLoader, "feuxfr", "fr_red_dim_138.ppm"),
        pixelsOf(javaClass.classLoader, "feuxfr", "fr_red_noise_732.ppm"),
        pixelsOf(javaClass.classLoader, "feuxfr2", "st_r01.ppm"),
        pixelsOf(javaClass.classLoader, "feuxfr2", "st_r02.ppm"),
        Triple(disc(10, 10, 5, RED), 20, 20),
        Triple(IntArray(100) { RED }, 10, 10),
    )

    /** Photométries du fuzz : gains, contrastes, gammas (bornés, déterministes). */
    private fun photoOps(): List<Pair<String, (IntArray) -> IntArray>> = listOf(
        "bright1.3" to { gain(it, 1.3f, 1.3f, 1.3f) },
        "bright0.5" to { gain(it, 0.5f, 0.5f, 0.5f) },
        "bright0.25" to { gain(it, 0.25f, 0.25f, 0.25f) },
        "contrast1.5" to { contrast(it, 1.5f) },
        "contrast0.5" to { contrast(it, 0.5f) },
        "gamma2.2" to { gamma(it, 2.2) },
        "gamma0.45" to { gamma(it, 0.45) },
    )

    /** Mises à l'échelle x2 et /2 (plus proche voisin). */
    private fun scaled(pixels: IntArray, w: Int, h: Int): List<Triple<IntArray, Int, Int>> =
        listOf(scaleNN(pixels, w, h, 2, 1), scaleNN(pixels, w, h, 1, 2))

    /** Rouge dominant (75 %) + quadrant vert : le rouge franc interdit le vert. */
    private fun redDominant(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h) { RED }
        for (y in h / 2 until h) for (x in w / 2 until w) pixels[y * w + x] = GREEN
        return pixels
    }

    /** Ligne verte d'1 px en bas : échoue la hauteur minimale (3 px). */
    private fun hline(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h) { BLACK }
        for (x in 0 until w) pixels[(h - 1) * w + x] = GREEN
        return pixels
    }
}
