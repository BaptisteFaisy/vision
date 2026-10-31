package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `le noir doute, garde vert`() {
        assertEquals(
            CrossingState.Unknown,
            TrainedLightColor.classify(IntArray(100) { BLACK }, 10, 10),
        )
    }

    @Test
    fun `le gris doute, feuille fragile`() {
        assertEquals(
            CrossingState.Unknown,
            TrainedLightColor.classify(IntArray(100) { GRAY }, 10, 10),
        )
    }

    @Test
    fun `un tableau incoherent doute`() {
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(0), 0, 0))
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(10), 10, 10))
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(100), -1, 10))
    }

    @Test
    fun `un bonhomme vert sombre est vert`() {
        assertEquals(CrossingState.Green, TrainedLightColor.classify(bar(), 20, 20))
    }

    @Test
    fun `le vert se tranche des 0,50 de purete`() {
        assertEquals(0.50f, TrainedLightColor.GREEN_MIN_PURITY, 0f)
    }

    @Test
    fun `l'arbre dit non valide France, corpus allemand`() {
        assertFalse(TrainedLightColor.VALIDATED_FR)
    }

    /** Barre verte 4x10 sur fond noir : petit bonhomme sombre (feuille 6 de l'arbre). */
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
