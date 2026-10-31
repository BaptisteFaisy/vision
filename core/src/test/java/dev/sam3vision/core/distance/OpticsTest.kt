package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optique Camera2 : zone active du capteur, angles et focale en pixels (`f_px`).
 *
 * Le capteur de référence est celui des téléphones du poste : tableau 4 000×3 000 px,
 * 5,76×4,32 mm, focale 4,3 mm — soit ~67,6° horizontaux et `f_px = 382,2` pour une
 * image 512×384.
 */
class OpticsTest {

    private companion object {
        const val ARRAY_W = 4000
        const val ARRAY_H = 3000
        const val PHYS_W = 5.76f
        const val PHYS_H = 4.32f
        const val FOCAL = 4.3f
    }

    /** `f_px` du capteur de référence pour une image `[w]`×`[h]`. */
    private fun refFocalPx(w: Int, h: Int): Float =
        Optics.focalPx(FOCAL, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H, w, h)

    /** Calibrage du capteur de référence, zone active explicite. */
    private fun refLens(activeW: Int = ARRAY_W, activeH: Int = ARRAY_H): LensSpec? =
        Optics.lensSpec(FOCAL, PHYS_W, PHYS_H, activeW, activeH, ARRAY_W, ARRAY_H)

    @Test
    fun `capteur plein donne les deux angles du constructeur`() {
        val sensor = Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H)
        assertNotNull(sensor)
        assertEquals(PHYS_W, sensor!!.widthMm, 0.001f)
        assertEquals(PHYS_H, sensor.heightMm, 0.001f)
        val spec = Optics.lensSpec(FOCAL, sensor)!!
        assertEquals(67.63f, spec.horizontalFovDeg, 0.1f)
        assertEquals(53.34f, spec.verticalFovDeg, 0.1f)
        assertTrue(spec.sensorLandscape)
    }

    @Test
    fun `focale en pixels de l'image analysee`() {
        assertEquals(382.2f, refFocalPx(512, 384), 0.5f)
    }

    @Test
    fun `focale en pixels suit la formule des millimetres`() {
        // f_px = focale(mm) / largeur utile(mm) × largeur image(px) : la définition même
        assertEquals(FOCAL / PHYS_W * 512f, refFocalPx(512, 384), 0.01f)
        assertEquals(FOCAL / PHYS_W * 1024f, refFocalPx(1024, 768), 0.02f)
    }

    @Test
    fun `telephone cible donne les angles mesures`() {
        // Redmi Note 13 Pro, caméra 0 (dumpsys media.camera du 2026-10-31) :
        // focale 6,14 mm, tableau 9,1392×6,8544 mm, zone active = tableau 4080×3060.
        // La chaîne live lit ces valeurs (CameraLens) : ce test verrouille le bout en bout.
        val sensor = Optics.activeSensor(9.1392f, 6.8544f, 4080, 3060, 4080, 3060)
        assertNotNull(sensor)
        val spec = Optics.lensSpec(6.14f, sensor)!!
        assertEquals(73.32f, spec.horizontalFovDeg, 0.05f)
        assertEquals(58.34f, spec.verticalFovDeg, 0.05f)
        assertEquals(2741.07f, spec.focalPx(4080, 3060), 0.5f)
        assertEquals(343.98f, spec.focalPx(512, 384), 0.1f)
    }

    @Test
    fun `focale en pixels ne change pas avec la rotation`() {
        // même optique : redresser une frame ne change pas sa focale en pixels
        assertEquals(refFocalPx(512, 384), refFocalPx(384, 512), 1f)
    }

    @Test
    fun `focale en pixels est proportionnelle a la largeur`() {
        assertEquals(refFocalPx(512, 384) / 2f, refFocalPx(256, 192), 0.5f)
    }

    @Test
    fun `recadrage vertical ne change pas la focale en pixels`() {
        // la largeur utile reste 5,76 mm : l'axe qui porte f_px est intact
        assertEquals(382.2f, Optics.focalPx(FOCAL, PHYS_W, PHYS_H, ARRAY_W, 2250, ARRAY_W, ARRAY_H, 512, 384), 0.5f)
        assertEquals(67.63f, refLens(ARRAY_W, 2250)!!.horizontalFovDeg, 0.1f)
    }

    @Test
    fun `recadrage vertical resserre le champ vertical`() {
        assertEquals(3.24f, Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, 2250, ARRAY_W, ARRAY_H)!!.heightMm, 0.001f)
        assertEquals(41.29f, refLens(ARRAY_W, 2250)!!.verticalFovDeg, 0.2f)
    }

    @Test
    fun `recadrage 80 pourcent applique le facteur de recadrage`() {
        val cropped = Optics.focalPx(FOCAL, PHYS_W, PHYS_H, 3200, 2400, ARRAY_W, ARRAY_H, 512, 384)
        assertEquals(477.8f, cropped, 1f)
        assertEquals(refFocalPx(512, 384) / 0.8f, cropped, 0.5f)
        assertEquals(4.608f, Optics.activeSensor(PHYS_W, PHYS_H, 3200, 2400, ARRAY_W, ARRAY_H)!!.widthMm, 0.001f)
    }

    @Test
    fun `zone active decalee est mise a l'echelle du tableau`() {
        val sensor = Optics.activeSensor(PHYS_W, PHYS_H, 3968, 2976, ARRAY_W, ARRAY_H)!!
        assertEquals(5.71392f, sensor.widthMm, 0.0001f)
        assertEquals(4.28544f, sensor.heightMm, 0.0001f)
        assertEquals(67.2f, Optics.lensSpec(FOCAL, sensor)!!.horizontalFovDeg, 0.1f)
    }

    @Test
    fun `zone active egale au tableau ne change rien`() {
        val sensor = Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H)
        assertEquals(Optics.lensSpec(FOCAL, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H), Optics.lensSpec(FOCAL, sensor))
    }

    @Test
    fun `zone active plus grande que le tableau est refusee`() {
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W + 1, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H + 1, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `zone active nulle ou negative est refusee`() {
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, 0, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, -1, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `tableau de pixels nul est refuse`() {
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, 0, ARRAY_H))
        assertNull(Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, 0))
    }

    @Test
    fun `taille physique hors bornes est refusee`() {
        assertNull(Optics.activeSensor(0f, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.activeSensor(PHYS_W, Optics.SENSOR_MM_MIN - 0.1f, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.activeSensor(Optics.SENSOR_MM_MAX + 1f, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `bornes basses acceptees`() {
        assertNotNull(Optics.activeSensor(Optics.SENSOR_MM_MIN, 0.75f, 1000, 750, 1000, 750))
        assertNotNull(Optics.activeSensor(Optics.SENSOR_MM_MAX, 45f, 1000, 750, 1000, 750))
        // focale minimale sur un petit capteur : 90° horizontaux, encore mesurable
        val spec = Optics.lensSpec(Optics.FOCAL_MM_MIN, 1.0f, 0.75f, 1000, 750, 1000, 750)
        assertNotNull(spec)
        assertEquals(90f, spec!!.horizontalFovDeg, 0.1f)
    }

    @Test
    fun `focale nulle ou negative est refusee`() {
        assertNull(Optics.lensSpec(0f, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpec(0f, Optics.activeSensor(PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H)))
        assertNull(Optics.lensSpec(-4.3f, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `focale hors bornes est refusee`() {
        assertNull(Optics.lensSpec(Optics.FOCAL_MM_MIN - 0.1f, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpec(Optics.FOCAL_MM_MAX + 1f, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `pas de focale en pixels sans calibrage`() {
        assertEquals(0f, Optics.focalPx(0f, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H, 512, 384), 0f)
        assertEquals(0f, refFocalPx(0, 0), 0f)
    }

    @Test
    fun `capteur natif portrait inverse les axes`() {
        val spec = Optics.lensSpec(FOCAL, 3.2f, 4.3f, 3200, 4300, 3200, 4300)!!
        assertFalse(spec.sensorLandscape)
        assertEquals(40.82f, spec.horizontalFovDeg, 0.1f)
        // image portrait : sa largeur porte l'axe horizontal du capteur, donc 4,3/3,2×384
        assertEquals(516f, Optics.focalPx(FOCAL, 3.2f, 4.3f, 3200, 4300, 3200, 4300, 384, 512), 1f)
    }

    @Test
    fun `ultra grand angle reste mesurable`() {
        val spec = Optics.lensSpec(2.0f, 3.6f, 2.7f, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H)!!
        assertEquals(83.97f, spec.horizontalFovDeg, 0.2f)
        assertEquals(2.0f / 3.6f * 512f, Optics.focalPx(2.0f, 3.6f, 2.7f, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H, 512, 384), 1f)
    }

    @Test
    fun `teleobjectif hors champ est ecarte au profit du repli`() {
        // 60 mm sur 5,76 mm de large : 5,5° horizontaux, sous FOV_MIN (15°) → repli Réglages
        assertNull(Optics.lensSpec(Optics.FOCAL_MM_MAX, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
        assertEquals(0f, Optics.focalPx(Optics.FOCAL_MM_MAX, PHYS_W, PHYS_H, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H, 512, 384), 0f)
    }

    @Test
    fun `fish eye hors champ est ecarte`() {
        // 1 mm sur 6,4 mm de large : 145° horizontaux, au-delà de FOV_MAX (130°) : la
        // projection sténopé n'y tient plus
        assertNull(Optics.lensSpec(1.0f, 6.4f, 4.8f, ARRAY_W, ARRAY_H, ARRAY_W, ARRAY_H))
    }

    @Test
    fun `raccourci des caracteristiques delegue au calcul de zone active`() {
        val shortcut = Optics.lensSpec(FOCAL, PHYS_W, PHYS_H, 3200, 2400, ARRAY_W, ARRAY_H)
        val explicit = Optics.lensSpec(FOCAL, Optics.activeSensor(PHYS_W, PHYS_H, 3200, 2400, ARRAY_W, ARRAY_H))
        assertNotNull(shortcut)
        assertEquals(explicit, shortcut)
    }

    @Test
    fun `calibrage sans zone active est nul`() {
        assertNull(Optics.lensSpec(FOCAL, null))
    }

    @Test
    fun `calibration intrinseque donne focale et centre exacts`() {
        // mêmes angles que la voie mm (67,63° / 53,34°), centre au milieu
        val spec = Optics.lensSpecFromIntrinsics(2986.1f, 2986.1f, 1999.5f, 1499.5f, ARRAY_W, ARRAY_H)!!
        assertEquals(67.63f, spec.horizontalFovDeg, 0.1f)
        assertEquals(53.34f, spec.verticalFovDeg, 0.1f)
        assertEquals(0.5f, spec.principalXFrac, 0.001f)
        assertEquals(0.5f, spec.principalYFrac, 0.001f)
        assertEquals(382.2f, spec.focalPx(512, 384), 0.5f)
    }

    @Test
    fun `calibration intrinseque decentree garde son centre`() {
        val spec = Optics.lensSpecFromIntrinsics(2986.1f, 2986.1f, 2050f, 1450f, ARRAY_W, ARRAY_H)!!
        assertEquals(2050f / 3999f, spec.principalXFrac, 0.0001f)
        assertEquals(1450f / 2999f, spec.principalYFrac, 0.0001f)
        val (cx, cy) = spec.principalPx(512, 384)
        assertEquals(2050f / 3999f * 511f, cx, 0.01f)
        assertEquals(1450f / 2999f * 383f, cy, 0.01f)
    }

    @Test
    fun `calibration intrinseque incoherente retombe sur la voie millimetres`() {
        assertNull(Optics.lensSpecFromIntrinsics(0f, 2986f, 1999f, 1499f, ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpecFromIntrinsics(2986f, 2986f, -5f, 1499f, ARRAY_W, ARRAY_H))
        assertNull(Optics.lensSpecFromIntrinsics(2986f, 2986f, 1999f, 1499f, 0, ARRAY_H))
        // téléobjectif : 3,8° horizontaux, hors bornes comme par la voie mm
        assertNull(Optics.lensSpecFromIntrinsics(60000f, 60000f, 1999f, 1499f, ARRAY_W, ARRAY_H))
    }
}