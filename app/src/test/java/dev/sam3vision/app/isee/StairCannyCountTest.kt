package dev.sam3vision.app.isee

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.stairs.StairRoi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Compte Canny partagé de la caméra : une frame qui voit des marches compte
 * (> 0), une frame unie, incomplète ou périmée ne compte pas (0 = non compté,
 * jamais « zéro marche »).
 */
class StairCannyCountTest {

    @Before
    fun setUp() = StairCannyCount.clear()

    @After
    fun tearDown() = StairCannyCount.clear()

    /** Plan 256×103 : fond sombre, une ligne blanche par marche, dans la ROI. */
    private fun stairsLuma(vararg rows: Int): ByteArray {
        val luma = ByteArray(LumaPlane.SIZE) { 20 }
        for (y in rows) {
            for (x in 0 until LumaPlane.WIDTH) luma[y * LumaPlane.WIDTH + x] = 255.toByte()
        }
        return luma
    }

    /** Pixels ARGB 256×103 : fond sombre, une ligne blanche par marche, dans la ROI. */
    private fun stairsPixels(vararg rows: Int): IntArray {
        val pixels = IntArray(LumaPlane.SIZE) { 0xFF141414.toInt() }
        for (y in rows) {
            for (x in 0 until LumaPlane.WIDTH) pixels[y * LumaPlane.WIDTH + x] = 0xFFFFFFFF.toInt()
        }
        return pixels
    }

    @Test
    fun `marches vues = compte positif`() {
        StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))

        assertTrue(StairCannyCount.lineCount() > 0)
    }

    @Test
    fun `image unie = non comptee`() {
        StairCannyCount.offerLuma(ByteArray(LumaPlane.SIZE) { 20 })

        assertEquals(0, StairCannyCount.lineCount())
    }

    @Test
    fun `compte perime = non compte`() {
        StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
        assertTrue(StairCannyCount.lineCount() > 0)

        val staleMs = System.currentTimeMillis() + StairCannyCount.FRESH_MS + 1

        assertEquals(0, StairCannyCount.lineCount(nowMs = staleMs))
    }

    @Test
    fun `clear oublie le compte`() {
        StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))

        StairCannyCount.clear()

        assertEquals(0, StairCannyCount.lineCount())
    }

    @Test
    fun `age du compte connu puis oublie`() {
        assertEquals(null, StairCannyCount.countAgeMs())

        StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
        val age = StairCannyCount.countAgeMs()
        assertTrue((age ?: Long.MAX_VALUE) <= StairCannyCount.FRESH_MS)

        StairCannyCount.clear()
        assertEquals(null, StairCannyCount.countAgeMs())
    }

    @Test
    fun `pixels lunettes = compte positif`() {
        StairCannyCount.offerPixels(
            stairsPixels(50, 58, 66, 74, 82, 90), LumaPlane.WIDTH, LumaPlane.HEIGHT)

        assertTrue(StairCannyCount.lineCount() > 0)
    }

    @Test
    fun `pixels incoherents ignores sans ecraser`() {
        StairCannyCount.offerPixels(
            stairsPixels(50, 58, 66, 74, 82, 90), LumaPlane.WIDTH, LumaPlane.HEIGHT)
        assertTrue(StairCannyCount.lineCount() > 0)

        StairCannyCount.offerPixels(IntArray(10), LumaPlane.WIDTH, LumaPlane.HEIGHT)
        StairCannyCount.offerPixels(IntArray(0), 0, 0)

        assertTrue(StairCannyCount.lineCount() > 0)
    }

    @Test
    fun `echantillonneur pur au plus proche voisin`() {
        // Source 2×2 : le quart supérieur gauche survit seul en 256×103.
        val pixels = IntArray(4) { 0xFF000000.toInt() }
        pixels[0] = 0xFFFFFFFF.toInt()
        val luma = lumaPlaneFromPixels(pixels, 2, 2)!!

        assertEquals(LumaPlane.SIZE, luma.size)
        assertEquals(255.toByte(), luma[0])
        // Ligne 52 (moitié basse) : échantillonnée sur la rangée 1, noire.
        assertEquals(0.toByte(), luma[52 * LumaPlane.WIDTH])
    }

    @Test
    fun `plan incomplet ignore`() {
        StairCannyCount.offerLuma(ByteArray(10) { 20 })

        assertEquals(0, StairCannyCount.lineCount())
    }

    @Test
    fun `roi sam : marches hors boite ignorees`() {
        // ROI = moitié basse : les 2 marches du haut (44,45) ne comptent plus.
        StairCannyCount.setRoi(StairRoi(0f, 0.5f, 1f, 1f))
        StairCannyCount.offerLuma(stairsLuma(44, 45, 60, 68, 76, 84))

        assertEquals(4, StairCannyCount.lineCount())
    }

    @Test
    fun `roi effacee : balayage entier`() {
        StairCannyCount.setRoi(StairRoi(0f, 0.5f, 1f, 1f))
        StairCannyCount.offerLuma(stairsLuma(44, 45, 60, 68, 76, 84))
        assertEquals(4, StairCannyCount.lineCount())

        StairCannyCount.setRoi(null)
        StairCannyCount.offerLuma(stairsLuma(44, 45, 60, 68, 76, 84))

        assertEquals(5, StairCannyCount.lineCount())
    }

    @Test
    fun `clear oublie la roi`() {
        StairCannyCount.setRoi(StairRoi(0f, 0.5f, 1f, 1f))

        StairCannyCount.clear()
        StairCannyCount.offerLuma(stairsLuma(44, 45, 60, 68, 76, 84))

        assertEquals(5, StairCannyCount.lineCount())
    }
}
