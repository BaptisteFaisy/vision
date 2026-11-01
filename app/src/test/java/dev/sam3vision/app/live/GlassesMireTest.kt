package dev.sam3vision.app.live

import dev.sam3vision.app.mireLine
import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec mire lunettes : parsing du bloc solveur et validation en LensSpec. */
class GlassesMireTest {

    private fun fields() = MireFields(60.61f, 92.19f, 0.5f, 0.5f, true, -0.1f, 0.02f, 1761980000000L)

    private fun block() = """
        mire v1
        hFovDeg=60.61
        vFovDeg=92.19
        cx=0.5
        cy=0.5
        hasDistortion=true
        k1=-0.1
        k2=0.02
        atMs=1761980000000
    """.trimIndent()

    @Test
    fun `champs sains donnent un lens portrait utilisable`() {
        val spec = mireLensOf(fields())!!
        assertEquals(60.61f, spec.horizontalFovDeg, 0f)
        assertEquals(92.19f, spec.verticalFovDeg, 0f)
        assertFalse(spec.sensorLandscape)
        assertNotNull(spec.distortion)
        assertTrue(spec.usable)
    }

    @Test
    fun `sans distorsion le lens n'en porte pas`() {
        val spec = mireLensOf(fields().copy(hasDistortion = false))!!
        assertNull(spec.distortion)
        assertTrue(spec.usable)
    }

    @Test
    fun `champ hors bornes ou date nulle refuse la mire`() {
        assertNull(mireLensOf(fields().copy(hFovDeg = 200f)))
        assertNull(mireLensOf(fields().copy(vFovDeg = Float.NaN)))
        assertNull(mireLensOf(fields().copy(cx = 1.5f)))
        assertNull(mireLensOf(fields().copy(cy = Float.NaN)))
        assertNull(mireLensOf(fields().copy(k1 = Float.POSITIVE_INFINITY)))
        assertNull(mireLensOf(fields().copy(atMs = 0L)))
        assertNull(mireLensOf(fields().copy(atMs = -1L)))
    }

    @Test
    fun `bloc solveur se lit en champs`() {
        val parsed = parseMireBlock(block())!!
        assertEquals(fields(), parsed)
    }

    @Test
    fun `bloc sans entete ou incomplet est refuse`() {
        assertNull(parseMireBlock("hFovDeg=60.61\n"))
        assertNull(parseMireBlock(""))
        assertNull(parseMireBlock(block().lines().dropLast(1).joinToString("\n")))
        assertNull(parseMireBlock(block().replace("cx=0.5", "cx=moitié")))
    }

    @Test
    fun `bloc solveur passe la validation`() {
        val spec = parseMireBlock(block())?.let(::mireLensOf)
        assertNotNull(spec)
        assertTrue(spec!!.usable)
        assertEquals(LensSpec(60.61f, 92.19f).horizontalFovDeg, spec.horizontalFovDeg, 0f)
    }

    @Test
    fun `ligne de statut dit la mire ou son absence`() {
        assertEquals(
            "Mire: none — glasses distances run on the estimated fallback",
            mireLine(null, null),
        )
        val spec = mireLensOf(fields())!!
        assertEquals("Mire: measured 60.6° × 92.2° (12 min ago)", mireLine(spec, 750_000L))
        assertEquals("Mire: measured 60.6° × 92.2°", mireLine(spec, -1L))
    }
}
