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
    fun `le noir est un doute`() {
        assertEquals(
            CrossingState.Unknown,
            TrainedLightColor.classify(IntArray(100) { BLACK }, 10, 10),
        )
    }

    @Test
    fun `le gris est un doute`() {
        assertEquals(
            CrossingState.Unknown,
            TrainedLightColor.classify(IntArray(100) { GRAY }, 10, 10),
        )
    }

    @Test
    fun `un tableau incoherent est un doute`() {
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(0), 0, 0))
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(10), 10, 10))
        assertEquals(CrossingState.Unknown, TrainedLightColor.classify(IntArray(100), -1, 10))
    }

    companion object {
        private const val CYAN_GREEN = 0xFF00FFAA.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
    }
}
