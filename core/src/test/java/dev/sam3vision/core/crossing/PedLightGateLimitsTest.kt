package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte anti-confusion : cas limites complémentaires à [PedLightGateTest].
 *
 * Chaque test vise une frontière exacte du code lu dans [PedLightGate]
 * (fractions rouge/vert, élancement 1,5, plancher R1b 20 %, bande 20-35 %) :
 * un pixel de plus ou de moins fait basculer le verdict. Aucun seuil
 * n'est modifié ici — ce fichier les verrouille.
 *
 * Note : [PedLightGate.verdict] passe d'abord par [TrainedLightColor],
 * donc les formes synthétiques sont testées via [PedLightGate.acceptGreen]
 * (géométrie pure) ; [verdict] n'est testé que sur des cas dont la
 * réponse de l'arbre est prévisible (plein rouge, noir, incohérent).
 */
class PedLightGateLimitsTest {

    @Test
    fun `dimensions incoherentes - refusees`() {
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { GREEN }, 0, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { GREEN }, -1, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { GREEN }, 10, 0))
        assertFalse(PedLightGate.acceptGreen(IntArray(10), 10, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(0), 0, 0))
    }

    @Test
    fun `verdict incoherent - rouge, binaire`() {
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(0), 0, 0))
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(10), 10, 10))
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100), -1, 10))
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100) { BLACK }, 0, 10))
    }

    @Test
    fun `rouge melange - 7px tolere, 8px refuse sur 400`() {
        assertTrue(PedLightGate.acceptGreen(barBottomPlusReds(7), 20, 20))
        assertFalse(PedLightGate.acceptGreen(barBottomPlusReds(8), 20, 20))
    }

    @Test
    fun `fraction verte - 39px refuse, 40px accepte sur 100x100`() {
        assertFalse(PedLightGate.acceptGreen(verticalLine(39), 100, 100))
        assertTrue(PedLightGate.acceptGreen(verticalLine(40), 100, 100))
    }

    @Test
    fun `elancement exact - 4x6 accepte, 4x5 refuse`() {
        assertTrue(PedLightGate.acceptGreen(barSized(4, 6), 20, 20))
        assertFalse(PedLightGate.acceptGreen(barSized(4, 5), 20, 20))
    }

    @Test
    fun `bande R1b - 30 pourcent accepte par la forme, 10 pourcent refuse dur`() {
        assertTrue("barre a 30 % en bas : nuage allonge, non entasse", PedLightGate.acceptGreen(barAt(3), 20, 20))
        assertFalse("barre a 10 % en bas : sous le plancher 20 %", PedLightGate.acceptGreen(barAt(1), 20, 20))
    }

    @Test
    fun `verdict - plein rouge reste rouge, jamais vert`() {
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100) { RED }, 10, 10))
        assertNotEquals(
            CrossingState.Green,
            PedLightGate.verdict(disc(10, 10, 5, RED), 20, 20),
        )
        assertEquals(CrossingState.Red, PedLightGate.verdict(IntArray(100) { BLACK }, 10, 10))
    }

    /** Barre verte 4x10 dans la moitié basse, plus [reds] pixels rouges en haut. */
    private fun barBottomPlusReds(reds: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 10 until 20) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        for (i in 0 until reds) pixels[i] = RED
        return pixels
    }

    /** Ligne verticale verte d'un pixel de large, calée en bas d'un cadre 100x100. */
    private fun verticalLine(heightPx: Int, width: Int = 100, height: Int = 100): IntArray {
        val pixels = IntArray(width * height) { BLACK }
        for (y in height - heightPx until height) pixels[y * width + 50] = GREEN
        return pixels
    }

    /** Barre verte [w]x[h] calée en bas d'un cadre 20x20. */
    private fun barSized(w: Int, h: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 20 - h until 20) {
            for (x in 10 - w / 2 until 10 - w / 2 + w) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Barre verte 4x10 placée en [y0] dans un cadre 20x20. */
    private fun barAt(y0: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in y0 until y0 + 10) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Disque de couleur sur fond noir. */
    private fun disc(cx: Int, cy: Int, radius: Int, color: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 0 until 20) {
            for (x in 0 until 20) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) pixels[y * 20 + x] = color
            }
        }
        return pixels
    }

    companion object {
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLACK = 0xFF000000.toInt()
    }
}
