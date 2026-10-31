package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthAffineTest {

    /** Droite parfaite sans décalage : échelle 20, décalage 0. */
    @Test
    fun `ancres parfaites - echelle et decalage exacts`() {
        val cal = AffineScale.calibrate(
            listOf(DepthAnchor(2f, 10f), DepthAnchor(10f, 2f), DepthAnchor(4f, 5f)),
        )!!
        assertEquals(20f, cal.scale, 0.01f)
        assertEquals(0f, cal.shift, 0.01f)
    }

    /** Droite décalée 20/proximité + 1,5 : les deux paramètres retrouvés. */
    @Test
    fun `decalage non nul - echelle et decalage retrouves`() {
        val cal = AffineScale.calibrate(
            listOf(
                DepthAnchor(3.5f, 10f),
                DepthAnchor(11.5f, 2f),
                DepthAnchor(5.5f, 5f),
                DepthAnchor(6.5f, 4f),
            ),
        )!!
        assertEquals(20f, cal.scale, 0.05f)
        assertEquals(1.5f, cal.shift, 0.05f)
    }

    /** Une ancre à 3 m (droite : 6,5) : rejetée, les 4 saines gardées. */
    @Test
    fun `ancre aberrante - rejetee, ajustement intact`() {
        val cal = AffineScale.calibrate(
            listOf(
                DepthAnchor(3.5f, 10f),
                DepthAnchor(11.5f, 2f),
                DepthAnchor(5.5f, 5f),
                DepthAnchor(6.5f, 4f),
                DepthAnchor(3f, 4f),
            ),
        )!!
        assertEquals(20f, cal.scale, 0.05f)
        assertEquals(1.5f, cal.shift, 0.05f)
    }

    /** 1-2 ancres : repli échelle médiane + décalage 0 ; vide : null. */
    @Test
    fun `peu d ancres - repli median, vide - null`() {
        val one = AffineScale.calibrate(listOf(DepthAnchor(2f, 10f)))!!
        assertEquals(20f, one.scale, 0.001f)
        assertEquals(0f, one.shift, 0.001f)
        val two = AffineScale.calibrate(listOf(DepthAnchor(2f, 10f), DepthAnchor(10f, 2f)))!!
        assertEquals(20f, two.scale, 0.001f)
        assertEquals(0f, two.shift, 0.001f)
        assertNull(AffineScale.calibrate(emptyList()))
        assertNull(AffineScale.calibrate(listOf(DepthAnchor(-1f, 10f), DepthAnchor(2f, 0f))))
    }

    /** Gardes : entrées finies/positives, résultat strictement positif. */
    @Test
    fun `apply - gardes finis et positifs`() {
        assertEquals(2f, AffineScale.apply(10f, AffineCalibration(20f, 0f))!!, 1e-6f)
        assertEquals(3.5f, AffineScale.apply(10f, AffineCalibration(20f, 1.5f))!!, 1e-6f)
        assertNull(AffineScale.apply(0f, AffineCalibration(20f, 0f)))
        assertNull(AffineScale.apply(Float.NaN, AffineCalibration(20f, 0f)))
        assertNull(AffineScale.apply(10f, AffineCalibration(0f, 0f)))
        assertNull(AffineScale.apply(10f, AffineCalibration(20f, Float.NaN)))
        assertNull(AffineScale.apply(10f, AffineCalibration(20f, -5f)))
    }

    /** Affinage avec 3 ancres décalées : la cible suit 20/x + 1. */
    @Test
    fun `affine cable - cible sur la droite decalée`() {
        val a = Detection("a", 0.9f, listOf(0f, 0f, 10f, 10f), 100, 100)
        val b = Detection("b", 0.9f, listOf(10f, 0f, 20f, 10f), 100, 100)
        val c = Detection("c", 0.9f, listOf(20f, 0f, 30f, 10f), 100, 100)
        val target = Detection("cible", 0.8f, listOf(30f, 0f, 40f, 10f), 100, 100)
        val pinhole = listOf(
            DistanceReading(1, "a", 3f, 3f, a.box),
            DistanceReading(2, "b", 5f, 5f, b.box),
            DistanceReading(3, "c", 11f, 11f, c.box),
        )
        val close = mapOf(a.box to 10f, b.box to 5f, c.box to 2f, target.box to 4f)
        val out = DepthReadings.refineWithCloseness(listOf(a, b, c, target), pinhole, close)
        val got = out.first { it.label == "cible" }
        assertEquals(6f, got.meters, 0.05f)
    }
}
