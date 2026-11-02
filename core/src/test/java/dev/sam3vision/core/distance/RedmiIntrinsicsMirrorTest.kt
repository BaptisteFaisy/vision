package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Miroir intrinsèques du téléphone de test (Redmi `2c6852ac`, cam 0) : la voie
 * `LENS_INTRINSIC_CALIBRATION` rejoint la voie mm d'`OpticsTest` (même dumpsys
 * du 2026-10-31), gardes de la voie intrinsèques, et E2E JVM minimal
 * (lentille → `DistanceMeter.measure`). 100 % JVM : la CI l'exécute.
 */
class RedmiIntrinsicsMirrorTest {

    private companion object {
        const val FOCAL_MM = 6.14f
        const val PHYS_W = 9.1392f
        const val PHYS_H = 6.8544f
        const val ARRAY_W = 4080
        const val ARRAY_H = 3060
        const val FX = 2741.0713f
        const val FY = 2741.0713f
        const val CX = 2040f
        const val CY = 1530f
    }

    private fun mmSpec(): LensSpec =
        Optics.lensSpec(FOCAL_MM, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H)!!

    private fun intrinsicsSpec(): LensSpec =
        Optics.lensSpecFromIntrinsics(FX, FY, CX, CY, ARRAY_W, ARRAY_H)!!

    @Test
    fun `voies mm et intrinseques du Redmi donnent le meme calibrage`() {
        val mm = mmSpec()
        val intr = intrinsicsSpec()
        assertTrue(mm.usable)
        assertTrue(intr.usable)
        assertEquals(mm.horizontalFovDeg, intr.horizontalFovDeg, 0.02f)
        assertEquals(mm.verticalFovDeg, intr.verticalFovDeg, 0.02f)
        assertEquals(73.32f, intr.horizontalFovDeg, 0.02f)
        assertEquals(58.34f, intr.verticalFovDeg, 0.02f)
        assertEquals(mm.focalPx(512, 384), intr.focalPx(512, 384), 0.5f)
        assertEquals(mm.focalPx(ARRAY_W, ARRAY_H), intr.focalPx(ARRAY_W, ARRAY_H), 0.5f)
        assertEquals(0.5f, intr.principalXFrac, 0.001f)
        assertEquals(0.5f, intr.principalYFrac, 0.001f)
    }

    @Test
    fun `intrinseques non finis ou focale nulle retombent sur la voie millimetres`() {
        listOf(
            Optics.lensSpecFromIntrinsics(Float.NaN, FY, CX, CY, ARRAY_W, ARRAY_H),
            Optics.lensSpecFromIntrinsics(FX, Float.POSITIVE_INFINITY, CX, CY, ARRAY_W, ARRAY_H),
            Optics.lensSpecFromIntrinsics(FX, FY, Float.NaN, CY, ARRAY_W, ARRAY_H),
            Optics.lensSpecFromIntrinsics(FX, FY, CX, Float.POSITIVE_INFINITY, ARRAY_W, ARRAY_H),
            Optics.lensSpecFromIntrinsics(-FX, FY, CX, CY, ARRAY_W, ARRAY_H),
            Optics.lensSpecFromIntrinsics(FX, 0f, CX, CY, ARRAY_W, ARRAY_H),
        ).forEach { assertNull(it) }
    }

    @Test
    fun `centre intrinseque accepte aux bords exacts, refuse dehors`() {
        assertNotNull(Optics.lensSpecFromIntrinsics(FX, FY, 0f, 0f, ARRAY_W, ARRAY_H))
        val corner = Optics.lensSpecFromIntrinsics(
            FX, FY, (ARRAY_W - 1).toFloat(), (ARRAY_H - 1).toFloat(), ARRAY_W, ARRAY_H,
        )!!
        assertEquals(1f, corner.principalXFrac, 0f)
        assertEquals(1f, corner.principalYFrac, 0f)
        assertTrue(corner.usable)
        assertNull(Optics.lensSpecFromIntrinsics(FX, FY, ARRAY_W.toFloat(), CY, ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpecFromIntrinsics(FX, FY, CX, ARRAY_H.toFloat(), ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpecFromIntrinsics(FX, FY, CX, CY, 1, ARRAY_H))
        assertNull(Optics.lensSpecFromIntrinsics(FX, FY, CX, CY, ARRAY_W, -1))
    }

    @Test
    fun `intrinseques hors champ sortent des bornes, asymetrie acceptee`() {
        // Focale minuscule : 174° horizontaux, fish-eye comme par la voie mm.
        assertNull(Optics.lensSpecFromIntrinsics(100f, 100f, CX, CY, ARRAY_W, ARRAY_H))
        // Axe vertical seul hors bornes : 2,9°, téléobjectif.
        assertNull(Optics.lensSpecFromIntrinsics(FX, 60000f, CX, CY, ARRAY_W, ARRAY_H))
        // Pixels non carrés : un champ par axe, les deux plausibles.
        val anamorphic = Optics.lensSpecFromIntrinsics(FX, 3000f, CX, CY, ARRAY_W, ARRAY_H)!!
        assertEquals(73.32f, anamorphic.horizontalFovDeg, 0.02f)
        assertEquals(54.03f, anamorphic.verticalFovDeg, 0.1f)
        assertTrue(anamorphic.usable)
    }

    @Test
    fun `e2e une personne de 200 px sort a 2,92 m sur lentille intrinseques`() {
        // 1,70 m × 343,98 px / 200 px = 2,92 m ; sans piste, brut = lissé.
        val meter = DistanceMeter()
        meter.setLens(intrinsicsSpec())
        val box = listOf(156f, 92f, 356f, 292f)
        val readings = meter.measure(512, 384, listOf(Detection("person", 0.9f, box, 512, 384)))
        assertEquals(1, readings.size)
        val reading = readings.single()
        assertEquals(2.92f, reading.meters, 0.02f)
        assertEquals(reading.rawMeters, reading.meters, 0f)
        assertEquals(DistanceMeter.UNTRACKED_ID, reading.trackId)
    }
}
