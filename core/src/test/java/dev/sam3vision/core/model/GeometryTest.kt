package dev.sam3vision.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    @Test
    fun `unproject replace le centre optique sur l axe`() {
        val center = unproject(u = 320f, v = 240f, depthMeters = 2f, focalPx = 500f, cx = 320f, cy = 240f)
        assertEquals(0f, center.x, 1e-6f)
        assertEquals(0f, center.y, 1e-6f)
        assertEquals(2f, center.z, 1e-6f)
    }

    @Test
    fun `unproject ecarte en proportion de la profondeur`() {
        val near = unproject(420f, 240f, 1f, 500f, 320f, 240f)
        val far = unproject(420f, 240f, 3f, 500f, 320f, 240f)
        assertEquals(0.2f, near.x, 1e-6f)
        assertEquals(0.6f, far.x, 1e-6f)
        assertEquals(0f, near.y, 1e-6f)
    }

    @Test
    fun `box3D encadre la boite 2D sur la plage de profondeur`() {
        val box = box3D(listOf(220f, 140f, 420f, 340f), 1f, 2f, 500f, 320f, 240f)!!
        assertEquals(-0.2f, box.min.x, 1e-5f)
        assertEquals(-0.2f, box.min.y, 1e-5f)
        assertEquals(1f, box.min.z, 0f)
        assertEquals(0.4f, box.max.x, 1e-5f)
        assertEquals(0.4f, box.max.y, 1e-5f)
        assertEquals(2f, box.max.z, 0f)
        assertEquals(0.1f, box.center.x, 1e-5f)
        assertEquals(1f, box.extent.z, 0f)
    }

    @Test
    fun `box3D rend null sur entree inexploitable`() {
        assertNull(box3D(listOf(0f, 0f), 1f, 2f, 500f, 0f, 0f))
        assertNull(box3D(listOf(0f, 0f, 10f, 10f), 1f, 2f, 0f, 5f, 5f))
        assertNull(box3D(listOf(0f, 0f, 10f, 10f), 0f, 2f, 500f, 5f, 5f))
        assertNull(box3D(listOf(0f, 0f, 10f, 10f), 3f, 2f, 500f, 5f, 5f))
    }
}
