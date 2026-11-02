package dev.sam3vision.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Points AE de la lampe : centre de la boîte, bornage au bord du cadre, refus des
 * boîtes inexploitables (une région AE posée au hasard écraserait la scène) et seuil de
 * ré-armement de 0,02.
 */
class FireMeteringTest {

    @Test
    fun `boite au centre - lampe puis plein cadre`() {
        val points = FireMetering.points(listOf(0.4f, 0.3f, 0.6f, 0.5f))!!
        assertEquals(2, points.size)
        assertEquals(0.5f, points[0].x, 0.0001f)
        assertEquals(0.4f, points[0].y, 0.0001f)
        assertEquals(FireMetering.LAMP_SIZE, points[0].size, 0f)
        assertEquals(0.5f, points[1].x, 0.0001f)
        assertEquals(FireMetering.SCENE_SIZE, points[1].size, 0f)
    }

    @Test
    fun `boite a moitie hors champ - centre borne, jamais invente`() {
        val points = FireMetering.points(listOf(0.9f, 0.1f, 1.3f, 0.3f))!!
        assertEquals(1f, points[0].x, 0.0001f)
        assertEquals(0.2f, points[0].y, 0.0001f)
    }

    @Test
    fun `boite inexploitable - aucun point`() {
        assertNull(FireMetering.points(null))
        assertNull(FireMetering.points(listOf(0.1f, 0.2f, 0.3f)))
        assertNull(FireMetering.points(listOf(0.4f, 0.4f, 0.4f, 0.6f)))
        assertNull(FireMetering.points(listOf(0.1f, 0.1f, Float.NaN, 0.3f)))
    }

    @Test
    fun `deplacement - seuil de re-armement a deux centiemes`() {
        val before = FireMetering.points(listOf(0.4f, 0.4f, 0.5f, 0.5f))!![0]
        val after = FireMetering.points(listOf(0.49f, 0.4f, 0.59f, 0.5f))!![0]
        assertTrue(FireMetering.moved(before, after) >= 0.02f)
        assertEquals(1f, FireMetering.moved(null, after), 0f)
    }
}
