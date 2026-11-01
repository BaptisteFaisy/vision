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

    @Test
    fun `la distorsion se propage renormalisee au crop`() {
        // Mire type (`GlassesMireTest` : k1 = -0,1, k2 = 0,02) sur le flux HIGH.
        val mire = stream.copy(distortion = RadialDistortion(-0.1f, 0.02f))
        val cropped = CropGeometry.afterCrop(mire, SRC_W, SRC_H, crop169)!!
        assertTrue(cropped.usable)
        assertTrue(cropped.hasMeasuredDistortion)
        // (n'/n)^2 = 81/256, (n'/n)^4 = (81/256)^2 : 720x1280 -> 720x405.
        assertEquals(-0.03164f, cropped.distortion!!.k1, 0.00002f)
        assertEquals(0.002f, cropped.distortion.k2, 0.00002f)
        // Coeffs nuls mesures : propages non-nuls (mesure sans distorsion).
        val zero = CropGeometry.afterCrop(
            stream.copy(distortion = RadialDistortion(0f, 0f)), SRC_W, SRC_H, crop169,
        )!!
        assertEquals(RadialDistortion(0f, 0f), zero.distortion)
    }

    @Test
    fun `redresser commute avec le crop, au centieme de pixel`() {
        // Grille 9x9 du crop : redresser en source puis mapper == mapper puis
        // redresser en crop (preuve float64 ~1e-13, bruit float32 seul ici).
        val mire = stream.copy(distortion = RadialDistortion(-0.1f, 0.02f))
        val cropped = CropGeometry.afterCrop(mire, SRC_W, SRC_H, crop169)!!
        val (cx, cy) = mire.principalPx(SRC_W, SRC_H)
        val (ccx, ccy) = cropped.principalPx(crop169.width, crop169.height)
        val ns = Distortion.normPx(SRC_W, SRC_H)
        val nc = Distortion.normPx(crop169.width, crop169.height)
        for (gx in 0..8) for (gy in 0..8) {
            val x = gx * (SRC_W - 1) / 8f
            val y = crop169.y + gy * (crop169.height - 1) / 8f
            val px = x - crop169.x
            val py = y - crop169.y
            assertEquals(
                Distortion.undistortX(x, y, cx, cy, ns, mire.distortion) - crop169.x,
                Distortion.undistortX(px, py, ccx, ccy, nc, cropped.distortion), 0.01f,
            )
            assertEquals(
                Distortion.undistortY(x, y, cx, cy, ns, mire.distortion) - crop169.y,
                Distortion.undistortY(px, py, ccx, ccy, nc, cropped.distortion), 0.01f,
            )
        }
    }

    @Test
    fun `recentre ou pas, la propagation reste exacte`() {
        // Crop NON centre en x (remplit-la-vue 1920x1080 -> 720x1280) : `r`
        // part du centre optique remappe, le recentrage ne casse rien.
        val src = LensSpec(H_STREAM, V_STREAM, sensorLandscape = false)
            .copy(distortion = RadialDistortion(-0.1f, 0.02f))
        val crop = CropGeometry.fillViewCrop(1920, 1080, 720, 1280)
        assertEquals(CropGeometry.Window(656, 0, 607, 1080), crop)
        val cropped = CropGeometry.afterCrop(src, 1920, 1080, crop)!!
        val (cx, cy) = src.principalPx(1920, 1080)
        val (ccx, ccy) = cropped.principalPx(crop.width, crop.height)
        val ns = Distortion.normPx(1920, 1080)
        val nc = Distortion.normPx(crop.width, crop.height)
        for (gx in 0..4) for (gy in 0..4) {
            val x = crop.x + gx * (crop.width - 1) / 4f
            val y = crop.y + gy * (crop.height - 1) / 4f
            assertEquals(
                Distortion.undistortY(x, y, cx, cy, ns, src.distortion) - crop.y,
                Distortion.undistortY(x - crop.x, y - crop.y, ccx, ccy, nc, cropped.distortion),
                0.01f,
            )
        }
    }

    @Test
    fun `sans mesure, pas de distorsion inventee`() {
        val cropped = CropGeometry.afterCrop(stream, SRC_W, SRC_H, crop169)!!
        assertNull(cropped.distortion)
    }

    @Test
    fun `le null post-crop sous-estimait de 2 pour cent au bord`() {
        // Boite 390 px au bord gauche du crop 16:9, mire (-0,1 ; 0,02) :
        // hauteur redressee 381,6 px -> le null sous-estimait de 2,2 %.
        val mire = stream.copy(distortion = RadialDistortion(-0.1f, 0.02f))
        val cropped = CropGeometry.afterCrop(mire, SRC_W, SRC_H, crop169)!!
        val focal = cropped.focalPx(crop169.width, crop169.height)
        val (ccx, ccy) = cropped.principalPx(crop169.width, crop169.height)
        val nc = Distortion.normPx(crop169.width, crop169.height)
        val top = Distortion.undistortY(70f, 10f, ccx, ccy, nc, cropped.distortion)
        val bottom = Distortion.undistortY(70f, 400f, ccx, ccy, nc, cropped.distortion)
        assertEquals(381.6f, bottom - top, 0.3f)
        val box = listOf(10f, 10f, 130f, 400f)
        val brut = Pinhole.distanceMeters(box, 1.7f, focal)!!
        val corrige = 1.7f * focal / (bottom - top)
        assertEquals(0.022f, (corrige - brut) / brut, 0.002f)
    }
}
