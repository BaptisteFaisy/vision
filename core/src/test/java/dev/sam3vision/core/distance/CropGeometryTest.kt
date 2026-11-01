package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Géométrie de crop du flux lunettes : rectangles centrés, FOV effectif,
 * mapping des boîtes.
 *
 * Exemple chiffré sur une entrée synthétique (`H = 68°`, `V = 100,4°` : ces
 * tests ne pinnent aucun FOV capteur, ils le prennent en entrée) :
 * `f = 533,7 px`, crop 16:9 `720×405`, `H' = 68,0°`, `V' = 41,6°`.
 */
class CropGeometryTest {

    private companion object {
        const val SRC_W = 720
        const val SRC_H = 1280
        const val H_STREAM = 68f
        /** V arbitraire : le résultat n'en dépend pas (pixels carrés). */
        const val V_STREAM = 100.4f
    }

    private val stream = LensSpec(H_STREAM, V_STREAM, sensorLandscape = false)
    private val crop169 = CropGeometry.Window(0, 437, 720, 405)

    @Test
    fun `le 16-9 du flux HIGH coupe le haut et le bas`() {
        assertEquals(crop169, CropGeometry.centerCropToAspect(SRC_W, SRC_H, 16, 9))
        // Parité avec les valeurs pinnées côté app (`GlassesFramePolicyTest`).
        assertEquals(
            CropGeometry.Window(0, 306, 504, 283),
            CropGeometry.centerCropToAspect(504, 896, 16, 9),
        )
        assertEquals(
            CropGeometry.Window(249, 0, 1422, 800),
            CropGeometry.centerCropToAspect(1920, 800, 16, 9),
        )
    }

    @Test
    fun `ratio exact ou degenere, rien a couper`() {
        assertNull(CropGeometry.centerCropToAspect(1280, 720, 16, 9))
        assertNull(CropGeometry.centerCropToAspect(16, 9, 16, 9))
        assertNull(CropGeometry.centerCropToAspect(0, 480, 16, 9))
        assertNull(CropGeometry.centerCropToAspect(640, 480, 0, 9))
    }

    @Test
    fun `remplit-la-vue, parite avec le chemin bitmap du direct`() {
        // Valeurs pinnées côté app (`GlassesBitmapCropTest`).
        assertEquals(
            CropGeometry.Window(0, 0, 720, 1280),
            CropGeometry.fillViewCrop(720, 1280, 720, 1280),
        )
        assertEquals(
            CropGeometry.Window(656, 0, 607, 1080),
            CropGeometry.fillViewCrop(1920, 1080, 720, 1280),
        )
        assertEquals(
            CropGeometry.Window(0, 308, 504, 280),
            CropGeometry.fillViewCrop(504, 896, 1080, 600),
        )
        assertEquals(
            CropGeometry.Window(0, 499, 1, 1),
            CropGeometry.fillViewCrop(1, 1000, 1000, 1),
        )
        assertEquals(
            CropGeometry.Window(0, 0, 0, 480),
            CropGeometry.fillViewCrop(0, 480, 1080, 600),
        )
    }

    @Test
    fun `largeur pleine gardee, le FOV horizontal est inchange`() {
        assertEquals(H_STREAM, CropGeometry.fovDegForPixels(533.712f, 720), 0.05f)
        assertEquals(41.55f, CropGeometry.fovDegForPixels(533.712f, 405), 0.1f)
        assertEquals(0f, CropGeometry.fovDegForPixels(0f, 720), 0f)
    }

    @Test
    fun `la spec post-crop est paysage et porte H en horizontal`() {
        val cropped = CropGeometry.afterCrop(stream, SRC_W, SRC_H, crop169)
        assertNotNull(cropped)
        cropped!!
        assertTrue(cropped.usable)
        assertTrue(cropped.sensorLandscape)
        assertEquals(H_STREAM, cropped.horizontalFovDeg, 0.05f)
        assertEquals(41.55f, cropped.verticalFovDeg, 0.1f)
        assertEquals(0.5f, cropped.principalXFrac, 0.0001f)
        assertEquals(0.5012f, cropped.principalYFrac, 0.0005f)
    }

    @Test
    fun `sans remappage le steneope prend le mauvais axe, moins 44 pour cent`() {
        // Le piège que `afterCrop` interdit : spec portrait sur image paysage.
        assertEquals(300.2f, stream.focalPx(720, 405), 0.5f)
        val cropped = CropGeometry.afterCrop(stream, SRC_W, SRC_H, crop169)!!
        assertEquals(533.7f, cropped.focalPx(720, 405), 0.5f)
        // À l'échelle d'envoi SAM (320×180) la focale suit, le FOV ne bouge pas.
        assertEquals(H_STREAM, cropped.widthFovDeg(320, 180), 0.001f)
        assertEquals(237.2f, cropped.focalPx(320, 180), 0.3f)
        // Personne 1,70 m / boîte 200 px → ~2,02 m (formule `Pinhole`).
        val box = listOf(0f, 0f, 50f, 200f)
        assertEquals(2.02f, Pinhole.distanceMeters(box, 1.7f, cropped.focalPx(320, 180))!!, 0.03f)
    }

    @Test
    fun `le crop live vue 1000x1600 garde la pleine largeur`() {
        val live = CropGeometry.fillViewCrop(SRC_W, SRC_H, 1000, 1600)
        assertEquals(CropGeometry.Window(0, 64, 720, 1152), live)
        val cropped = CropGeometry.afterCrop(stream, SRC_W, SRC_H, live)!!
        assertEquals(H_STREAM, cropped.horizontalFovDeg, 0.05f)
        assertEquals(94.4f, cropped.verticalFovDeg, 0.2f)
    }

    @Test
    fun `les boites se mappent dans les deux sens`() {
        val portrait = listOf(10f, 447f, 100f, 600f)
        val cropped = CropGeometry.mapBoxToCrop(portrait, crop169)
        assertEquals(listOf(10f, 10f, 100f, 163f), cropped)
        assertEquals(portrait, CropGeometry.mapBoxToSource(cropped, crop169))
        val short = listOf(1f, 2f)
        assertEquals(short, CropGeometry.mapBoxToCrop(short, crop169))
        assertEquals(short, CropGeometry.mapBoxToSource(short, crop169))
    }

    @Test
    fun `crop impossible, pas de spec inventee`() {
        val outside = CropGeometry.Window(0, 0, 100, 100)
        assertNull(CropGeometry.afterCrop(stream, SRC_W, SRC_H, outside))
        val overflow = CropGeometry.Window(0, 1000, 720, 405)
        assertNull(CropGeometry.afterCrop(stream, SRC_W, SRC_H, overflow))
        val empty = CropGeometry.Window(0, 437, 720, 0)
        assertNull(CropGeometry.afterCrop(stream, SRC_W, SRC_H, empty))
        val bad = LensSpec(0f, 0f)
        assertNull(CropGeometry.afterCrop(bad, SRC_W, SRC_H, crop169))
    }
}
