package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Portage de l'arbre : les attentes viennent du harnais Python sur les mêmes
 * pixels synthétiques (arbre + seuil de pureté 0,80). Elles prouvent que le
 * Kotlin suit l'arbre entraîné — pas que l'arbre a raison sur le terrain.
 */
class TrainedLightColorTest {

    @Test
    fun `un vert cyan franc est vert`() {
        assertEquals(
            CrossingState.Green,
            TrainedLightColor.classify(IntArray(100) { CYAN_GREEN }, 10, 10),
        )
    }

    @Test
    fun `le noir attend rouge, binaire`() {
        assertEquals(
            CrossingState.Red,
            TrainedLightColor.classify(IntArray(100) { BLACK }, 10, 10),
        )
    }

    @Test
    fun `le gris attend rouge, binaire`() {
        assertEquals(
            CrossingState.Red,
            TrainedLightColor.classify(IntArray(100) { GRAY }, 10, 10),
        )
    }

    @Test
    fun `un tableau incoherent attend rouge, binaire`() {
        assertEquals(CrossingState.Red, TrainedLightColor.classify(IntArray(0), 0, 0))
        assertEquals(CrossingState.Red, TrainedLightColor.classify(IntArray(10), 10, 10))
        assertEquals(CrossingState.Red, TrainedLightColor.classify(IntArray(100), -1, 10))
    }

    @Test
    fun `un bonhomme vert sombre est vert`() {
        assertEquals(CrossingState.Green, TrainedLightColor.classify(bar(), 20, 20))
    }

    @Test
    fun `la frontiere verte est 0,002`() {
        // 25x40 = 1000 px : 3 px verts = 0,003 > 0,002 (vert ; rouge avant le 01/11).
        val three = IntArray(1000) { BLACK }
        three[0] = GREEN
        three[1] = GREEN
        three[2] = GREEN
        assertEquals(CrossingState.Green, TrainedLightColor.classify(three, 25, 40))
        // 2 px verts = 0,002 : sur le plancher, attend rouge.
        val two = IntArray(1000) { BLACK }
        two[0] = GREEN
        two[1] = GREEN
        assertEquals(CrossingState.Red, TrainedLightColor.classify(two, 25, 40))
    }

    @Test
    fun `le vert se tranche des 0,50 de purete`() {
        assertEquals(0.50f, TrainedLightColor.GREEN_MIN_PURITY, 0f)
    }

    /** Barre verte 4x10 sur fond noir : petit bonhomme sombre (fraction verte 0,10). */
    private fun bar(): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 4 until 14) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    companion object {
        private const val CYAN_GREEN = 0xFF00FFAA.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
    }
}
