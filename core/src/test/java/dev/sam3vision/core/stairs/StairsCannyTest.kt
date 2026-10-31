package dev.sam3vision.core.stairs

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairsCannyTest {

    private val config = StairsConfig()

    private fun grayOf(width: Int, height: Int, fill: Int = 0, draw: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }): GrayMask {
        val pixels = ByteArray(width * height) { fill.toByte() }
        draw(pixels, width, height)
        return GrayMask(width, height, pixels)
    }

    private fun hline(pixels: ByteArray, width: Int, y: Int, level: Int = 255) {
        for (x in 0 until width) pixels[y * width + x] = level.toByte()
    }

    private fun countFlagged(edges: ByteArray): Int = edges.count { it != 0.toByte() }

    @Test
    fun `marche horizontale pleine largeur = contour retenu`() {
        val mask = grayOf(64, 60, fill = 20) { pixels, width, _ -> hline(pixels, width, 40) }
        val edges = StairsCanny.horizontalEdges(mask, 24, 60, config)

        assertTrue(countFlagged(edges) >= 64)
    }

    @Test
    fun `bande epaisse = rangs actifs d un seul tenant, pas dedoubles`() {
        // Contrat du compteur : un nez n'a qu'un côté montant — les deux
        // côtés d'une bande de 6 px ne doivent plus activer deux rangs
        // séparés (un plateau NMS peut garder 2 rangs adjacents : le
        // groupement les fusionne, le compte reste un).
        val width = 64
        val mask = grayOf(width, 60, fill = 20) { pixels, w, _ ->
            for (y in 38 until 44) hline(pixels, w, y)
        }
        val edges = StairsCanny.horizontalEdges(mask, 24, 60, config)
        val needed = (width * config.minRowCoverage).toInt()
        val active = (24 until 60).filter { y ->
            (0 until width).count { x -> edges[y * width + x] != 0.toByte() } >= needed
        }

        assertTrue(active.isNotEmpty())
        assertEquals(active.size, active.last() - active.first() + 1)
    }

    @Test
    fun `marche verticale = aucun contour retenu`() {
        val mask = grayOf(64, 60, fill = 20) { pixels, width, height ->
            for (y in 0 until height) pixels[y * width + 32] = 255.toByte()
        }
        val edges = StairsCanny.horizontalEdges(mask, 24, 60, config)

        assertEquals(0, countFlagged(edges))
    }

    @Test
    fun `diagonale a 45 degres = aucune ligne active`() {
        // Les micro-segments horizontaux de la rastérisation retiennent quelques
        // pixels, mais jamais 30 % d'une ligne : c'est la couverture qui protège.
        val width = 64
        val mask = grayOf(width, 60, fill = 20) { pixels, _, height ->
            for (y in 0 until height) {
                val x = y % width
                pixels[y * width + x] = 255.toByte()
            }
        }
        val edges = StairsCanny.horizontalEdges(mask, 24, 60, config)
        val needed = (width * config.minRowCoverage).toInt()

        for (y in 24 until 60) {
            var row = 0
            for (x in 0 until width) {
                if (edges[y * width + x] != 0.toByte()) row++
            }
            assertTrue("ligne $y : $row pixels", row < needed)
        }
    }

    @Test
    fun `aplat = aucun contour`() {
        val mask = grayOf(64, 60, fill = 128)
        val edges = StairsCanny.horizontalEdges(mask, 0, 60, config)

        assertEquals(0, countFlagged(edges))
    }

    @Test
    fun `bruit capteur faible amplitude = aucun contour`() {
        val random = java.util.Random(7)
        val mask = grayOf(64, 60) { pixels, _, _ ->
            for (i in pixels.indices) pixels[i] = (128 + random.nextInt(21) - 10).toByte()
        }
        val edges = StairsCanny.horizontalEdges(mask, 0, 60, config)

        assertEquals(0, countFlagged(edges))
    }

    @Test
    fun `contour hors ROI = ignore`() {
        val mask = grayOf(64, 60, fill = 20) { pixels, width, _ -> hline(pixels, width, 5) }
        val edges = StairsCanny.horizontalEdges(mask, 24, 60, config)

        assertEquals(0, countFlagged(edges))
    }

    @Test
    fun `image incoherente ou vide = carte vide sans exception`() {
        assertEquals(0, countFlagged(StairsCanny.horizontalEdges(GrayMask(4, 4, ByteArray(3)), 0, 4, config)))
        assertEquals(0, countFlagged(StairsCanny.horizontalEdges(GrayMask(0, 0, ByteArray(0)), 0, 0, config)))
        assertEquals(0, countFlagged(StairsCanny.horizontalEdges(GrayMask(1, 60, ByteArray(60)), 0, 60, config)))
    }

    @Test
    fun `ROI degeneree = carte vide`() {
        val mask = grayOf(64, 60, fill = 20) { pixels, width, _ -> hline(pixels, width, 40) }
        assertEquals(0, countFlagged(StairsCanny.horizontalEdges(mask, 59, 60, config)))
        assertFalse(StairsCanny.horizontalEdges(mask, 40, 24, config).any { it != 0.toByte() })
    }
}
