package dev.sam3vision.app.isee

import dev.sam3vision.core.local.LumaPlane
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
    fun `plan incomplet ignore`() {
        StairCannyCount.offerLuma(ByteArray(10) { 20 })

        assertEquals(0, StairCannyCount.lineCount())
    }
}
