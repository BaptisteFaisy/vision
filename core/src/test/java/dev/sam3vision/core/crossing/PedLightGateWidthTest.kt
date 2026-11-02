package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parité **Kotlin ↔ Python** de la mesure du plancher de largeur
 * ([PedLightGate.GREEN_MIN_WIDTH_FRACTION]) : les 20 crops réels ci-dessous
 * doivent rendre la même largeur de plus grande composante verte que
 * `tools/light_features.py`
 *
 *     widest_component(_green_mask(px), w, h)
 *
 * (valeurs produites par le miroir le 02/11/2026, `--` voir
 * `.scratch/feux-impl/fixa/RAPPORT.md` §Parité). Le choix des 20 : les 8
 * crops dont la composante diffère de l'étendue X de tous les pixels verts
 * (le défaut corrigé, audit W12 §1), les 2 rouges qui portent des pixels
 * verts, et 10 verts de plus. Les 13 autres ancres restent couvertes par la
 * parité de **verdict** (`PedLightGateTest`, `test_train_light.py`), qui les
 * assert toutes les deux côté à côté.
 *
 * Une divergence d'un seul pixel ici signifie que les deux modèles ne lisent
 * plus les mêmes pixels (projection signée) — donc que les chiffres du
 * miroir ne décrivent plus le code livré.
 */
class PedLightGateWidthTest {

    private val attendu = mapOf(
        "fr_cardisc_367.ppm" to 19,
        "fr_cardisc_close_183.ppm" to 19,
        "fr_green_fig_21.ppm" to 12,
        "fr_green_fig_243.ppm" to 2,
        "fr_green_fig_302.ppm" to 6,
        "fr_green_widepose_355.ppm" to 13,
        "fr_red_dim_138.ppm" to 4,
        "fr_red_noise_732.ppm" to 4,
        "r1b_g01.ppm" to 9,
        "r1b_g02.ppm" to 11,
        "r1b_g03.ppm" to 9,
        "r1b_g04.ppm" to 5,
        "r1b_g05.ppm" to 5,
        "st_c01.ppm" to 9,
        "st_c02.ppm" to 12,
        "tel_g02.ppm" to 2,
        "tel_g05.ppm" to 5,
        "tel_g07.ppm" to 9,
        "tel_g08.ppm" to 8,
        "tel_g09.ppm" to 14,
    )

    /** Dossier `core/src/test/resources` de chaque crop attendu. */
    private val dossiers = mapOf(
        "fr_" to "feuxfr", "r1b_" to "feuxfr2", "st_" to "feuxfr2", "tel_" to "feuxfr3",
    )

    @Test
    fun `largeur de la plus grande composante - memes nombres que le miroir Python`() {
        assertEquals(20, attendu.size)
        for ((nom, largeur) in attendu) {
            val dossier = dossiers.entries.first { nom.startsWith(it.key) }.value
            val (pixels, width, height) = pixelsOf(javaClass.classLoader, dossier, nom)
            assertEquals("$dossier/$nom", largeur, PedLightGate.greenWidthPx(pixels, width, height))
        }
    }

    @Test
    fun `largeur - deux bandes opposees ne s additionnent pas`() {
        // Étendue X = 86 px, deux composantes de 1 px : le plancher doit lire 1.
        val pixels = IntArray(96 * 96) { BLACK }
        for (y in 56 until 96) pixels[y * 96 + 48] = GREEN
        for (y in 30 until 70) pixels[y * 96 + 90] = GREEN
        assertEquals(1, PedLightGate.greenWidthPx(pixels, 96, 96))
    }

    @Test
    fun `largeur - diagonale 8-connexe - une seule composante`() {
        val pixels = IntArray(10 * 10) { BLACK }
        for (k in 2 until 8) pixels[k * 10 + k] = GREEN
        assertEquals(6, PedLightGate.greenWidthPx(pixels, 10, 10))
    }

    @Test
    fun `largeur - deux taches separees - la plus large decide`() {
        val pixels = IntArray(10 * 10) { BLACK }
        for (y in 0 until 3) for (x in 0 until 2) pixels[y * 10 + x] = GREEN
        for (y in 6 until 9) for (x in 5 until 9) pixels[y * 10 + x] = GREEN
        assertEquals(4, PedLightGate.greenWidthPx(pixels, 10, 10))
    }

    @Test
    fun `largeur - crop 1x1 et dimensions fausses - 0, pas de crash`() {
        assertEquals(1, PedLightGate.greenWidthPx(IntArray(1) { GREEN }, 1, 1))
        assertEquals(0, PedLightGate.greenWidthPx(IntArray(1), 0, 0))
        assertEquals(0, PedLightGate.greenWidthPx(IntArray(10), 10, 10))
    }
}
