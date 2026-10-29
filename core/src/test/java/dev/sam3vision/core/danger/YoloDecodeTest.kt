package dev.sam3vision.core.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décodage YOLO11n (JUnit seul) : classe personne gardée, autres classes et
 * scores faibles jetés, doublons fusionnés par NMS, pixels source retrouvés.
 */
class YoloDecodeTest {

    /** Tenseur [84][cols] nul (fond = logit très négatif → score ~0). */
    private fun blankTensor(cols: Int = 8): Array<FloatArray> =
        Array(84) { FloatArray(cols) { -10f } }

    /** Écrit une proposition (boîte 640 + classe + logit) dans une colonne. */
    private fun Array<FloatArray>.proposal(
        col: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        cls: Int,
        logit: Float,
    ) {
        this[0][col] = cx
        this[1][col] = cy
        this[2][col] = w
        this[3][col] = h
        this[4 + cls][col] = logit
    }

    @Test
    fun `une personne forte sort en pixels source`() {
        val rows = blankTensor()
        // Boîte 640² : centre (320,320), 128×256 → source 320×240 (squash : ×0,5 en X, ×0,375 en Y).
        rows.proposal(0, 320f, 320f, 128f, 256f, YoloDecode.PERSON_CLASS, 5f)
        val persons = YoloDecode.persons(rows, 320, 240)
        assertEquals(1, persons.size)
        val box = persons[0].box
        assertEquals(listOf(128f, 72f, 192f, 168f), box)
        assertEquals("personne", persons[0].label)
        assertTrue(persons[0].score > 0.99f)
    }

    @Test
    fun `les autres classes et les scores faibles sont jetes`() {
        val rows = blankTensor()
        rows.proposal(0, 320f, 320f, 100f, 100f, 2, 5f) // voiture, fort
        rows.proposal(1, 100f, 100f, 50f, 50f, YoloDecode.PERSON_CLASS, -5f) // personne faible
        assertTrue(YoloDecode.persons(rows, 320, 240).isEmpty())
    }

    @Test
    fun `la nms ne garde que le meilleur des doublons`() {
        val rows = blankTensor()
        rows.proposal(0, 320f, 320f, 128f, 256f, YoloDecode.PERSON_CLASS, 5f)
        rows.proposal(1, 322f, 320f, 130f, 256f, YoloDecode.PERSON_CLASS, 3f)
        rows.proposal(2, 64f, 64f, 60f, 120f, YoloDecode.PERSON_CLASS, 4f)
        val persons = YoloDecode.persons(rows, 320, 240)
        assertEquals(2, persons.size)
        // Le doublon gardé est le plus fort (logit 5).
        assertTrue(persons.any { it.score > 0.99f })
    }

    @Test
    fun `les boites depassant l'image sont bornees`() {
        val rows = blankTensor()
        rows.proposal(0, 630f, 630f, 100f, 100f, YoloDecode.PERSON_CLASS, 5f)
        val box = YoloDecode.persons(rows, 320, 240)[0].box
        assertEquals(listOf(290f, 217.5f, 320f, 240f), box)
    }
}
