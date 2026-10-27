package dev.sam3vision.core.depth

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthMapTest {

    @Test
    fun `at rend la valeur et 0 hors image`() {
        val map = DepthMap(2, 2, floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(1f, map.at(0, 0), 0f)
        assertEquals(4f, map.at(1, 1), 0f)
        assertEquals(0f, map.at(9, 9), 0f)
        assertEquals(0f, map.at(-1, 0), 0f)
        assertEquals(4, map.size)
    }

    @Test
    fun `normalized01 ramene min-max dans 0-1`() {
        val map = DepthMap(3, 1, floatArrayOf(10f, 20f, 30f)).normalized01()
        assertEquals(0f, map.at(0, 0), 1e-6f)
        assertEquals(0.5f, map.at(1, 0), 1e-6f)
        assertEquals(1f, map.at(2, 0), 1e-6f)
    }

    @Test
    fun `normalized01 d une carte plate rend des zeros`() {
        val map = DepthMap(2, 2, floatArrayOf(7f, 7f, 7f, 7f)).normalized01()
        assertEquals(0f, map.at(1, 1), 0f)
    }

    @Test
    fun `resampledTo conserve une carte uniforme et les coins`() {
        val flat = DepthMap(2, 2, floatArrayOf(5f, 5f, 5f, 5f)).resampledTo(5, 5)
        assertEquals(25, flat.values.size)
        flat.values.forEach { assertEquals(5f, it, 1e-5f) }

        val corners = DepthMap(2, 2, floatArrayOf(0f, 10f, 20f, 30f)).resampledTo(3, 3)
        assertEquals(0f, corners.at(0, 0), 1e-5f)
        assertEquals(10f, corners.at(2, 0), 1e-5f)
        assertEquals(20f, corners.at(0, 2), 1e-5f)
        assertEquals(30f, corners.at(2, 2), 1e-5f)
        assertEquals(15f, corners.at(1, 1), 1e-5f)
    }

    @Test
    fun `resampledTo vers la meme taille rend la carte`() {
        val map = DepthMap(2, 2, floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(map.values.toList(), map.resampledTo(2, 2).values.toList())
    }
}
